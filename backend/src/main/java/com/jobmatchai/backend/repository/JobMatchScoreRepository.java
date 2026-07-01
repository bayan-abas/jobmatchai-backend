package com.jobmatchai.backend.repository;

import com.jobmatchai.backend.model.JobMatchScore;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JobMatchScoreRepository extends JpaRepository<JobMatchScore, Long> {

    List<JobMatchScore> findByCandidateEmailAndJobIdIn(String candidateEmail, List<Long> jobIds);
}
