package com.jobmatchai.backend.security.ratelimit;

public record LockoutStatus(boolean lockedOut, long retryAfterSeconds) {

    public static LockoutStatus lockedOut(long retryAfterSeconds) {
        return new LockoutStatus(true, retryAfterSeconds);
    }

    public static LockoutStatus notLockedOut() {
        return new LockoutStatus(false, 0);
    }
}
