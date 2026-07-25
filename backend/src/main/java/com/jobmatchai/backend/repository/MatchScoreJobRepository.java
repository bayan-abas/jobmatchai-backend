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

    void deleteByCandidateEmail(String candidateEmail);

    void deleteByJobIdInAndJobType(List<Long> jobIds, String jobType);

    // native query כי JPQL לא תומך ב-SKIP LOCKED - מאפשר לכמה worker instances למשוך batch בלי לחסום אחד את השני
    @Query(value = "SELECT * FROM match_score_jobs WHERE status = 'PENDING' AND available_at <= :now "
            + "ORDER BY available_at ASC LIMIT :batchSize FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<MatchScoreJob> lockNextBatch(@Param("now") LocalDateTime now, @Param("batchSize") int batchSize);

    @Query("SELECT COUNT(m) FROM MatchScoreJob m WHERE m.status = :status")
    long countByStatus(@Param("status") String status);

    List<MatchScoreJob> findByStatusAndUpdatedAtBefore(String status, LocalDateTime cutoff);

    // ניקוי תקופתי - מוחק jobs שנכשלו לפני הרבה זמן כדי שהטבלה לא תתמלא לצמיתות
    @Modifying
    @Query("DELETE FROM MatchScoreJob m WHERE m.status = 'FAILED' AND m.updatedAt < :cutoff")
    int deleteFailedOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
