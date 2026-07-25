package com.jobmatchai.backend.service;

import com.jobmatchai.backend.repository.MatchScoreJobRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class MatchMetrics {

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private MatchScoreJobRepository matchScoreJobRepository;

    // סופר כמה בקשות ציון קיבלו תשובה מהמטמון (לפי fingerprint) בלי לקרוא ל-AI מחדש
    public void recordCacheHit(String kind) {
        Counter.builder("matchscore.cache.result")
                .tag("kind", kind)
                .tag("outcome", "hit")
                .description("Match score requests served from the cvFingerprint/jobFingerprint cache vs. needing computation")
                .register(meterRegistry)
                .increment();
    }

    // סופר כמה בקשות ציון לא נמצאו במטמון וחייבו חישוב מחדש
    public void recordCacheMiss(String kind) {
        Counter.builder("matchscore.cache.result")
                .tag("kind", kind)
                .tag("outcome", "miss")
                .description("Match score requests served from the cvFingerprint/jobFingerprint cache vs. needing computation")
                .register(meterRegistry)
                .increment();
    }

    // סופר משרות שנפסלו לפני קריאה ל-AI כי אין מספיק מידע לחשב עליהן ציון
    public void recordInsufficientData() {
        Counter.builder("matchscore.insufficient_data")
                .description("Jobs classified as too thin to score - deterministic gate, zero OpenAI spend")
                .register(meterRegistry)
                .increment();
    }

    // סופר משרות שנפסלו לפני קריאה ל-AI כי המקצוע לא תואם בכלל לפי טבלת ההתאמות
    public void recordProfessionIncompatible() {
        Counter.builder("matchscore.profession_incompatible")
                .description("Jobs rejected by the profession-taxonomy compatibility gate before any AI call - "
                        + "e.g. a Software Engineer CV against a QA Engineer posting")
                .register(meterRegistry)
                .increment();
    }

    // מודד זמן וכשל/הצלחה לכל קריאה בפועל ל-OpenAI
    public void recordOpenAiCall(String model, String outcome, long elapsedMs) {
        Timer.builder("openai.calls")
                .tag("model", model)
                .tag("outcome", outcome)
                .description("Every OpenAI Responses API call this app makes, by model and outcome (success/error)")
                .register(meterRegistry)
                .record(elapsedMs, TimeUnit.MILLISECONDS);
    }

    // מודד כמה זמן לוקחות קריאות DB של תהליך ההתאמה עצמו
    public void recordDbQuery(String operation, long elapsedMs) {
        Timer.builder("db.query")
                .tag("operation", operation)
                .description("Latency of the match-scoring pipeline's own DB reads/writes")
                .register(meterRegistry)
                .record(elapsedMs, TimeUnit.MILLISECONDS);
    }

    // סופר כל job שנכנס לתור החישוב
    public void recordQueueJobEnqueued() {
        Counter.builder("matchscore.queue.enqueued")
                .description("Jobs added to the persistent match-score queue")
                .register(meterRegistry)
                .increment();
    }

    // מודד כמה זמן לקח ל-worker לעבד job אחד מהתור ובאיזו תוצאה (הצלחה/כישלון/וכו')
    public void recordQueueJobProcessed(String outcome, long elapsedMs) {
        Timer.builder("matchscore.queue.processed")
                .tag("outcome", outcome)
                .description("Time a worker spent processing one queued match-score job, by outcome (success/retry/failed)")
                .register(meterRegistry)
                .record(elapsedMs, TimeUnit.MILLISECONDS);
    }

    // רושם gauge שמראה כמה שורות יש כרגע בכל סטטוס בתור (pending/in_progress/failed) - לניטור אורך התור בזמן אמת
    @jakarta.annotation.PostConstruct
    public void registerQueueDepthGauge() {
        meterRegistry.gauge("matchscore.queue.depth", java.util.List.of(io.micrometer.core.instrument.Tag.of("status", "pending")),
                matchScoreJobRepository, repo -> repo.countByStatus("PENDING"));
        meterRegistry.gauge("matchscore.queue.depth", java.util.List.of(io.micrometer.core.instrument.Tag.of("status", "in_progress")),
                matchScoreJobRepository, repo -> repo.countByStatus("IN_PROGRESS"));
        meterRegistry.gauge("matchscore.queue.depth", java.util.List.of(io.micrometer.core.instrument.Tag.of("status", "failed")),
                matchScoreJobRepository, repo -> repo.countByStatus("FAILED"));
    }
}
