package com.jobmatchai.backend.security.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class LoginLockoutServiceTest {

    private final LoginLockoutService loginLockoutService = new LoginLockoutService();

    private static final long MAX_ATTEMPTS = 5;
    private static final Duration LOCKOUT = Duration.ofSeconds(60);

    @Test
    void check_notLockedOut_forUnknownKey() {
        assertThat(loginLockoutService.check("key-a").lockedOut()).isFalse();
    }

    @Test
    void recordFailure_belowThreshold_doesNotLockOut() {
        for (int i = 0; i < MAX_ATTEMPTS - 1; i++) {
            loginLockoutService.recordFailure("key-b", MAX_ATTEMPTS, LOCKOUT);
        }

        assertThat(loginLockoutService.check("key-b").lockedOut()).isFalse();
    }

    @Test
    void recordFailure_atThreshold_locksOutWithRetryAfterUpToFullDuration() {
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            loginLockoutService.recordFailure("key-c", MAX_ATTEMPTS, LOCKOUT);
        }

        LockoutStatus status = loginLockoutService.check("key-c");

        assertThat(status.lockedOut()).isTrue();
        assertThat(status.retryAfterSeconds()).isGreaterThan(0).isLessThanOrEqualTo(60);
    }

    @Test
    void check_isANonMutatingPeek_andDoesNotExtendOrTriggerLockout() {
        for (int i = 0; i < 20; i++) {
            assertThat(loginLockoutService.check("key-d").lockedOut()).isFalse();
        }

        // Peeking 20 times must not itself have registered any failures.
        for (int i = 0; i < MAX_ATTEMPTS - 1; i++) {
            loginLockoutService.recordFailure("key-d", MAX_ATTEMPTS, LOCKOUT);
        }
        assertThat(loginLockoutService.check("key-d").lockedOut()).isFalse();
    }

    @Test
    void differentKeys_areIndependentlyLimited() {
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            loginLockoutService.recordFailure("key-e", MAX_ATTEMPTS, LOCKOUT);
        }

        assertThat(loginLockoutService.check("key-e").lockedOut()).isTrue();
        assertThat(loginLockoutService.check("key-f").lockedOut()).isFalse();
    }

    @Test
    void recordSuccess_clearsAccumulatedFailures() {
        for (int i = 0; i < MAX_ATTEMPTS - 1; i++) {
            loginLockoutService.recordFailure("key-g", MAX_ATTEMPTS, LOCKOUT);
        }
        loginLockoutService.recordSuccess("key-g");

        // A fresh set of MAX_ATTEMPTS failures should be required again - the single failure
        // right after the reset must not be treated as failure number MAX_ATTEMPTS.
        loginLockoutService.recordFailure("key-g", MAX_ATTEMPTS, LOCKOUT);

        assertThat(loginLockoutService.check("key-g").lockedOut()).isFalse();
    }

    @Test
    void recordFailure_afterLockoutExpires_startsFreshCount() throws InterruptedException {
        Duration shortLockout = Duration.ofMillis(50);
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            loginLockoutService.recordFailure("key-h", MAX_ATTEMPTS, shortLockout);
        }
        assertThat(loginLockoutService.check("key-h").lockedOut()).isTrue();

        Thread.sleep(100);
        assertThat(loginLockoutService.check("key-h").lockedOut()).isFalse();

        // A single failure right after expiry must not immediately re-lock - it's failure #1 of
        // a fresh count, not #(MAX_ATTEMPTS+1) of the old one.
        loginLockoutService.recordFailure("key-h", MAX_ATTEMPTS, shortLockout);
        assertThat(loginLockoutService.check("key-h").lockedOut()).isFalse();
    }
}
