package com.jobmatchai.backend.security.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterServiceTest {

    private final RateLimiterService rateLimiterService = new RateLimiterService();

    private static final RateLimitRule RULE = new RateLimitRule(3, Duration.ofMinutes(1));

    @Test
    void tryConsume_allowsUpToCapacityRequests() {
        assertThat(rateLimiterService.tryConsume("key-a", RULE).allowed()).isTrue();
        assertThat(rateLimiterService.tryConsume("key-a", RULE).allowed()).isTrue();
        assertThat(rateLimiterService.tryConsume("key-a", RULE).allowed()).isTrue();
    }

    @Test
    void tryConsume_blocksOnceCapacityExhausted_andReportsRetryAfter() {
        rateLimiterService.tryConsume("key-b", RULE);
        rateLimiterService.tryConsume("key-b", RULE);
        rateLimiterService.tryConsume("key-b", RULE);

        RateLimitResult result = rateLimiterService.tryConsume("key-b", RULE);

        assertThat(result.allowed()).isFalse();
        assertThat(result.retryAfterSeconds()).isGreaterThan(0);
    }

    @Test
    void tryConsume_tracksDifferentKeysIndependently() {
        rateLimiterService.tryConsume("key-c", RULE);
        rateLimiterService.tryConsume("key-c", RULE);
        rateLimiterService.tryConsume("key-c", RULE);
        assertThat(rateLimiterService.tryConsume("key-c", RULE).allowed()).isFalse();

        // A different key (e.g. a different IP or a different account's email) must not be
        // affected by key-c's exhausted budget.
        assertThat(rateLimiterService.tryConsume("key-d", RULE).allowed()).isTrue();
    }

    @Test
    void hasCapacity_isANonConsumingPeek() {
        for (int i = 0; i < 10; i++) {
            assertThat(rateLimiterService.hasCapacity("key-e", RULE)).isTrue();
        }

        // Since hasCapacity never consumed anything, the full capacity is still available.
        assertThat(rateLimiterService.tryConsume("key-e", RULE).allowed()).isTrue();
        assertThat(rateLimiterService.tryConsume("key-e", RULE).allowed()).isTrue();
        assertThat(rateLimiterService.tryConsume("key-e", RULE).allowed()).isTrue();
        assertThat(rateLimiterService.tryConsume("key-e", RULE).allowed()).isFalse();
    }

    @Test
    void recordFailure_consumesTokens_soHasCapacityEventuallyReturnsFalse() {
        assertThat(rateLimiterService.hasCapacity("key-f", RULE)).isTrue();

        rateLimiterService.recordFailure("key-f", RULE);
        rateLimiterService.recordFailure("key-f", RULE);
        rateLimiterService.recordFailure("key-f", RULE);

        assertThat(rateLimiterService.hasCapacity("key-f", RULE)).isFalse();
    }
}
