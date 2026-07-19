package com.jobmatchai.backend.repository;

import com.jobmatchai.backend.model.Job;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JobRepository extends JpaRepository<Job, Long> {

    List<Job> findByCompanyEmail(String companyEmail);

    void deleteByCompanyEmail(String companyEmail);

    // Backs the startup embedding backfill in JobMatchService - rows created before the embedding
    // pre-filter existed (or any row whose embedding call failed) have no vector yet.
    List<Job> findByContentEmbeddingIsNull();

}