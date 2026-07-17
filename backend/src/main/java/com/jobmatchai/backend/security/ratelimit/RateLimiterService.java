package com.jobmatchai.backend.security.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class RateLimiterService {

    // Keys are derived from client-supplied IPs/emails, so without eviction a sustained stream of
    // distinct values (e.g. a botnet rotating source IPs, or an attacker cycling target emails)
    // would grow this map without bound. One hour comfortably exceeds every configured window
    // (the longest is 15 minutes), so an entry idle that long has already fully refilled and holds
    // no state worth keeping; maximumSize is a hard backstop on top of that.
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofHours(1))
            .maximumSize(200_000)
            .build();

    // Consumes one token immediately, regardless of the caller's eventual success/failure -
    // for endpoints where every request (not just failures) should count against the limit.
    public RateLimitResult tryConsume(String key, RateLimitRule rule) {
        Bucket bucket = buckets.get(key, k -> newBucket(rule));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return RateLimitResult.allow();
        }
        return RateLimitResult.deny(ceilSeconds(probe.getNanosToWaitForRefill()));
    }

    // Non-consuming check: true if at least one token is currently available. Used to gate access
    // up front for endpoints that should only be penalized on failure (see recordFailure) - the
    // gate itself must not spend a token, or a run of successes would silently exhaust the budget
    // meant only for failed attempts.
    public boolean hasCapacity(String key, RateLimitRule rule) {
        Bucket bucket = buckets.get(key, k -> newBucket(rule));
        return bucket.getAvailableTokens() > 0;
    }

    // Consumes one token to record a failed attempt. Pair with hasCapacity for endpoints where
    // only failures (e.g. wrong verification code) should count against the limit.
    public RateLimitResult recordFailure(String key, RateLimitRule rule) {
        return tryConsume(key, rule);
    }

    private Bucket newBucket(RateLimitRule rule) {
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(rule.capacity()).refillGreedy(rule.capacity(), rule.window()))
                .build();
    }

    private static long ceilSeconds(long nanos) {
        if (nanos <= 0) {
            return 0;
        }
        return (nanos + 999_999_999L) / 1_000_000_000L;
    }
}
