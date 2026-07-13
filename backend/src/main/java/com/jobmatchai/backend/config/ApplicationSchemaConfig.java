package com.jobmatchai.backend.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

/**
 * Fixes a specific historical schema mismatch on Postgres, same shape as SavedJobSchemaConfig's
 * saved_at fix: applications.pre_interview_answers_json was created as an "oid" column (a
 * Postgres large-object reference) because the entity used to map it with @Lob, before that was
 * corrected to @Column(columnDefinition = "TEXT") - the same fix contactMessage/rejectionReason
 * already had. Any row written under the old mapping has real content sitting in pg_largeobject,
 * addressable only via lo_get(oid); a bare "ALTER COLUMN ... TYPE text" would just stringify the
 * numeric oid itself and silently discard the actual answers. This reads that large-object
 * content back out explicitly before switching the column to plain TEXT, so existing
 * pre-interview answers survive the migration instead of being lost.
 *
 * Inspects the column's actual current type first (via information_schema) and only migrates if
 * it isn't already text - a no-op on every subsequent startup once fixed, and a no-op entirely on
 * non-Postgres datasources (e.g. local H2 dev).
 */
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
            // Column/table doesn't exist yet (fresh database) - Hibernate creates it with the
            // correct type on its own; nothing to migrate.
            return;
        }

        if (!"oid".equals(currentType)) {
            log.debug("applications.pre_interview_answers_json is already '{}' - no migration needed.", currentType);
            return;
        }

        log.info("Migrating applications.pre_interview_answers_json from oid (large object) to TEXT, "
                + "preserving existing large-object content.");

        // 1) Pull the real text out of pg_largeobject via lo_get BEFORE the column that
        //    references those oids is touched, into a new plain-text column.
        jdbcTemplate.execute(
                "ALTER TABLE applications ADD COLUMN IF NOT EXISTS pre_interview_answers_json_migrated TEXT");
        jdbcTemplate.execute("""
                UPDATE applications
                SET pre_interview_answers_json_migrated = convert_from(lo_get(pre_interview_answers_json), 'UTF8')
                WHERE pre_interview_answers_json IS NOT NULL
                """);

        // 2) Release the now-migrated large objects so they don't leak in pg_largeobject once
        //    the oid column referencing them is dropped.
        jdbcTemplate.execute("""
                SELECT lo_unlink(pre_interview_answers_json)
                FROM applications
                WHERE pre_interview_answers_json IS NOT NULL
                """);

        // 3) Drop the old oid column and rename the migrated one into its place, so the entity's
        //    existing column name (pre_interview_answers_json, now TEXT per @Column above) still
        //    matches with no Java-side change needed beyond the annotation already fixed.
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
