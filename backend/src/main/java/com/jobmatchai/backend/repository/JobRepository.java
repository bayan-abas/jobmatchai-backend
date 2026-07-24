package com.jobmatchai.backend.repository;

import com.jobmatchai.backend.model.Job;
import com.jobmatchai.backend.model.JobStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JobRepository extends JpaRepository<Job, Long> {

    List<Job> findByCompanyEmail(String companyEmail);

    void deleteByCompanyEmail(String companyEmail);

    // Backs the startup embedding backfill in JobMatchService - rows created before the embedding
    // pre-filter existed (or any row whose embedding call failed) have no vector yet.
    List<Job> findByContentEmbeddingIsNull();

    // Backs the candidate/public job listing (JobController#getAllJobs) - a CLOSED job must never
    // appear here, even though it's still fully readable via the company's own
    // findByCompanyEmail(...) (deliberately NOT filtered by status - a company must keep seeing
    // its closed jobs) or by direct id lookup (findById, used by the apply-guard and the
    // still-reachable job-details endpoint).
    List<Job> findByStatus(JobStatus status);

}