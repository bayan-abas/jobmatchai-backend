package com.jobmatchai.backend.repository;

import com.jobmatchai.backend.model.JobMatchScore;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface JobMatchScoreRepository extends JpaRepository<JobMatchScore, Long> {

    List<JobMatchScore> findByCandidateEmailAndJobIdIn(String candidateEmail, List<Long> jobIds);

    List<JobMatchScore> findByCandidateEmailInAndJobIdIn(List<String> candidateEmails, List<Long> jobIds);

    Optional<JobMatchScore> findByCandidateEmailAndJobId(String candidateEmail, Long jobId);

    void deleteByCandidateEmail(String candidateEmail);

    void deleteByJobIdIn(List<Long> jobIds);
}
