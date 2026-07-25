package com.jobmatchai.backend.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

@Component
public class CvAnalysisUniqueConstraintSchemaConfig {

    private static final Logger log = LoggerFactory.getLogger(CvAnalysisUniqueConstraintSchemaConfig.class);

    private static final String CONSTRAINT_NAME = "uk_cv_analysis_user_email";

    private final JdbcTemplate jdbcTemplate;

    public CvAnalysisUniqueConstraintSchemaConfig(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void ensureUserEmailUniqueConstraint() {
        if (!isPostgres()) {
            return;
        }

        if (!tableExists("cv_analysis")) {

            return;
        }

        if (constraintAlreadyExists()) {
            log.debug("cv_analysis already has a unique constraint on user_email - no migration needed.");
            return;
        }

        // ADD CONSTRAINT UNIQUE נכשל אם כבר קיימות כפילויות - חייבים לבדוק לפני שמנסים
        List<Map<String, Object>> duplicates = jdbcTemplate.queryForList("""
                SELECT user_email, COUNT(*) AS row_count
                FROM cv_analysis
                WHERE user_email IS NOT NULL
                GROUP BY user_email
                HAVING COUNT(*) > 1
                """);

        if (!duplicates.isEmpty()) {
            log.warn("Skipping cv_analysis.user_email unique constraint migration: found {} user_email value(s) "
                            + "with duplicate cv_analysis rows ({}). Resolve the duplicates manually (decide which "
                            + "row per user should be kept) before this constraint can be added safely.",
                    duplicates.size(), duplicates);
            return;
        }

        log.info("Adding unique constraint {} on cv_analysis(user_email).", CONSTRAINT_NAME);
        jdbcTemplate.execute(
                "ALTER TABLE cv_analysis ADD CONSTRAINT " + CONSTRAINT_NAME + " UNIQUE (user_email)");
        log.info("cv_analysis.user_email unique constraint migration complete.");
    }

    private boolean constraintAlreadyExists() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name
                 AND tc.table_schema = kcu.table_schema
                WHERE tc.table_name = 'cv_analysis'
                  AND kcu.column_name = 'user_email'
                  AND tc.constraint_type IN ('UNIQUE', 'PRIMARY KEY')
                """, Integer.class);
        return count != null && count > 0;
    }

    private boolean tableExists(String table) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?",
                Integer.class, table);
        return count != null && count > 0;
    }

    private boolean isPostgres() {
        try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String productName = metaData.getDatabaseProductName();
            return productName != null && productName.toLowerCase().contains("postgresql");
        } catch (SQLException e) {
            log.warn("Could not determine database product name - skipping cv_analysis.user_email unique constraint check.", e);
            return false;
        }
    }
}
