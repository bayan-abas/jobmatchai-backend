package com.jobmatchai.backend.security.ratelimit;

import java.time.Duration;

public record RateLimitRule(long capacity, Duration window) {
}
