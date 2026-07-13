package com.jobmatchai.backend.repository;

import com.jobmatchai.backend.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
    User findByEmail(String email);

    // Used by PaymentController's webhook handlers for subscription-level Stripe events
    // (customer.subscription.updated/deleted, invoice.payment_failed) - those events carry a
    // subscription/customer id, not the candidate's email, so activatePremium's metadata-based
    // lookup (which only works for checkout.session.completed) doesn't apply.
    User findByStripeSubscriptionId(String stripeSubscriptionId);

    User findByStripeCustomerId(String stripeCustomerId);
}