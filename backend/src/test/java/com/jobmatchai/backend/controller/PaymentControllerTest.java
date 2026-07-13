package com.jobmatchai.backend.controller;

import com.jobmatchai.backend.model.User;
import com.jobmatchai.backend.repository.UserRepository;
import com.jobmatchai.backend.service.NotificationService;
import com.stripe.model.Invoice;
import com.stripe.model.Subscription;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Exercises the Stripe subscription lifecycle logic (findUserForSubscription/revokePremiumFor/
// restorePremiumFor/notifyPaymentFailed - see PaymentController's webhook() switch) directly
// against plain Stripe model objects built with setters, rather than through the full
// webhook(payload, signature) entry point. There's no Stripe test-mode account configured in
// this environment to fabricate a validly-signed payload against and verify end-to-end, so this
// is the most thorough verification available: it pins down the actual "does premium status end
// up correct" business logic, independent of Stripe's own signature verification (which is
// Stripe's SDK code, not ours, and isn't what a real regression here would break).
@ExtendWith(MockitoExtension.class)
class PaymentControllerTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private NotificationService notificationService;

    private PaymentController paymentController;

    @BeforeEach
    void setUp() {
        paymentController = new PaymentController();
        ReflectionTestUtils.setField(paymentController, "userRepository", userRepository);
        ReflectionTestUtils.setField(paymentController, "notificationService", notificationService);
    }

    private User premiumUser() {
        User user = new User();
        user.setEmail("candidate@example.com");
        user.setPremium(true);
        user.setStripeSubscriptionId("sub_123");
        user.setStripeCustomerId("cus_123");
        return user;
    }

    private User nonPremiumUser() {
        User user = new User();
        user.setEmail("candidate@example.com");
        user.setPremium(false);
        return user;
    }

    private Subscription subscription(String id, String status, String customer) {
        Subscription subscription = new Subscription();
        subscription.setId(id);
        subscription.setStatus(status);
        subscription.setCustomer(customer);
        return subscription;
    }

    // ---- findUserForSubscription ----

    @Test
    void findUserForSubscription_prefersStripeSubscriptionIdLookup() {
        User user = premiumUser();
        when(userRepository.findByStripeSubscriptionId("sub_123")).thenReturn(user);

        User found = paymentController.findUserForSubscription(subscription("sub_123", "active", "cus_123"));

        assertThat(found).isSameAs(user);
    }

    @Test
    void findUserForSubscription_fallsBackToMetadataEmail_whenSubscriptionIdUnmatched() {
        Subscription subscription = subscription("sub_new", "active", "cus_123");
        subscription.setMetadata(Map.of("candidateEmail", "candidate@example.com"));

        User user = premiumUser();
        when(userRepository.findByStripeSubscriptionId("sub_new")).thenReturn(null);
        when(userRepository.findByEmail("candidate@example.com")).thenReturn(user);

        User found = paymentController.findUserForSubscription(subscription);

        assertThat(found).isSameAs(user);
    }

    @Test
    void findUserForSubscription_fallsBackToStripeCustomerId_whenNoMetadata() {
        Subscription subscription = subscription("sub_new", "active", "cus_123");
        User user = premiumUser();
        when(userRepository.findByStripeSubscriptionId("sub_new")).thenReturn(null);
        when(userRepository.findByStripeCustomerId("cus_123")).thenReturn(user);

        User found = paymentController.findUserForSubscription(subscription);

        assertThat(found).isSameAs(user);
    }

    // ---- revokePremiumFor (customer.subscription.deleted / lost-access statuses) ----

    @Test
    void revokePremiumFor_flipsPremiumOffAndNotifies() {
        User user = premiumUser();
        when(userRepository.findByStripeSubscriptionId("sub_123")).thenReturn(user);

        paymentController.revokePremiumFor(subscription("sub_123", "canceled", "cus_123"), "ended");

        assertThat(user.isPremium()).isFalse();
        verify(userRepository).save(user);
        verify(notificationService).createNotification(
                eq("candidate@example.com"), anyString(), anyString(), eq("PREMIUM_CANCELLED"));
    }

    @Test
    void revokePremiumFor_isNoOp_whenUserAlreadyNonPremium() {
        // Covers the race where the user's own /cancel-subscription already flipped this before
        // Stripe's .deleted event for the same cancellation arrives - must not re-notify.
        User user = nonPremiumUser();
        when(userRepository.findByStripeSubscriptionId("sub_123")).thenReturn(user);

        paymentController.revokePremiumFor(subscription("sub_123", "canceled", "cus_123"), "ended");

        verify(userRepository, never()).save(any());
        verify(notificationService, never()).createNotification(any(), any(), any(), any());
    }

    @Test
    void revokePremiumFor_isNoOp_whenNoMatchingUser() {
        when(userRepository.findByStripeSubscriptionId("sub_unknown")).thenReturn(null);
        when(userRepository.findByStripeCustomerId("cus_unknown")).thenReturn(null);

        paymentController.revokePremiumFor(subscription("sub_unknown", "canceled", "cus_unknown"), "ended");

        verify(userRepository, never()).save(any());
        verify(notificationService, never()).createNotification(any(), any(), any(), any());
    }

    // ---- restorePremiumFor (customer.subscription.updated -> active, e.g. recovered from past_due) ----

    @Test
    void restorePremiumFor_flipsPremiumOnAndPersistsStripeIds() {
        User user = nonPremiumUser();
        when(userRepository.findByStripeSubscriptionId("sub_123")).thenReturn(user);

        paymentController.restorePremiumFor(subscription("sub_123", "active", "cus_123"));

        assertThat(user.isPremium()).isTrue();
        assertThat(user.getStripeSubscriptionId()).isEqualTo("sub_123");
        assertThat(user.getStripeCustomerId()).isEqualTo("cus_123");
        assertThat(user.getPremiumSince()).isNotNull();
        verify(userRepository).save(user);
        verify(notificationService).createNotification(
                eq("candidate@example.com"), anyString(), anyString(), eq("PREMIUM_ACTIVATED"));
    }

    @Test
    void restorePremiumFor_isNoOp_whenAlreadyPremium() {
        User user = premiumUser();
        when(userRepository.findByStripeSubscriptionId("sub_123")).thenReturn(user);

        paymentController.restorePremiumFor(subscription("sub_123", "active", "cus_123"));

        verify(userRepository, never()).save(any());
        verify(notificationService, never()).createNotification(any(), any(), any(), any());
    }

    // ---- notifyPaymentFailed (invoice.payment_failed - notify only, never revokes access itself) ----

    @Test
    void notifyPaymentFailed_notifiesViaSubscriptionOnInvoiceParent() {
        Invoice.Parent.SubscriptionDetails details = new Invoice.Parent.SubscriptionDetails();
        details.setSubscription("sub_123");
        Invoice.Parent parent = new Invoice.Parent();
        parent.setSubscriptionDetails(details);

        Invoice invoice = new Invoice();
        invoice.setParent(parent);
        invoice.setCustomer("cus_123");

        User user = premiumUser();
        when(userRepository.findByStripeSubscriptionId("sub_123")).thenReturn(user);

        paymentController.notifyPaymentFailed(invoice);

        verify(notificationService).createNotification(
                eq("candidate@example.com"), anyString(), anyString(), eq("PREMIUM_PAYMENT_FAILED"));
        // Never revokes access itself on a single failed payment - only subscription.updated/
        // deleted (once Stripe's own dunning retries are exhausted) do that.
        verify(userRepository, never()).save(any());
    }

    @Test
    void notifyPaymentFailed_fallsBackToCustomerId_whenNoParentSubscriptionDetails() {
        Invoice invoice = new Invoice();
        invoice.setCustomer("cus_123");

        User user = premiumUser();
        when(userRepository.findByStripeCustomerId("cus_123")).thenReturn(user);

        paymentController.notifyPaymentFailed(invoice);

        verify(notificationService).createNotification(
                eq("candidate@example.com"), anyString(), anyString(), eq("PREMIUM_PAYMENT_FAILED"));
    }

    @Test
    void notifyPaymentFailed_isNoOp_whenNoMatchingUser() {
        Invoice invoice = new Invoice();
        invoice.setCustomer("cus_unknown");
        when(userRepository.findByStripeCustomerId("cus_unknown")).thenReturn(null);

        paymentController.notifyPaymentFailed(invoice);

        verify(notificationService, never()).createNotification(any(), any(), any(), any());
    }
}
