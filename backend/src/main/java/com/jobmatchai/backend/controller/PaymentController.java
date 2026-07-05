package com.jobmatchai.backend.controller;

import com.jobmatchai.backend.model.User;
import com.jobmatchai.backend.repository.UserRepository;
import com.jobmatchai.backend.service.NotificationService;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

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
                    .addLineItem(lineItem)
                    .build();

            Session session = Session.create(params);

            return ResponseEntity.ok(Map.of("url", session.getUrl()));

        } catch (StripeException e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "Failed to start checkout: " + e.getMessage()
            ));
        }
    }

    @GetMapping("/confirm")
    @PreAuthorize("hasRole('CANDIDATE')")
    public ResponseEntity<?> confirm(@RequestParam("session_id") String sessionId, Authentication authentication) {
        if (stripeSecretKey == null || stripeSecretKey.isBlank()) {
            return ResponseEntity.status(503).body(Map.of("success", false, "message", "Payments are not configured."));
        }

        try {
            Session session = Session.retrieve(sessionId);

            if (!"paid".equals(session.getPaymentStatus())) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Payment not completed."));
            }

            activatePremium(session);

            return ResponseEntity.ok(Map.of("success", true, "message", "Premium activated."));

        } catch (StripeException e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "Failed to confirm payment: " + e.getMessage()
            ));
        }
    }

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

        if ("checkout.session.completed".equals(event.getType())) {
            event.getDataObjectDeserializer().getObject().ifPresent(obj -> {
                if (obj instanceof Session session) {
                    String candidateEmail = session.getMetadata() != null
                            ? session.getMetadata().get("candidateEmail")
                            : null;

                    if (candidateEmail != null && "paid".equals(session.getPaymentStatus())) {
                        activatePremium(session);
                    }
                }
            });
        }

        return ResponseEntity.ok(Map.of("received", true));
    }

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
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "Failed to cancel subscription: " + e.getMessage()
            ));
        }
    }

    private void activatePremium(Session session) {
        String email = session.getMetadata() != null ? session.getMetadata().get("candidateEmail") : null;

        if (email == null) {
            return;
        }

        User user = userRepository.findByEmail(email);

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
