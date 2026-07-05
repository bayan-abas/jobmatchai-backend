package com.jobmatchai.backend.repository;

import com.jobmatchai.backend.model.SavedJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SavedJobRepository extends JpaRepository<SavedJob, Long> {

    List<SavedJob> findByCandidateEmailOrderBySavedAtDesc(String candidateEmail);

    Optional<SavedJob> findByCandidateEmailAndJobIdAndJobType(String candidateEmail, Long jobId, String jobType);

    void deleteByCandidateEmailAndJobIdAndJobType(String candidateEmail, Long jobId, String jobType);

    List<SavedJob> findByJobIdAndJobType(Long jobId, String jobType);

    void deleteByCandidateEmail(String candidateEmail);

    void deleteByJobIdInAndJobType(List<Long> jobIds, String jobType);
}
