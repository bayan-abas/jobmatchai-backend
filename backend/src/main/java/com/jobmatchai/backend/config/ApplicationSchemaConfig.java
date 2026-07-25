package com.jobmatchai.backend.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

@Component
public class ApplicationSchemaConfig {

    private static final Logger log = LoggerFactory.getLogger(ApplicationSchemaConfig.class);

    private final JdbcTemplate jdbcTemplate;

    public ApplicationSchemaConfig(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void fixPreInterviewAnswersColumnType() {
        if (!isPostgres()) {
            return;
        }

        String currentType = currentColumnType();

        if (currentType == null) {

            return;
        }

        if (!"oid".equals(currentType)) {
            log.debug("applications.pre_interview_answers_json is already '{}' - no migration needed.", currentType);
            return;
        }

        log.info("Migrating applications.pre_interview_answers_json from oid (large object) to TEXT, "
                + "preserving existing large-object content.");

        jdbcTemplate.execute(
                "ALTER TABLE applications ADD COLUMN IF NOT EXISTS pre_interview_answers_json_migrated TEXT");
        jdbcTemplate.execute("""
                UPDATE applications
                SET pre_interview_answers_json_migrated = convert_from(lo_get(pre_interview_answers_json), 'UTF8')
                WHERE pre_interview_answers_json IS NOT NULL
                """);

        // lo_unlink כדי לא להשאיר large objects יתומים ב-pg_largeobject אחרי המעבר לטקסט רגיל
        jdbcTemplate.execute("""
                SELECT lo_unlink(pre_interview_answers_json)
                FROM applications
                WHERE pre_interview_answers_json IS NOT NULL
                """);

        jdbcTemplate.execute("ALTER TABLE applications DROP COLUMN pre_interview_answers_json");
        jdbcTemplate.execute(
                "ALTER TABLE applications RENAME COLUMN pre_interview_answers_json_migrated TO pre_interview_answers_json");

        log.info("applications.pre_interview_answers_json migration complete.");
    }

    private String currentColumnType() {
        return jdbcTemplate.query(
                "SELECT data_type FROM information_schema.columns " +
                        "WHERE table_name = 'applications' AND column_name = 'pre_interview_answers_json'",
                rs -> rs.next() ? rs.getString(1) : null
        );
    }

    private boolean isPostgres() {
        try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String productName = metaData.getDatabaseProductName();
            return productName != null && productName.toLowerCase().contains("postgresql");
        } catch (SQLException e) {
            log.warn("Could not determine database product name - skipping applications.pre_interview_answers_json schema check.", e);
            return false;
        }
    }
}
