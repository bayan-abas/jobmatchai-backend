package com.jobmatchai.backend.repository;

import com.jobmatchai.backend.model.JobMatchNarrative;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface JobMatchNarrativeRepository extends JpaRepository<JobMatchNarrative, Long> {

    Optional<JobMatchNarrative> findByCandidateEmailAndJobIdAndLanguage(String candidateEmail, Long jobId, String language);

    List<JobMatchNarrative> findByCandidateEmailAndJobIdInAndLanguage(String candidateEmail, List<Long> jobIds, String language);

    void deleteByCandidateEmail(String candidateEmail);

    void deleteByJobIdIn(List<Long> jobIds);
}
