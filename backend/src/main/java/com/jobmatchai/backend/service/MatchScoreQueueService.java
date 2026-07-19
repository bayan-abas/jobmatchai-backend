package com.jobmatchai.backend.service;

import com.jobmatchai.backend.model.Job;
import com.jobmatchai.backend.model.JobMatchScore;
import com.jobmatchai.backend.model.MatchScoreJob;
import com.jobmatchai.backend.repository.JobMatchScoreRepository;
import com.jobmatchai.backend.repository.MatchScoreJobRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

// The enqueue + "wait for a result" half of the persistent match-score queue (see
// MatchScoreQueueWorker for the half that actually drains it). Replaces JobMatchService's old
// per-request inFlightComputations map/virtual-thread-per-job model: computation is no longer
// tied to the lifetime of the HTTP request that asked for it - it's a durable row a worker (on
// THIS instance or, once horizontally scaled, any instance) will eventually process, retrying
// with backoff on failure, rate-limited independently of how many requests are asking at once.
//
// A caller (JobMatchService) that wants a result still gets one via awaitResult - this class
// keeps the streaming/progressive UX identical (a job resolves the moment its answer is ready,
// not "come back and poll the whole list again") while the actual work happens off the request
// thread. Two notification paths race for the same CompletableFuture: (1) completeIfAwaited, a
// fast, same-instance, zero-latency path a LOCAL worker calls the instant it finishes: and (2) a
// backstop DB poll, which is what makes this correct even if the row is ultimately claimed and
// processed by a DIFFERENT app instance's worker (there is no way for that instance to reach back
// into this one's memory - the shared Postgres row is the only thing both instances can see).
@Service
public class MatchScoreQueueService {

    private static final Logger log = LoggerFactory.getLogger(MatchScoreQueueService.class);

    @Autowired
    private MatchScoreJobRepository matchScoreJobRepository;

    @Autowired
    private JobMatchScoreRepository jobMatchScoreRepository;

    @Autowired
    private MatchMetrics matchMetrics;

    // One background thread is plenty - this only ever does cheap, occasional DB reads/timeouts
    // for whichever requests are actively waiting on a streaming response right now, never AI
    // calls (that's MatchScoreQueueWorker's dedicated pool).
    private final ScheduledExecutorService pollScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "match-score-queue-poller");
        t.setDaemon(true);
        return t;
    });

    private final ConcurrentHashMap<String, CompletableFuture<JobMatchScore>> waiters = new ConcurrentHashMap<>();

    private static String key(String email, long jobId, String jobType) {
        return email + "|" + jobId + "|" + jobType;
    }

    // Idempotent: concurrent callers for the exact same (candidate, job, jobType) - two browser
    // tabs, the dashboard and the job-matches page loading around the same time, or even two
    // different app instances - collapse into ONE queued row via the unique constraint on
    // (candidate_email, job_id, job_type). Whichever caller's insert wins, every other caller's
    // insert fails with a constraint violation and is treated as "good, someone already queued
    // this" rather than an error - the exact same safe-save pattern JobMatchService already uses
    // for JobMatchScore itself.
    @Transactional
    public void enqueueIfNeeded(
            String email, Job job, String jobType, String language, String cvFingerprint, String jobFingerprint) {
        long jobId = job.getId();

        Optional<MatchScoreJob> existing =
                matchScoreJobRepository.findByCandidateEmailAndJobIdAndJobType(email, jobId, jobType);

        if (existing.isPresent()) {
            MatchScoreJob row = existing.get();
            if ("PENDING".equals(row.getStatus()) || "IN_PROGRESS".equals(row.getStatus())) {
                // Already queued or already being worked - this IS the dedup, not a missed case.
                return;
            }
            // A previous DONE/FAILED row for this exact key - reset it for fresh processing
            // (e.g. the CV or the job's own content changed since it was last computed).
            applySnapshot(row, job, language, cvFingerprint, jobFingerprint);
            row.setStatus("PENDING");
            row.setAttempts(0);
            row.setLastError(null);
            matchScoreJobRepository.save(row);
            matchMetrics.recordQueueJobEnqueued();
            return;
        }

        MatchScoreJob row = new MatchScoreJob();
        row.setCandidateEmail(email);
        row.setJobId(jobId);
        row.setJobType(jobType);
        applySnapshot(row, job, language, cvFingerprint, jobFingerprint);

        try {
            matchScoreJobRepository.save(row);
            matchMetrics.recordQueueJobEnqueued();
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent enqueue for the exact same key - the winner's row is
            // equally valid; nothing left to do.
            log.debug("enqueueIfNeeded lost a race for {} - another caller already queued it", key(email, jobId, jobType));
        }
    }

    private void applySnapshot(MatchScoreJob row, Job job, String language, String cvFingerprint, String jobFingerprint) {
        row.setLanguage(language);
        row.setCvFingerprint(cvFingerprint);
        row.setJobFingerprint(jobFingerprint);
        row.setJobTitle(job.getTitle());
        row.setJobCompanyName(job.getCompanyName());
        row.setJobLocation(job.getLocation());
        row.setJobEmploymentType(job.getType());
        row.setJobSalary(job.getSalary());
        row.setJobDescription(job.getDescription());
        row.setJobRequirements(job.getRequirements());
        row.setJobSkills(job.getSkills());
    }

    // Resolves once a result is available - via whichever of the two notification paths above
    // fires first - or with null once timeoutMs elapses with no answer (a queue backlog, or a job
    // that exhausted its retries and landed FAILED). Callers treat a null result exactly like any
    // other "couldn't compute this one, please retry" case - never cached, never shown as a real
    // verdict.
    public CompletableFuture<JobMatchScore> awaitResult(
            String email, long jobId, String jobType, String cvFingerprint, String jobFingerprint, long timeoutMs) {

        String k = key(email, jobId, jobType);
        CompletableFuture<JobMatchScore> future = waiters.computeIfAbsent(k, kk -> new CompletableFuture<>());

        ScheduledFuture<?> pollHandle = pollScheduler.scheduleAtFixedRate(() -> {
            if (future.isDone()) {
                return;
            }
            try {
                jobMatchScoreRepository.findByCandidateEmailAndJobId(email, jobId).ifPresent(score -> {
                    if (cvFingerprint.equals(score.getCvFingerprint()) && jobFingerprint.equals(score.getJobFingerprint())) {
                        future.complete(score);
                    }
                });
            } catch (Exception e) {
                log.warn("Backstop poll failed for {}", k, e);
            }
        }, 300, 300, TimeUnit.MILLISECONDS);

        ScheduledFuture<?> timeoutHandle = pollScheduler.schedule(
                () -> future.complete(null), timeoutMs, TimeUnit.MILLISECONDS);

        future.whenComplete((result, ex) -> {
            pollHandle.cancel(false);
            timeoutHandle.cancel(false);
            waiters.remove(k, future);
        });

        return future;
    }

    // The fast, zero-polling-latency path: called by MatchScoreQueueWorker the instant it
    // finishes processing a row, for the common case where the request awaiting a result and the
    // worker that computed it are on the SAME instance. A no-op (by design) when nobody on this
    // instance is currently waiting on this exact key - the normal case for pure background
    // pre-computation with no active viewer.
    public void completeIfAwaited(String email, long jobId, String jobType, JobMatchScore score) {
        CompletableFuture<JobMatchScore> future = waiters.get(key(email, jobId, jobType));
        if (future != null) {
            future.complete(score);
        }
    }
}
