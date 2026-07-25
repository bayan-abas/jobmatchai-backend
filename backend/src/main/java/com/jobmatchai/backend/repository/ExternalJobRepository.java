package com.jobmatchai.backend.repository;

import com.jobmatchai.backend.model.ExternalJob;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ExternalJobRepository extends JpaRepository<ExternalJob, Long> {

    interface TitleCompanyProjection {
        Long getId();
        String getTitle();
        String getCompanyName();
    }

    // מביא רק שדות קלים (id, כותרת, חברה) בלי כל שאר העמודות הכבדות - חוסך זיכרון כשצריך רק לזהות משרות
    @Query("SELECT e.id AS id, e.title AS title, e.companyName AS companyName FROM ExternalJob e")
    List<TitleCompanyProjection> findAllTitleCompanyProjections();
    Optional<ExternalJob> findByExternalJobId(String externalJobId);

    Optional<ExternalJob> findByExternalJobIdOrApplyUrl(String externalJobId, String applyUrl);

    List<ExternalJob> findByExternalJobIdInOrApplyUrlIn(List<String> externalJobIds, List<String> applyUrls);

    List<ExternalJob> findAllByOrderByImportedAtDesc();

    List<Long> findIdByImportedAtBefore(LocalDateTime cutoff);

    List<ExternalJob> findByContentEmbeddingIsNull();
}
