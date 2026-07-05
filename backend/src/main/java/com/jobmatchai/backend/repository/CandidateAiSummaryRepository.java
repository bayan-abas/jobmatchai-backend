package com.jobmatchai.backend.repository;

import com.jobmatchai.backend.model.CandidateAiSummary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CandidateAiSummaryRepository extends JpaRepository<CandidateAiSummary, Long> {

    Optional<CandidateAiSummary> findFirstByCandidateEmailAndJobIdOrderByIdDesc(String candidateEmail, Long jobId);

    List<CandidateAiSummary> findByCandidateEmailInAndJobIdIn(List<String> candidateEmails, List<Long> jobIds);

    void deleteByCandidateEmail(String candidateEmail);

    void deleteByJobIdIn(List<Long> jobIds);
}
