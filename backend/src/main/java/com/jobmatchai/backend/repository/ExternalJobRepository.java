package com.jobmatchai.backend.repository;

import com.jobmatchai.backend.model.ExternalJob;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ExternalJobRepository extends JpaRepository<ExternalJob, Long> {
    Optional<ExternalJob> findByExternalJobId(String externalJobId);
    boolean existsByExternalJobIdOrApplyUrl(String externalJobId, String applyUrl);

    // findAll() has no defined order (effectively insertion/id order on most DBs), so newly
    // imported jobs silently land at the bottom of a page that never paginates - a candidate
    // re-opening External Jobs would see the exact same jobs on top every time even on a run
    // that did import new ones. Newest-first is what makes freshly imported postings actually
    // visible.
    List<ExternalJob> findAllByOrderByImportedAtDesc();

    long deleteByImportedAtBefore(LocalDateTime cutoff);
}
