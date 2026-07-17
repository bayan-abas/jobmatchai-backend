package com.jobmatchai.backend.security.ratelimit;

public record RateLimitResult(boolean allowed, long retryAfterSeconds) {

    public static RateLimitResult allow() {
        return new RateLimitResult(true, 0);
    }

    public static RateLimitResult deny(long retryAfterSeconds) {
        return new RateLimitResult(false, retryAfterSeconds);
    }
}
