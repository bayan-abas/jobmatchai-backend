package com.jobmatchai.backend.security.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class RateLimiterService {

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofHours(1))
            .maximumSize(200_000)
            .build();

    // מנסה "לצרוך" בקשה אחת מהמכסה של המפתח לפי הכלל שהוגדר, ומחזיר אם מותר להמשיך או כמה זמן לחכות
    public RateLimitResult tryConsume(String key, RateLimitRule rule) {
        Bucket bucket = buckets.get(key, k -> newBucket(rule));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return RateLimitResult.allow();
        }
        return RateLimitResult.deny(ceilSeconds(probe.getNanosToWaitForRefill()));
    }

    // לא צורך טוקן - רק בדיקה, כדי שסבב בקשות מוצלחות לא ירוקן את המכסה שנועדה רק לכישלונות
    public boolean hasCapacity(String key, RateLimitRule rule) {
        Bucket bucket = buckets.get(key, k -> newBucket(rule));
        return bucket.getAvailableTokens() > 0;
    }

    // רושם כישלון (למשל ניסיון התחברות שנכשל) על ידי צריכת טוקן מהמכסה הייעודית לכישלונות
    public RateLimitResult recordFailure(String key, RateLimitRule rule) {
        return tryConsume(key, rule);
    }

    // יוצר bucket חדש שמתמלא מחדש בקצב שהכלל מגדיר (capacity בקשות לחלון זמן נתון)
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
