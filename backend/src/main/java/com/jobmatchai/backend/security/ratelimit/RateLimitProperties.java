package com.jobmatchai.backend.security.ratelimit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

// Every knob here maps to a ratelimit.auth.* property (see application.properties) so limits can
// be tuned per environment without a code change or redeploy of new thresholds.
@Component
public class RateLimitProperties {

    @Value("${ratelimit.auth.enabled:true}")
    private boolean enabled;

    @Value("${ratelimit.auth.login.capacity:5}")
    private long loginCapacity;
    @Value("${ratelimit.auth.login.window-seconds:60}")
    private long loginWindowSeconds;

    @Value("${ratelimit.auth.send-verification-code.capacity:3}")
    private long sendVerificationCodeCapacity;
    @Value("${ratelimit.auth.send-verification-code.window-seconds:600}")
    private long sendVerificationCodeWindowSeconds;

    // Covers /api/auth/register, the endpoint that actually consumes the verification code.
    @Value("${ratelimit.auth.verify-code.capacity:5}")
    private long verifyCodeCapacity;
    @Value("${ratelimit.auth.verify-code.window-seconds:600}")
    private long verifyCodeWindowSeconds;

    @Value("${ratelimit.auth.forgot-password.capacity:3}")
    private long forgotPasswordCapacity;
    @Value("${ratelimit.auth.forgot-password.window-seconds:900}")
    private long forgotPasswordWindowSeconds;

    @Value("${ratelimit.auth.reset-password.capacity:5}")
    private long resetPasswordCapacity;
    @Value("${ratelimit.auth.reset-password.window-seconds:900}")
    private long resetPasswordWindowSeconds;

    public boolean isEnabled() {
        return enabled;
    }

    public RateLimitRule login() {
        return new RateLimitRule(loginCapacity, Duration.ofSeconds(loginWindowSeconds));
    }

    public RateLimitRule sendVerificationCode() {
        return new RateLimitRule(sendVerificationCodeCapacity, Duration.ofSeconds(sendVerificationCodeWindowSeconds));
    }

    public RateLimitRule verifyCode() {
        return new RateLimitRule(verifyCodeCapacity, Duration.ofSeconds(verifyCodeWindowSeconds));
    }

    public RateLimitRule forgotPassword() {
        return new RateLimitRule(forgotPasswordCapacity, Duration.ofSeconds(forgotPasswordWindowSeconds));
    }

    public RateLimitRule resetPassword() {
        return new RateLimitRule(resetPasswordCapacity, Duration.ofSeconds(resetPasswordWindowSeconds));
    }
}
