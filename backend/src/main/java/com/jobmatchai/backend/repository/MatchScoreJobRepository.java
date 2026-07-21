package com.jobmatchai.backend.repository;

import com.jobmatchai.backend.model.MatchScoreJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface MatchScoreJobRepository extends JpaRepository<MatchScoreJob, Long> {

    Optional<MatchScoreJob> findByCandidateEmailAndJobIdAndJobType(String candidateEmail, Long jobId, String jobType);

    // Used when a candidate's CV is deleted or replaced (see CVController) - any row still
    // queued/in-flight for their OLD CV is no longer meaningful and must not linger.
    void deleteByCandidateEmail(String candidateEmail);

    // Used when a job is deleted (internal, via JobController) or pruned as expired (external, via
    // ExternalJobService) - any row still queued for a job that no longer exists must not linger
    // forever as an orphan the worker can never successfully process.
    void deleteByJobIdInAndJobType(List<Long> jobIds, String jobType);

    // The actual cross-worker, cross-instance claim: FOR UPDATE SKIP LOCKED means two workers
    // racing this query at the same instant can never both receive the same row - one gets it,
    // the other silently skips it and gets the next one instead. This is what makes the queue
    // safe to poll concurrently from multiple worker threads AND multiple app instances without
    // any external broker/lock service - Postgres's own row locking does the job. Callers MUST
    // run this inside a short transaction that also flips the claimed rows to IN_PROGRESS before
    // committing (see MatchScoreQueueWorker#claimBatch) - never hold this transaction open across
    // an actual AI call.
    @Query(value = "SELECT * FROM match_score_jobs WHERE status = 'PENDING' AND available_at <= :now "
            + "ORDER BY available_at ASC LIMIT :batchSize FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<MatchScoreJob> lockNextBatch(@Param("now") LocalDateTime now, @Param("batchSize") int batchSize);

    @Query("SELECT COUNT(m) FROM MatchScoreJob m WHERE m.status = :status")
    long countByStatus(@Param("status") String status);

    // Rows a worker claimed (flipped to IN_PROGRESS) but never finished - the app crashed,
    // restarted, or OOM'd mid-processing. Without a reaper these would stay IN_PROGRESS forever,
    // since enqueueIfNeeded treats IN_PROGRESS as "already being worked" and never resets it - see
    // MatchScoreQueueWorker#reclaimStaleInProgress, which routes each one through the normal
    // handleFailure retry/backoff path rather than assuming it's unrecoverable.
    List<MatchScoreJob> findByStatusAndUpdatedAtBefore(String status, LocalDateTime cutoff);

    @Modifying
    @Query("DELETE FROM MatchScoreJob m WHERE m.status = 'FAILED' AND m.updatedAt < :cutoff")
    int deleteFailedOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
