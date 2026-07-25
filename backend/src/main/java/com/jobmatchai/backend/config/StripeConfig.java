package com.jobmatchai.backend.config;

import com.stripe.Stripe;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StripeConfig {

    @Value("${stripe.secret-key}")
    private String secretKey;

    // מגדיר את מפתח ה-API הגלובלי של Stripe SDK מתוך ההגדרות, כדי שכל קריאה ל-Stripe בהמשך תעבוד בלי צורך להעביר אותו כל פעם
    @PostConstruct
    public void init() {
        if (secretKey != null && !secretKey.isBlank()) {
            Stripe.apiKey = secretKey;
        }
    }
}
