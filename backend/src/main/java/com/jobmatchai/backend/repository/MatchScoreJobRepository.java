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

    @Modifying
    @Query("DELETE FROM MatchScoreJob m WHERE m.status = 'FAILED' AND m.updatedAt < :cutoff")
    int deleteFailedOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
