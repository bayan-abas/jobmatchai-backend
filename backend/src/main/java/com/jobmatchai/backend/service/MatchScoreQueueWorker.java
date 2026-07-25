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

    @Value("${matching.queue.batch-size:10}")
    private int batchSize;

    @Value("${matching.queue.worker-concurrency:5}")
    private int workerConcurrency;

    @Value("${matching.openai.rate-limit-per-second:3}")
    private int openAiRateLimitPerSecond;

    @Value("${matching.queue.max-attempts:4}")
    private int maxAttempts;

    @Value("${matching.queue.stale-in-progress-minutes:5}")
    private int staleInProgressMinutes;

    private Semaphore aiConcurrencyLimiter;

    private Semaphore rowConcurrencyLimiter;
    private Bucket rateLimitBucket;
    private ExecutorService dispatchExecutor;

    // מכין את מגבלות הריצה במקביל (AI + שורות) ואת ה-rate limit bucket לפני שהworker מתחיל לרוץ
    @PostConstruct
    void init() {
        aiConcurrencyLimiter = new Semaphore(Math.max(1, workerConcurrency));
        rowConcurrencyLimiter = new Semaphore(Math.max(1, workerConcurrency));
        rateLimitBucket = Bucket.builder()
                .addLimit(limit -> limit.capacity(Math.max(1, openAiRateLimitPerSecond))
                        .refillGreedy(Math.max(1, openAiRateLimitPerSecond), java.time.Duration.ofSeconds(1)))
                .build();

        dispatchExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    // רץ כל כמה שניות - שולף באטש מהתור ומפזר כל שורה ל-thread נפרד שמעבד אותה במקביל
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

    // lockNextBatch נועל את השורות ב-DB (FOR UPDATE SKIP LOCKED) כדי שכמה worker/אינסטנסים לא יתפסו אותה שורה פעמיים
    @Transactional
    List<MatchScoreJob> claimBatch() {
        List<MatchScoreJob> rows = matchScoreJobRepository.lockNextBatch(LocalDateTime.now(), batchSize);
        for (MatchScoreJob row : rows) {
            row.setStatus("IN_PROGRESS");
        }
        return matchScoreJobRepository.saveAll(rows);
    }

    // מעבד משימה אחת מהתור - קורא ל-AI לחישוב ציון ההתאמה ושומר את התוצאה, כולל בדיקות רענון (fingerprint) וטיפול בכשלים
    private void processOne(MatchScoreJob row) {
        long start = System.nanoTime();
        try {
            rateLimitBucket.asBlocking().consume(1);

            CVAnalysis analysis = cvAnalysisRepository.findByUserEmail(row.getCandidateEmail()).orElse(null);
            if (analysis == null) {

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

            CVAnalysis currentAnalysis = cvAnalysisRepository.findByUserEmail(row.getCandidateEmail()).orElse(null);
            if (currentAnalysis == null || !cvFingerprint.equals(jobMatchService.fingerprintCv(currentAnalysis))) {
                log.info("match-score-queue jobId={} candidate={} -> CV changed mid-computation, discarding stale result",
                        row.getJobId(), row.getCandidateEmail());
                matchScoreJobRepository.delete(row);
                matchScoreQueueService.completeIfAwaited(row.getCandidateEmail(), row.getJobId(), row.getJobType(), null);
                matchMetrics.recordQueueJobProcessed("discarded_stale_cv", elapsedMs(start));
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

    // מאתר שורות שנתקעו ב-IN_PROGRESS יותר מדי זמן (worker שקרס באמצע) ומחזיר אותן לתור או מכשיל אותן
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

    // מטפל בכישלון עיבוד - מגדיל את מונה הניסיונות ומחליט אם לנסות שוב עם backoff או לסמן FAILED סופית
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

        matchScoreQueueService.completeIfAwaited(row.getCandidateEmail(), row.getJobId(), row.getJobType(), null);
    }

    // exponential backoff: 10, 20, 40... שניות, לא יותר מ-5 דקות
    private long backoffSeconds(int attempts) {
        return Math.min(300, (long) (10 * Math.pow(2, attempts - 1)));
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
