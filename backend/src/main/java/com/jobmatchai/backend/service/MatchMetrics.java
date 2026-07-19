package com.jobmatchai.backend.service;

import com.jobmatchai.backend.repository.MatchScoreJobRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

// One place every match-scoring metric is recorded, exposed at /actuator/metrics (and
// /actuator/prometheus if that export is enabled) without any external monitoring service - real
// production observability for queue depth, cache-hit rate, OpenAI call volume/latency, and DB
// query latency, all requested explicitly: "Add monitoring for queue size, processing time,
// cache-hit rate, OpenAI calls, database latency, failures, and cost." Cost itself isn't metered
// directly (OpenAI doesn't return a per-call price), but openai.calls's count by model is exactly
// the input a cost estimate is built from (multiply by that model's published per-call price).
@Component
public class MatchMetrics {

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private MatchScoreJobRepository matchScoreJobRepository;

    public void recordCacheHit(String kind) {
        Counter.builder("matchscore.cache.result")
                .tag("kind", kind)
                .tag("outcome", "hit")
                .description("Match score requests served from the cvFingerprint/jobFingerprint cache vs. needing computation")
                .register(meterRegistry)
                .increment();
    }

    public void recordCacheMiss(String kind) {
        Counter.builder("matchscore.cache.result")
                .tag("kind", kind)
                .tag("outcome", "miss")
                .description("Match score requests served from the cvFingerprint/jobFingerprint cache vs. needing computation")
                .register(meterRegistry)
                .increment();
    }

    public void recordInsufficientData() {
        Counter.builder("matchscore.insufficient_data")
                .description("Jobs classified as too thin to score - deterministic gate, zero OpenAI spend")
                .register(meterRegistry)
                .increment();
    }

    public void recordOpenAiCall(String model, String outcome, long elapsedMs) {
        Timer.builder("openai.calls")
                .tag("model", model)
                .tag("outcome", outcome)
                .description("Every OpenAI Responses API call this app makes, by model and outcome (success/error)")
                .register(meterRegistry)
                .record(elapsedMs, TimeUnit.MILLISECONDS);
    }

    public void recordDbQuery(String operation, long elapsedMs) {
        Timer.builder("db.query")
                .tag("operation", operation)
                .description("Latency of the match-scoring pipeline's own DB reads/writes")
                .register(meterRegistry)
                .record(elapsedMs, TimeUnit.MILLISECONDS);
    }

    public void recordQueueJobEnqueued() {
        Counter.builder("matchscore.queue.enqueued")
                .description("Jobs added to the persistent match-score queue")
                .register(meterRegistry)
                .increment();
    }

    public void recordQueueJobProcessed(String outcome, long elapsedMs) {
        Timer.builder("matchscore.queue.processed")
                .tag("outcome", outcome)
                .description("Time a worker spent processing one queued match-score job, by outcome (success/retry/failed)")
                .register(meterRegistry)
                .record(elapsedMs, TimeUnit.MILLISECONDS);
    }

    // Registered once at startup as a live gauge (re-queried on every scrape) rather than pushed
    // on every enqueue/dequeue - "how many jobs are waiting right now" is exactly the kind of
    // number that's cheap to compute on demand and easy to get subtly wrong trying to maintain
    // incrementally.
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
