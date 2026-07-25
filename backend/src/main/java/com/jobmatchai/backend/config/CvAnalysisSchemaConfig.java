package com.jobmatchai.backend.config;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class CvAnalysisSchemaConfig {

    private final JdbcTemplate jdbcTemplate;

    public CvAnalysisSchemaConfig(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // בעת עליית השרת יוצר את טבלת cv_analysis אם היא לא קיימת ומוסיף עמודות חסרות - תיקון סכימה אוטומטי במקום מיגרציה ידנית
    @PostConstruct
    public void ensureCvAnalysisSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS cv_analysis (
                    id BIGSERIAL PRIMARY KEY
                )
                """);

        String[] columns = {
                "user_email VARCHAR(255)",
                "candidate_field TEXT",
                "skills TEXT",
                "summary TEXT",
                "strengths TEXT",
                "missing_skills TEXT",
                "recommended_roles TEXT",
                "overall_score TEXT",
                "score_level TEXT",
                "evaluation_reason TEXT",
                "missing_information TEXT"
        };
        for (String column : columns) {
            jdbcTemplate.execute("ALTER TABLE cv_analysis ADD COLUMN IF NOT EXISTS " + column);
        }
    }
}
