package com.jobmatchai.backend.repository;

import com.jobmatchai.backend.model.CVAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CVAnalysisRepository extends JpaRepository<CVAnalysis, Long> {

    Optional<CVAnalysis> findByUserEmail(String userEmail);

    void deleteByUserEmail(String userEmail);
}