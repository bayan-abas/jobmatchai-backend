package com.jobmatchai.backend.repository;

import com.jobmatchai.backend.model.ExternalJob;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ExternalJobRepository extends JpaRepository<ExternalJob, Long> {

    // Lightweight projection (just id/title/companyName, not the full row with its description/
    // embedding/etc.) backing importFromProviders' title+company duplicate check against every
    // ALREADY-STORED external job, not just the current fetch batch - see that method's own
    // comment for why the batch-scoped check alone let a provider reassigning a new external id
    // to an already-imported posting slip through as a true duplicate row.
    interface TitleCompanyProjection {
        Long getId();
        String getTitle();
        String getCompanyName();
    }

    @Query("SELECT e.id AS id, e.title AS title, e.companyName AS companyName FROM ExternalJob e")
    List<TitleCompanyProjection> findAllTitleCompanyProjections();
    Optional<ExternalJob> findByExternalJobId(String externalJobId);

    // Used by the import cycle to find an already-seen posting so its content can be refreshed
    // in place (see ExternalJobService#importFromProviders) instead of only ever skipping it.
    Optional<ExternalJob> findByExternalJobIdOrApplyUrl(String externalJobId, String applyUrl);

    // Batch counterpart of findByExternalJobIdOrApplyUrl - loads every existing row that could
    // possibly match ANY job in the current fetch in one query, instead of one query per fetched
    // job (see ExternalJobService#importFromProviders). The caller builds its own
    // externalJobId->row and applyUrl->row maps from the result for O(1) per-job lookup; this
    // repository method only needs to return the union (some rows may match by id, some by url,
    // some by both - de-duplicated automatically since each is a distinct persisted row).
    List<ExternalJob> findByExternalJobIdInOrApplyUrlIn(List<String> externalJobIds, List<String> applyUrls);

    // findAll() has no defined order (effectively insertion/id order on most DBs), so newly
    // imported jobs silently land at the bottom of a page that never paginates - a candidate
    // re-opening External Jobs would see the exact same jobs on top every time even on a run
    // that did import new ones. Newest-first is what makes freshly imported postings actually
    // visible.
    List<ExternalJob> findAllByOrderByImportedAtDesc();

    // Split into a find-then-delete pair (see ExternalJobService#pruneExpiredJobs) rather than a
    // single bulk deleteByImportedAtBefore, so the caller knows exactly which ids are about to be
    // pruned and can cascade-delete their JobMatchScore/MatchScoreJob rows first - otherwise those
    // would be left pointing at an external job id that no longer exists.
    List<Long> findIdByImportedAtBefore(LocalDateTime cutoff);

    // Backs the startup embedding backfill in ExternalJobService - rows imported before the
    // embedding pre-filter existed (or any row whose embedding call failed at import time) have
    // no vector yet and are picked up here to be filled in.
    List<ExternalJob> findByContentEmbeddingIsNull();
}
