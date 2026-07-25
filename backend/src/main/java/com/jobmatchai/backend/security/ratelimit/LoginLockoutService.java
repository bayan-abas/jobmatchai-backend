package com.jobmatchai.backend.security.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

// נעילה קשיחה עד זמן מסוים ולא token bucket כמו RateLimiterService - כי צריך "חסום בדיוק לדקה מהניסיון ה-N",
// ו-token bucket לא יודע לבטא את זה טוב (ה-refill שלו נספר מהיצירה ולא מנקודת החסימה)
@Service
public class LoginLockoutService {

    private final Cache<String, LockoutState> lockouts = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofHours(1))
            .maximumSize(200_000)
            .build();

    // בודק אם המפתח (למשל אימייל או IP) נעול כרגע, וכמה זמן נשאר עד שהנעילה תיפתח
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

    // סופר ניסיון התחברות כושל, ואם הגיע למקסימום המותר - נועל את החשבון עד זמן קבוע מראש
    public void recordFailure(String key, long maxAttempts, Duration lockoutDuration) {
        lockouts.asMap().compute(key, (k, existing) -> {
            Instant now = Instant.now();
            LockoutState state = existing != null ? existing : LockoutState.EMPTY;

            // נעילה קודמת כבר פגה - מתחילים ספירה מחדש, אחרת המשתמש נשאר תמיד ניסיון אחד מנעילה הבאה
            if (state.lockedUntil() != null && !now.isBefore(state.lockedUntil())) {
                state = LockoutState.EMPTY;
            }

            int failureCount = state.failureCount() + 1;
            Instant lockedUntil = failureCount >= maxAttempts ? now.plus(lockoutDuration) : state.lockedUntil();
            return new LockoutState(failureCount, lockedUntil);
        });
    }

    // התחברות הצליחה - מאפס את מונה הכישלונות והנעילה של המפתח הזה
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
