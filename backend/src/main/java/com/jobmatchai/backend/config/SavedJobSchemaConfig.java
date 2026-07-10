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
 * Fixes a specific historical schema mismatch on Postgres: saved_jobs.saved_at was created
 * under a type Postgres cannot implicitly cast to timestamp (e.g. an earlier varchar/text
 * column, from before the SavedJob entity's saved_at field was LocalDateTime). Hibernate's
 * ddl-auto=update trying to bring the column in line with the entity emits a bare
 * "ALTER COLUMN saved_at TYPE timestamp" with no USING clause, which Postgres rejects with
 * "column saved_at cannot be automatically cast to type timestamp without time zone" -
 * Hibernate logs that failure and continues (schema update failures are non-fatal, unlike
 * validate), leaving the column stuck in its old type indefinitely rather than crashing startup.
 *
 * This inspects the column's actual current type first (via information_schema, not a blind
 * assumption) and only issues the explicit USING-cast ALTER if it isn't already a timestamp -
 * a no-op on every subsequent startup once fixed, and a no-op entirely on non-Postgres
 * datasources (e.g. local H2 dev), which don't have this cast restriction in the first place.
 */
@Component
public class SavedJobSchemaConfig {

    private static final Logger log = LoggerFactory.getLogger(SavedJobSchemaConfig.class);

    private final JdbcTemplate jdbcTemplate;

    public SavedJobSchemaConfig(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void fixSavedAtColumnType() {
        if (!isPostgres()) {
            return;
        }

        String currentType = currentSavedAtType();

        if (currentType == null) {
            // Column/table doesn't exist yet (fresh database) - Hibernate will create it with
            // the correct type on its own; nothing to migrate.
            return;
        }

        if (currentType.equals("timestamp without time zone")) {
            log.debug("saved_jobs.saved_at is already timestamp - no migration needed.");
            return;
        }

        log.info("Migrating saved_jobs.saved_at from '{}' to timestamp(6)", currentType);

        jdbcTemplate.execute("""
                ALTER TABLE saved_jobs
                ALTER COLUMN saved_at TYPE timestamp(6)
                USING saved_at::timestamp(6)
                """);

        log.info("saved_jobs.saved_at migration complete.");
    }

    private String currentSavedAtType() {
        return jdbcTemplate.query(
                "SELECT data_type FROM information_schema.columns " +
                        "WHERE table_name = 'saved_jobs' AND column_name = 'saved_at'",
                rs -> rs.next() ? rs.getString(1) : null
        );
    }

    private boolean isPostgres() {
        try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String productName = metaData.getDatabaseProductName();
            return productName != null && productName.toLowerCase().contains("postgresql");
        } catch (SQLException e) {
            log.warn("Could not determine database product name - skipping saved_jobs.saved_at schema check.", e);
            return false;
        }
    }
}
