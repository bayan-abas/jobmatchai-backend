package com.jobmatchai.backend.security.ratelimit;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

public final class RateLimitSupport {

    private RateLimitSupport() {}

    // בונה תשובת 429 אחידה לכל מקומות ה-rate limiting, כולל header של Retry-After אם ידוע כמה לחכות
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

    // מנרמל אימייל (trim + lowercase) כדי שאותו משתמש תמיד ייספר תחת אותו מפתח, בלי קשר לאותיות רישיות ורווחים
    public static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase();
    }
}
