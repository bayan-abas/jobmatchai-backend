package com.jobmatchai.backend.service;

import com.fasterxml.jackson.databind.JsonNode;

import com.jobmatchai.backend.model.CVAnalysis;
import com.jobmatchai.backend.model.Job;
import com.jobmatchai.backend.model.JobMatchScore;
import com.jobmatchai.backend.model.MatchScoreJob;
import com.jobmatchai.backend.repository.CVAnalysisRepository;
import com.jobmatchai.backend.repository.JobMatchScoreRepository;
import com.jobmatchai.backend.repository.MatchScoreJobRepository;

import io.github.bucket4j.Bucket;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

// Drains the persistent match-score queue (see MatchScoreJob / MatchScoreQueueService) on a fixed
// poll interval. This is what "background jobs, not synchronous inline computation" actually
// means here: every OpenAI call this app makes for match scoring now happens on THIS worker's own
// bounded pool, not on the thread of whatever HTTP request happened to trigger the cache miss - a
// candidate closing their browser mid-computation doesn't cancel anything, and a burst of
// candidates opening their dashboards at once fans into the SAME shared, rate-limited pool instead
// of each spawning its own uncapped burst of concurrent AI calls.
//
// Horizontally scalable without any external broker: claimBatch's SELECT ... FOR UPDATE SKIP
// LOCKED (see MatchScoreJobRepository#lockNextBatch) means running this exact same @Scheduled
// poller on multiple app instances at once is already safe - two instances' workers racing for
// the same row can never both claim it. Postgres's own row locking is the only coordination this
// needs.
@Component
public class MatchScoreQueueWorker {

    private static final Logger log = LoggerFactory.getLogger(MatchScoreQueueWorker.class);

    @Autowired
    private MatchScoreJobRepository matchScoreJobRepository;

    @Autowired
    private JobMatchScoreRepository jobMatchScoreRepository;

    @Autowired
    private CVAnalysisRepository cvAnalysisRepository;

    @Autowired
    private JobMatchService jobMatchService;

    @Autowired
    private MatchScoreQueueService matchScoreQueueService;

    @Autowired
    private MatchMetrics matchMetrics;

    // How many queue rows one poll tick claims at once - deliberately small relative to Supabase's
    // 15-connection pool cap (this claim itself is one short transaction/connection, released
    // immediately; processing afterward doesn't hold a DB connection open for the AI call).
    @Value("${matching.queue.batch-size:10}")
    private int batchSize;

    // How many AI calls this worker runs at once, across ALL claimed jobs - the actual protection
    // against a batch of newly-claimed jobs firing an uncontrolled burst at OpenAI. Passed as the
    // Semaphore JobMatchService#computeChunkWithRetry already acquires/releases around every call.
    @Value("${matching.queue.worker-concurrency:5}")
    private int workerConcurrency;

    // Independent of the concurrency cap above: a hard calls-PER-SECOND ceiling, so even a wide-
    // open concurrency setting can never spike faster than this - the actual "protect the OpenAI
    // API from traffic spikes" control, decoupled from "protect the DB/pool" (workerConcurrency).
    @Value("${matching.openai.rate-limit-per-second:3}")
    private int openAiRateLimitPerSecond;

    @Value("${matching.queue.max-attempts:4}")
    private int maxAttempts;

    @Value("${matching.queue.stale-in-progress-minutes:5}")
    private int staleInProgressMinutes;

    private Semaphore aiConcurrencyLimiter;
    // Bounds how many processOne() calls actually RUN (not just how many are dispatched) at
    // once - found via production incident: claimBatch() marks a whole batch IN_PROGRESS and
    // dispatchExecutor (virtual-thread-per-task, uncapped) starts a task for every row
    // immediately, but aiConcurrencyLimiter only ever gated the OpenAI call INSIDE
    // computeChunkWithRetry - every row's surrounding DB work (the CVAnalysis lookup, the
    // "already fresh" JobMatchScore check, the final save) ran fully unbounded outside that gate.
    // With poll-interval-ms=1000 continuing to claim new batches every second regardless of how
    // many previous rows were still mid-processing, concurrent DB connection demand from this
    // worker alone scaled with backlog size, not with worker-concurrency - exhausting the Hikari
    // pool (observed: "Connection is not available... waiting=30" against a pool of just a few
    // connections) and making every affected row fail with CannotCreateTransactionException /
    // DataAccessResourceFailureException, retry, fail again, and eventually land FAILED - which
    // is what made already-cached-forever candidates see their match scores "recompute" on
    // every visit: those jobs were never actually finishing, not being recalculated from a
    // working cache. Acquiring this BEFORE any DB work starts (not just before the AI call) is
    // what actually caps concurrent connection usage to workerConcurrency, matching what the
    // comment on dispatchExecutor below always assumed was already true.
    private Semaphore rowConcurrencyLimiter;
    private Bucket rateLimitBucket;
    private ExecutorService dispatchExecutor;

    @PostConstruct
    void init() {
        aiConcurrencyLimiter = new Semaphore(Math.max(1, workerConcurrency));
        rowConcurrencyLimiter = new Semaphore(Math.max(1, workerConcurrency));
        rateLimitBucket = Bucket.builder()
                .addLimit(limit -> limit.capacity(Math.max(1, openAiRateLimitPerSecond))
                        .refillGreedy(Math.max(1, openAiRateLimitPerSecond), java.time.Duration.ofSeconds(1)))
                .build();
        // Cheap to dispatch generously on virtual threads - a claimed-but-not-yet-processed row
        // just blocks on rowConcurrencyLimiter in memory (no DB connection held) until its turn.
        dispatchExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Scheduled(fixedDelayString = "${matching.queue.poll-interval-ms:1000}")
    public void pollAndProcess() {
        List<MatchScoreJob> batch;
        try {
            batch = claimBatch();
        } catch (Exception e) {
            log.error("Failed to claim a batch from the match-score queue", e);
            return;
        }

        if (batch.isEmpty()) {
            return;
        }

        log.info("match-score-queue claimed batch size={}", batch.size());
        for (MatchScoreJob row : batch) {
            dispatchExecutor.execute(() -> {
                try {
                    rowConcurrencyLimiter.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    processOne(row);
                } finally {
                    rowConcurrencyLimiter.release();
                }
            });
        }
    }

    @Transactional
    List<MatchScoreJob> claimBatch() {
        List<MatchScoreJob> rows = matchScoreJobRepository.lockNextBatch(LocalDateTime.now(), batchSize);
        for (MatchScoreJob row : rows) {
            row.setStatus("IN_PROGRESS");
        }
        return matchScoreJobRepository.saveAll(rows);
    }

    private void processOne(MatchScoreJob row) {
        long start = System.nanoTime();
        try {
            rateLimitBucket.asBlocking().consume(1);

            CVAnalysis analysis = cvAnalysisRepository.findByUserEmail(row.getCandidateEmail()).orElse(null);
            if (analysis == null) {
                // The candidate's CV was deleted since this was enqueued - nothing left to score.
                matchScoreJobRepository.delete(row);
                matchScoreQueueService.completeIfAwaited(row.getCandidateEmail(), row.getJobId(), row.getJobType(), null);
                return;
            }

            Job job = new Job(row.getJobTitle(), row.getJobCompanyName(), null, row.getJobLocation(),
                    row.getJobEmploymentType(), row.getJobSalary(), row.getJobDescription(),
                    row.getJobRequirements(), row.getJobSkills());
            job.setId(row.getJobId());

            String cvFingerprint = jobMatchService.fingerprintCv(analysis);
            String jobFingerprint = jobMatchService.fingerprintJob(job);

            // A newer JobMatchScore may already exist with these exact current fingerprints - e.g.
            // another worker (a different instance, in a horizontally-scaled deployment) already
            // finished this exact candidate+job since this row was claimed. Skip the AI call
            // entirely rather than duplicate it; this is the queue-level half of the singleflight
            // guarantee (JobMatchScoreRepository's unique constraint is the storage-level half).
            JobMatchScore alreadyFresh = jobMatchScoreRepository
                    .findByCandidateEmailAndJobId(row.getCandidateEmail(), row.getJobId())
                    .filter(s -> cvFingerprint.equals(s.getCvFingerprint()) && jobFingerprint.equals(s.getJobFingerprint()))
                    .orElse(null);

            if (alreadyFresh != null) {
                matchScoreJobRepository.delete(row);
                matchScoreQueueService.completeIfAwaited(row.getCandidateEmail(), row.getJobId(), row.getJobType(), alreadyFresh);
                matchMetrics.recordQueueJobProcessed("already_fresh", elapsedMs(start));
                return;
            }

            String jobContentFingerprint = jobMatchService.buildJobContentFingerprint(job, jobFingerprint);
            Map<Long, String> jobFingerprints = Map.of(job.getId(), jobFingerprint);

            JsonNode matches = jobMatchService.computeChunkWithRetry(
                    analysis, List.of(job), jobFingerprints, row.getLanguage(), aiConcurrencyLimiter);
            JsonNode match = jobMatchService.firstMatchForJob(matches, job.getId());

            if (match == null) {
                handleFailure(row, "AI call failed validation on both attempts, or returned no result");
                matchMetrics.recordQueueJobProcessed("failed", elapsedMs(start));
                return;
            }

            JobMatchService.ParsedMatch parsed = jobMatchService.parseMatch(match);
            JobMatchScore score = jobMatchScoreRepository
                    .findByCandidateEmailAndJobId(row.getCandidateEmail(), row.getJobId())
                    .orElse(new JobMatchScore());
            jobMatchService.applyParsedMatchToScore(score, parsed, job, analysis, row.getCandidateEmail(), job.getId(),
                    cvFingerprint, jobFingerprint, jobContentFingerprint);
            score = jobMatchService.jobMatchScoreRepositorySafeSave(score, row.getCandidateEmail(), job.getId());
            jobMatchService.maybeNotifyHighMatch(row.getCandidateEmail(), job, score);

            matchScoreJobRepository.delete(row);
            matchScoreQueueService.completeIfAwaited(row.getCandidateEmail(), row.getJobId(), row.getJobType(), score);
            matchMetrics.recordQueueJobProcessed("success", elapsedMs(start));
        } catch (Throwable t) {
            // Throwable, not Exception: an OutOfMemoryError or StackOverflowError from a
            // pathological job/CV payload must still land this row back in PENDING/FAILED via
            // handleFailure - otherwise the row (and the request awaiting it) is stuck forever,
            // since nothing else ever marks it terminal.
            log.error("Unexpected failure processing match-score queue row id={} candidate={} jobId={}",
                    row.getId(), row.getCandidateEmail(), row.getJobId(), t);
            try {
                handleFailure(row, t.getClass().getSimpleName() + ": " + t.getMessage());
            } catch (Exception saveFailure) {
                log.error("Failed to even record the failure for queue row id={}", row.getId(), saveFailure);
            }
            matchMetrics.recordQueueJobProcessed("error", elapsedMs(start));
        }
    }

    // Reclaims rows a worker claimed (IN_PROGRESS) but never finished - the app crashed,
    // restarted, or was redeployed mid-processing. Without this, such a row stays IN_PROGRESS
    // forever: enqueueIfNeeded (see MatchScoreQueueService) treats IN_PROGRESS as "already being
    // worked" and never touches it again, permanently blocking that candidate+job from ever being
    // recomputed. Routed through the normal handleFailure path so it gets the same retry/backoff
    // and eventual FAILED-after-max-attempts treatment as any other failure, rather than being
    // reset to PENDING unconditionally forever.
    @Scheduled(fixedDelayString = "${matching.queue.reap-interval-ms:60000}")
    public void reclaimStaleInProgress() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(Math.max(1, staleInProgressMinutes));
        List<MatchScoreJob> stale;
        try {
            stale = matchScoreJobRepository.findByStatusAndUpdatedAtBefore("IN_PROGRESS", cutoff);
        } catch (Exception e) {
            log.error("Failed to query stale IN_PROGRESS match-score queue rows", e);
            return;
        }

        for (MatchScoreJob row : stale) {
            log.warn("Reclaiming stale IN_PROGRESS match-score queue row id={} candidate={} jobId={} (stuck since {})",
                    row.getId(), row.getCandidateEmail(), row.getJobId(), row.getUpdatedAt());
            try {
                handleFailure(row, "Reclaimed after being stuck IN_PROGRESS for over " + staleInProgressMinutes
                        + "m - worker likely crashed or the app restarted mid-processing");
            } catch (Exception e) {
                log.error("Failed to reclaim stale queue row id={}", row.getId(), e);
            }
        }
    }

    private void handleFailure(MatchScoreJob row, String error) {
        int attempts = row.getAttempts() + 1;
        row.setAttempts(attempts);
        row.setLastError(error);

        if (attempts >= maxAttempts) {
            row.setStatus("FAILED");
        } else {
            row.setStatus("PENDING");
            row.setAvailableAt(LocalDateTime.now().plusSeconds(backoffSeconds(attempts)));
        }
        matchScoreJobRepository.save(row);

        // The awaiting request (if any) doesn't wait out the full retry/backoff cycle - it gets
        // the honest "couldn't compute right now" sentinel promptly, exactly like a same-request
        // AI failure always has. The queue keeps retrying in the background regardless; the next
        // page load picks up whatever it eventually produces via the normal fingerprint cache.
        matchScoreQueueService.completeIfAwaited(row.getCandidateEmail(), row.getJobId(), row.getJobType(), null);
    }

    // Exponential backoff with a cap - 10s, 20s, 40s, 80s, capped at 5 minutes so a persistently
    // failing job (e.g. a sustained OpenAI outage) doesn't spin the worker pool hot.
    private long backoffSeconds(int attempts) {
        return Math.min(300, (long) (10 * Math.pow(2, attempts - 1)));
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
