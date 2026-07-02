package com.jobmatchai.backend.repository;

import com.jobmatchai.backend.model.RecentlyViewedJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RecentlyViewedJobRepository extends JpaRepository<RecentlyViewedJob, Long> {

    Optional<RecentlyViewedJob> findByCandidateEmailAndJobIdAndJobType(String candidateEmail, Long jobId, String jobType);

    List<RecentlyViewedJob> findByCandidateEmailOrderByViewedAtDesc(String candidateEmail);
}
