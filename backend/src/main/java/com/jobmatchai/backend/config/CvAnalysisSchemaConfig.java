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

    @PostConstruct
    public void ensureCvAnalysisSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS cv_analysis (
                    id BIGSERIAL PRIMARY KEY
                )
                """);

        jdbcTemplate.execute("""
                ALTER TABLE cv_analysis
                    ADD COLUMN IF NOT EXISTS user_email VARCHAR(255),
                    ADD COLUMN IF NOT EXISTS candidate_field TEXT,
                    ADD COLUMN IF NOT EXISTS skills TEXT,
                    ADD COLUMN IF NOT EXISTS summary TEXT,
                    ADD COLUMN IF NOT EXISTS strengths TEXT,
                    ADD COLUMN IF NOT EXISTS missing_skills TEXT,
                    ADD COLUMN IF NOT EXISTS recommended_roles TEXT,
                    ADD COLUMN IF NOT EXISTS overall_score TEXT,
                    ADD COLUMN IF NOT EXISTS score_level TEXT,
                    ADD COLUMN IF NOT EXISTS evaluation_reason TEXT,
                    ADD COLUMN IF NOT EXISTS missing_information TEXT
                """);
    }
}
