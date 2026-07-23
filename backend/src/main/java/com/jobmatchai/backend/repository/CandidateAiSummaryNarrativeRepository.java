package com.jobmatchai.backend.repository;

import com.jobmatchai.backend.model.CandidateAiSummaryNarrative;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CandidateAiSummaryNarrativeRepository extends JpaRepository<CandidateAiSummaryNarrative, Long> {

    Optional<CandidateAiSummaryNarrative> findByCandidateEmailAndJobIdAndLanguage(String candidateEmail, Long jobId, String language);

    void deleteByCandidateEmail(String candidateEmail);

    void deleteByJobIdIn(List<Long> jobIds);
}
