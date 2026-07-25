package com.jobmatchai.backend.repository;

import com.jobmatchai.backend.model.CVAnalysisCache;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CVAnalysisCacheRepository extends JpaRepository<CVAnalysisCache, Long> {

    // בודק אם כבר יש ניתוח שמור לאותו טקסט קורות חיים, שפה וגרסת פרומפט - כדי לא לקרוא שוב ל-AI בחינם
    Optional<CVAnalysisCache> findByCvTextHashAndLanguageAndPromptVersion(
            String cvTextHash, String language, String promptVersion);
}
