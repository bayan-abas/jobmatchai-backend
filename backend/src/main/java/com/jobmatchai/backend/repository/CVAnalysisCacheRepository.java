package com.jobmatchai.backend.repository;

import com.jobmatchai.backend.model.CVAnalysisCache;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CVAnalysisCacheRepository extends JpaRepository<CVAnalysisCache, Long> {

    Optional<CVAnalysisCache> findByCvTextHashAndLanguageAndPromptVersion(
            String cvTextHash, String language, String promptVersion);
}
