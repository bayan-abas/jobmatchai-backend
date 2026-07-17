package com.jobmatchai.backend.security.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

// Fixed-lockout throttle used only for login (see AuthController#login, UserController#loginUser):
// N failed attempts, then the key is completely blocked until a fixed instant, rather than
// RateLimiterService's token-bucket behavior (which lets attempts trickle back gradually). A
// token bucket can't cleanly express "blocked for exactly 1 minute measured from the Nth failure"
// - its refill is anchored to bucket creation/last-refill, not to the failure that tipped it over.
@Service
public class LoginLockoutService {

    // Same bounded/self-expiring shape as RateLimiterService's bucket cache, and for the same
    // reason: keys come from client-supplied IPs/emails, so eviction is required to keep memory
    // bounded. One hour comfortably exceeds any realistic lockout duration.
    private final Cache<String, LockoutState> lockouts = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofHours(1))
            .maximumSize(200_000)
            .build();

    // Read-only peek - never mutates state, so checking never itself extends or resets a lockout.
    public LockoutStatus check(String key) {
        LockoutState state = lockouts.getIfPresent(key);
        if (state == null || state.lockedUntil() == null) {
            return LockoutStatus.notLockedOut();
        }

        Instant now = Instant.now();
        if (now.isBefore(state.lockedUntil())) {
            return LockoutStatus.lockedOut(ceilSeconds(Duration.between(now, state.lockedUntil())));
        }
        return LockoutStatus.notLockedOut();
    }

    // Registers one failed attempt. Once the failure count reaches maxAttempts, the key is locked
    // for lockoutDuration starting from this exact call - further failures while already locked
    // out never reach here (the controller blocks at check() first), so the lockout instant never
    // gets pushed further into the future by repeated attempts during the lockout itself.
    public void recordFailure(String key, long maxAttempts, Duration lockoutDuration) {
        lockouts.asMap().compute(key, (k, existing) -> {
            Instant now = Instant.now();
            LockoutState state = existing != null ? existing : LockoutState.EMPTY;

            // A previous lockout already ran out - start counting fresh rather than treating this
            // as failure number (oldCount + 1), so the account isn't left permanently one strike
            // away from being locked out again forever.
            if (state.lockedUntil() != null && !now.isBefore(state.lockedUntil())) {
                state = LockoutState.EMPTY;
            }

            int failureCount = state.failureCount() + 1;
            Instant lockedUntil = failureCount >= maxAttempts ? now.plus(lockoutDuration) : state.lockedUntil();
            return new LockoutState(failureCount, lockedUntil);
        });
    }

    // A successful attempt clears the failure count/lockout entirely - proof of the real
    // credentials is a stronger signal than any residual failure count is worth keeping.
    public void recordSuccess(String key) {
        lockouts.invalidate(key);
    }

    private static long ceilSeconds(Duration duration) {
        long nanos = duration.toNanos();
        if (nanos <= 0) {
            return 0;
        }
        return (nanos + 999_999_999L) / 1_000_000_000L;
    }

    private record LockoutState(int failureCount, Instant lockedUntil) {
        static final LockoutState EMPTY = new LockoutState(0, null);
    }
}
