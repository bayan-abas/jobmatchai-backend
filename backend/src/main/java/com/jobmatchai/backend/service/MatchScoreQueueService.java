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

@Service
public class MatchScoreQueueService {

    private static final Logger log = LoggerFactory.getLogger(MatchScoreQueueService.class);

    @Autowired
    private MatchScoreJobRepository matchScoreJobRepository;

    @Autowired
    private JobMatchScoreRepository jobMatchScoreRepository;

    @Autowired
    private MatchMetrics matchMetrics;

    private final ScheduledExecutorService pollScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "match-score-queue-poller");
        t.setDaemon(true);
        return t;
    });

    private final ConcurrentHashMap<String, CompletableFuture<JobMatchScore>> waiters = new ConcurrentHashMap<>();

    private static String key(String email, long jobId, String jobType) {
        return email + "|" + jobId + "|" + jobType;
    }

    // מוסיף job לתור החישוב רק אם אין כבר אחד ממתין/בעיבוד עבור אותו מועמד ומשרה - מונע כפילויות
    @Transactional
    public void enqueueIfNeeded(
            String email, Job job, String jobType, String language, String cvFingerprint, String jobFingerprint) {
        long jobId = job.getId();

        Optional<MatchScoreJob> existing =
                matchScoreJobRepository.findByCandidateEmailAndJobIdAndJobType(email, jobId, jobType);

        if (existing.isPresent()) {
            MatchScoreJob row = existing.get();
            if ("PENDING".equals(row.getStatus()) || "IN_PROGRESS".equals(row.getStatus())) {

                return;
            }

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

    // מחכה (עם timeout) שהתוצאה של job מסוים תהיה מוכנה ומחזיר Future שמושלם ע"י completeIfAwaited או ע"י poll הגיבוי
    public CompletableFuture<JobMatchScore> awaitResult(
            String email, long jobId, String jobType, String cvFingerprint, String jobFingerprint, long timeoutMs) {

        String k = key(email, jobId, jobType);
        CompletableFuture<JobMatchScore> future = waiters.computeIfAbsent(k, kk -> new CompletableFuture<>());

        // completeIfAwaited עובד רק בתוך אותו JVM - אם ה-worker שמעבד את השורה רץ באינסטנס אחר, ה-poll הזה הוא הגיבוי
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

    // קורא ה-worker לזה כשהוא מסיים לעבד שורה - משחרר כל caller שקרא ל-awaitResult וממתין לתוצאה הזו
    public void completeIfAwaited(String email, long jobId, String jobType, JobMatchScore score) {
        CompletableFuture<JobMatchScore> future = waiters.get(key(email, jobId, jobType));
        if (future != null) {
            future.complete(score);
        }
    }
}
