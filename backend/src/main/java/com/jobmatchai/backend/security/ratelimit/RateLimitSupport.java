package com.jobmatchai.backend.security.ratelimit;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

// Shared by every controller that gates an endpoint with RateLimiterService (currently
// AuthController and UserController, which expose two independent routes - /api/auth/* and
// /api/users/* - to the same login/registration actions) so the 429 response shape and the
// email-normalization used to build rate-limit keys can't drift between them.
public final class RateLimitSupport {

    private RateLimitSupport() {}

    // Generic message + optional Retry-After, deliberately vague so a client can't distinguish
    // "too many requests from your IP" from "too many requests for this email" - either would
    // otherwise leak information about which dimension is being throttled.
    public static ResponseEntity<Map<String, Object>> tooManyRequests(long retryAfterSeconds) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", "Too many requests. Please try again later.");

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS);
        if (retryAfterSeconds > 0) {
            builder = builder.header("Retry-After", String.valueOf(retryAfterSeconds));
        }
        return builder.body(response);
    }

    public static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase();
    }
}
