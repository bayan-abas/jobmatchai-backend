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

    // Subscription statuses that mean "this subscription no longer grants access" - everything
    // else (active, trialing, past_due - Stripe is still retrying) leaves premium as-is.
    private static final Set<String> LOST_ACCESS_STATUSES = Set.of("canceled", "unpaid", "incomplete_expired");

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
                    // The checkout Session's own metadata only helps activatePremium (which reads
                    // the completed Session directly) - every OTHER lifecycle webhook
                    // (subscription.updated/deleted, invoice.payment_failed) hands us the
                    // Subscription/Invoice object instead, which never carries the Session's
                    // metadata unless stamped here too. Without this, those events could only be
                    // matched back to a user via stripeSubscriptionId/stripeCustomerId, which
                    // aren't persisted until AFTER activatePremium's first save - a subscription
                    // event arriving before that (unlikely but possible race) would be unmatchable.
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

    @GetMapping("/confirm")
    @PreAuthorize("hasRole('CANDIDATE')")
    public ResponseEntity<?> confirm(@RequestParam("session_id") String sessionId, Authentication authentication) {
        if (stripeSecretKey == null || stripeSecretKey.isBlank()) {
            return ResponseEntity.status(503).body(Map.of("success", false, "message", "Payments are not configured."));
        }

        try {
            Session session = Session.retrieve(sessionId);

            // A session_id isn't a secret the way a token is - it can end up in browser
            // history, a shared success-URL, or a referrer header. Without this check,
            // anyone logged in could activate premium on someone ELSE's account just by
            // calling this endpoint with a session_id that isn't theirs (Stripe still
            // requires the session to be genuinely paid, but ties the activation to
            // whatever email was stashed in its metadata, not to the caller).
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

            // Fires when a subscription is fully terminated - whether the user's own
            // cancellation took effect at period end, or Stripe gave up retrying a failed
            // payment after its dunning schedule ran out. This is the definitive "access is
            // over" signal; cancel-subscription (the user-initiated path above) covers the
            // immediate case, this covers every OTHER way a subscription can end.
            case "customer.subscription.deleted" -> event.getDataObjectDeserializer().getObject().ifPresent(obj -> {
                if (obj instanceof Subscription subscription) {
                    revokePremiumFor(subscription, "Your JobMatchAI Premium subscription has ended.");
                }
            });

            // Fires on every status transition (active <-> past_due <-> unpaid <-> canceled,
            // and re-activation after a manual retry succeeds). Handles both directions: losing
            // access when a renewal ultimately fails without waiting for the separate .deleted
            // event, and restoring it if a past_due subscription recovers - the checkout flow
            // only ever runs once, so recovery has no other path back to premium=true.
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

            // A renewal charge failed. Stripe retries automatically per its dunning schedule
            // (surfacing as subscription.updated -> past_due, then eventually .deleted if every
            // retry fails) - access isn't pulled on the first failure, but the candidate needs to
            // know now, while they can still fix their payment method before losing Premium.
            case "invoice.payment_failed" -> event.getDataObjectDeserializer().getObject().ifPresent(obj -> {
                if (obj instanceof Invoice invoice) {
                    notifyPaymentFailed(invoice);
                }
            });

            default -> log.debug("Ignoring unhandled Stripe event type: {}", event.getType());
        }

        return ResponseEntity.ok(Map.of("received", true));
    }

    // Package-private (not private) so PaymentControllerTest can exercise this lifecycle logic
    // directly with plain Stripe model objects, instead of having to fabricate a validly-signed
    // webhook payload and rely on Stripe's own JSON deserializer - there's no Stripe test-mode
    // account available in this environment to verify the real webhook end-to-end against.
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

    void revokePremiumFor(Subscription subscription, String message) {
        User user = findUserForSubscription(subscription);
        if (user == null || !user.isPremium()) {
            // Already non-premium (e.g. the user's own /cancel-subscription already flipped
            // this and Stripe's .deleted event for the same cancellation arrived after) -
            // nothing to do, and re-notifying would be misleading duplicate noise.
            return;
        }

        user.setPremium(false);
        userRepository.save(user);

        notificationService.createNotification(
                user.getEmail(), "Premium Ended", message, "PREMIUM_CANCELLED");
    }

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

    // --- Demo/development mode only -----------------------------------------------------
    // Bypasses Stripe entirely: no card, no checkout session, no webhook. Activates/cancels
    // Premium directly on the account. The frontend calls these instead of
    // create-checkout-session / cancel-subscription for now; once real Stripe payments are
    // wired up, point PaymentPage.tsx back at those endpoints and these can stay unused.
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
