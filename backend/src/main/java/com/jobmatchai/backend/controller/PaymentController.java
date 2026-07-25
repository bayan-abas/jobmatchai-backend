package com.jobmatchai.backend.controller;

import com.jobmatchai.backend.model.User;
import com.jobmatchai.backend.repository.UserRepository;
import com.jobmatchai.backend.service.NotificationService;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.Invoice;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NotificationService notificationService;

    @Value("${stripe.secret-key}")
    private String stripeSecretKey;

    @Value("${stripe.webhook-secret}")
    private String webhookSecret;

    @Value("${stripe.premium-price-id}")
    private String premiumPriceId;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    private static final long PREMIUM_MONTHLY_PRICE_CENTS = 999L;

    private static final Set<String> LOST_ACCESS_STATUSES = Set.of("canceled", "unpaid", "incomplete_expired");

    // יוצר Checkout Session ב-Stripe עבור שדרוג המועמד למנוי פרימיום חודשי
    @PostMapping("/create-checkout-session")
    @PreAuthorize("hasRole('CANDIDATE')")
    public ResponseEntity<?> createCheckoutSession(Authentication authentication) {
        if (stripeSecretKey == null || stripeSecretKey.isBlank()) {
            return ResponseEntity.status(503).body(Map.of(
                    "success", false,
                    "message", "Payments are not configured on this server yet."
            ));
        }

        try {
            SessionCreateParams.LineItem lineItem;

            if (premiumPriceId != null && !premiumPriceId.isBlank()) {
                lineItem = SessionCreateParams.LineItem.builder()
                        .setQuantity(1L)
                        .setPrice(premiumPriceId)
                        .build();
            } else {
                lineItem = SessionCreateParams.LineItem.builder()
                        .setQuantity(1L)
                        .setPriceData(
                                SessionCreateParams.LineItem.PriceData.builder()
                                        .setCurrency("usd")
                                        .setUnitAmount(PREMIUM_MONTHLY_PRICE_CENTS)
                                        .setRecurring(
                                                SessionCreateParams.LineItem.PriceData.Recurring.builder()
                                                        .setInterval(SessionCreateParams.LineItem.PriceData.Recurring.Interval.MONTH)
                                                        .build()
                                        )
                                        .setProductData(
                                                SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                                        .setName("JobMatchAI Premium (Monthly)")
                                                        .build()
                                        )
                                        .build()
                        )
                        .build();
            }

            SessionCreateParams params = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                    .setSuccessUrl(frontendUrl + "/payment/success?session_id={CHECKOUT_SESSION_ID}")
                    .setCancelUrl(frontendUrl + "/payment/cancel")
                    .setCustomerEmail(authentication.getName())
                    .putMetadata("candidateEmail", authentication.getName())

                    .setSubscriptionData(
                            SessionCreateParams.SubscriptionData.builder()
                                    .putMetadata("candidateEmail", authentication.getName())
                                    .build()
                    )
                    .addLineItem(lineItem)
                    .build();

            Session session = Session.create(params);

            return ResponseEntity.ok(Map.of("url", session.getUrl()));

        } catch (StripeException e) {
            log.error("Failed to start Stripe checkout for candidate={}", authentication.getName(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "Failed to start checkout. Please try again."
            ));
        }
    }

    // מאמת מול Stripe שה-checkout הושלם ותשלום התקבל, ומפעיל פרימיום למועמד
    @GetMapping("/confirm")
    @PreAuthorize("hasRole('CANDIDATE')")
    public ResponseEntity<?> confirm(@RequestParam("session_id") String sessionId, Authentication authentication) {
        if (stripeSecretKey == null || stripeSecretKey.isBlank()) {
            return ResponseEntity.status(503).body(Map.of("success", false, "message", "Payments are not configured."));
        }

        try {
            Session session = Session.retrieve(sessionId);

            String sessionCandidateEmail = session.getMetadata() != null
                    ? session.getMetadata().get("candidateEmail")
                    : null;
            if (sessionCandidateEmail == null || !sessionCandidateEmail.equalsIgnoreCase(authentication.getName())) {
                return ResponseEntity.status(403).body(Map.of("success", false, "message", "This checkout session does not belong to you."));
            }

            if (!"paid".equals(session.getPaymentStatus())) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Payment not completed."));
            }

            activatePremium(session);

            return ResponseEntity.ok(Map.of("success", true, "message", "Premium activated."));

        } catch (StripeException e) {
            log.error("Failed to confirm Stripe payment for candidate={} sessionId={}", authentication.getName(), sessionId, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "Failed to confirm payment. Please try again."
            ));
        }
    }

    // מטפל בהתראות Webhook מ-Stripe (תשלום הושלם/מנוי בוטל/מנוי עודכן/תשלום נכשל) ומעדכן את סטטוס הפרימיום בהתאם
    @PostMapping("/webhook")
    public ResponseEntity<?> webhook(@RequestBody String payload, @RequestHeader("Stripe-Signature") String sigHeader) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            return ResponseEntity.status(503).body("Webhook not configured.");
        }

        Event event;

        try {
            event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            return ResponseEntity.badRequest().body("Invalid signature");
        }

        switch (event.getType()) {
            case "checkout.session.completed" -> event.getDataObjectDeserializer().getObject().ifPresent(obj -> {
                if (obj instanceof Session session) {
                    String candidateEmail = session.getMetadata() != null
                            ? session.getMetadata().get("candidateEmail")
                            : null;

                    if (candidateEmail != null && "paid".equals(session.getPaymentStatus())) {
                        activatePremium(session);
                    }
                }
            });

            case "customer.subscription.deleted" -> event.getDataObjectDeserializer().getObject().ifPresent(obj -> {
                if (obj instanceof Subscription subscription) {
                    revokePremiumFor(subscription, "Your JobMatchAI Premium subscription has ended.");
                }
            });

            case "customer.subscription.updated" -> event.getDataObjectDeserializer().getObject().ifPresent(obj -> {
                if (obj instanceof Subscription subscription) {
                    String status = subscription.getStatus();
                    if (LOST_ACCESS_STATUSES.contains(status)) {
                        revokePremiumFor(subscription, "Your JobMatchAI Premium subscription has ended.");
                    } else if ("active".equals(status) || "trialing".equals(status)) {
                        restorePremiumFor(subscription);
                    }
                }
            });

            case "invoice.payment_failed" -> event.getDataObjectDeserializer().getObject().ifPresent(obj -> {
                if (obj instanceof Invoice invoice) {
                    notifyPaymentFailed(invoice);
                }
            });

            default -> log.debug("Ignoring unhandled Stripe event type: {}", event.getType());
        }

        return ResponseEntity.ok(Map.of("received", true));
    }

    // מאתר את המשתמש ששייך למנוי לפי subscription id, ואם לא נמצא לפי המייל או ה-customer id
    User findUserForSubscription(Subscription subscription) {
        User user = userRepository.findByStripeSubscriptionId(subscription.getId());
        if (user != null) {
            return user;
        }

        String candidateEmail = subscription.getMetadata() != null
                ? subscription.getMetadata().get("candidateEmail")
                : null;
        if (candidateEmail != null) {
            return userRepository.findByEmail(candidateEmail);
        }

        return userRepository.findByStripeCustomerId(subscription.getCustomer());
    }

    // מבטל את סטטוס הפרימיום של המשתמש ושולח לו התראה על סיום המנוי
    void revokePremiumFor(Subscription subscription, String message) {
        User user = findUserForSubscription(subscription);
        if (user == null || !user.isPremium()) {

            return;
        }

        user.setPremium(false);
        userRepository.save(user);

        notificationService.createNotification(
                user.getEmail(), "Premium Ended", message, "PREMIUM_CANCELLED");
    }

    // מחזיר את הפרימיום למשתמש לאחר שהמנוי חזר להיות פעיל (למשל לאחר תשלום מאוחר שהצליח)
    void restorePremiumFor(Subscription subscription) {
        User user = findUserForSubscription(subscription);
        if (user == null || user.isPremium()) {
            return;
        }

        user.setPremium(true);
        user.setPremiumSince(LocalDateTime.now());
        user.setStripeSubscriptionId(subscription.getId());
        user.setStripeCustomerId(subscription.getCustomer());
        userRepository.save(user);

        notificationService.createNotification(
                user.getEmail(), "Premium Reactivated",
                "Your payment went through and JobMatchAI Premium is active again.",
                "PREMIUM_ACTIVATED");
    }

    // שולח למשתמש התראה שהחיוב האחרון על המנוי נכשל
    void notifyPaymentFailed(Invoice invoice) {
        String subscriptionId = invoice.getParent() != null && invoice.getParent().getSubscriptionDetails() != null
                ? invoice.getParent().getSubscriptionDetails().getSubscription()
                : null;

        User user = subscriptionId != null ? userRepository.findByStripeSubscriptionId(subscriptionId) : null;
        if (user == null) {
            user = userRepository.findByStripeCustomerId(invoice.getCustomer());
        }
        if (user == null) {
            return;
        }

        notificationService.createNotification(
                user.getEmail(),
                "Payment Failed",
                "We couldn't process your last Premium payment. Please update your payment method to avoid losing access.",
                "PREMIUM_PAYMENT_FAILED");
    }

    // מבטל את מנוי הפרימיום דרך Stripe עבור המועמד המחובר
    @PostMapping("/cancel-subscription")
    @PreAuthorize("hasRole('CANDIDATE')")
    public ResponseEntity<?> cancelSubscription(Authentication authentication) {
        if (stripeSecretKey == null || stripeSecretKey.isBlank()) {
            return ResponseEntity.status(503).body(Map.of(
                    "success", false,
                    "message", "Payments are not configured on this server yet."
            ));
        }

        User user = userRepository.findByEmail(authentication.getName());

        if (user == null || user.getStripeSubscriptionId() == null || user.getStripeSubscriptionId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "No active subscription found."
            ));
        }

        try {
            Subscription subscription = Subscription.retrieve(user.getStripeSubscriptionId());
            subscription.cancel();

            user.setPremium(false);
            userRepository.save(user);

            notificationService.createNotification(
                    user.getEmail(),
                    "Premium Cancelled",
                    "Your JobMatchAI Premium subscription has been cancelled.",
                    "PREMIUM_CANCELLED"
            );

            return ResponseEntity.ok(Map.of("success", true, "message", "Subscription cancelled."));
        } catch (StripeException e) {
            log.error("Failed to cancel Stripe subscription for candidate={}", user.getEmail(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "Failed to cancel subscription. Please try again."
            ));
        }
    }

    // מפעיל פרימיום ידנית ללא תשלום אמיתי - למטרות הדגמה בלבד
    @PostMapping("/demo/activate-premium")
    @PreAuthorize("hasRole('CANDIDATE')")
    public ResponseEntity<?> activatePremiumDemo(Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName());
        if (user == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "User not found."));
        }

        if (user.isPremium()) {
            return ResponseEntity.ok(Map.of("success", true, "message", "Premium already active."));
        }

        user.setPremium(true);
        user.setPremiumSince(LocalDateTime.now());
        userRepository.save(user);

        notificationService.createNotification(
                user.getEmail(),
                "Premium Activated",
                "Your JobMatchAI Premium subscription is now active.",
                "PREMIUM_ACTIVATED"
        );

        return ResponseEntity.ok(Map.of("success", true, "message", "Premium activated."));
    }

    // מבטל פרימיום ידנית ללא Stripe - למטרות הדגמה בלבד
    @PostMapping("/demo/cancel-premium")
    @PreAuthorize("hasRole('CANDIDATE')")
    public ResponseEntity<?> cancelPremiumDemo(Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName());
        if (user == null || !user.isPremium()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "No active subscription found."));
        }

        user.setPremium(false);
        userRepository.save(user);

        notificationService.createNotification(
                user.getEmail(),
                "Premium Cancelled",
                "Your JobMatchAI Premium subscription has been cancelled.",
                "PREMIUM_CANCELLED"
        );

        return ResponseEntity.ok(Map.of("success", true, "message", "Subscription cancelled."));
    }

    // מפעיל בפועל את מנוי הפרימיום למשתמש לפי session מוצלח ושומר את פרטי ה-Stripe שלו
    private void activatePremium(Session session) {
        String email = session.getMetadata() != null ? session.getMetadata().get("candidateEmail") : null;

        if (email == null) {
            return;
        }

        User user = userRepository.findByEmail(email);

        // Stripe יכול לשלוח את אותו webhook כמה פעמים - הבדיקה הזו מונעת התראת "הופעל" כפולה
        if (user == null || user.isPremium()) {
            return;
        }

        user.setPremium(true);
        user.setPremiumSince(LocalDateTime.now());
        user.setStripeCustomerId(session.getCustomer());
        user.setStripeSubscriptionId(session.getSubscription());
        userRepository.save(user);

        notificationService.createNotification(
                email,
                "Premium Activated",
                "Your JobMatchAI Premium subscription is now active.",
                "PREMIUM_ACTIVATED"
        );
    }
}
