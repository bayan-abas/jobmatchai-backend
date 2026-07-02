package com.jobmatchai.backend.repository;

import com.jobmatchai.backend.model.ExternalJob;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExternalJobRepository extends JpaRepository<ExternalJob, Long> {
    Optional<ExternalJob> findByExternalJobId(String externalJobId);
    boolean existsByExternalJobIdOrApplyUrl(String externalJobId, String applyUrl);
}
