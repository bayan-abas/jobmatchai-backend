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
public class SavedJobSchemaConfig {

    private static final Logger log = LoggerFactory.getLogger(SavedJobSchemaConfig.class);

    private final JdbcTemplate jdbcTemplate;

    public SavedJobSchemaConfig(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // בסביבות ישנות saved_at נוצרה כ-date/varchar בטעות - מתקן את זה אוטומטית בעליה כדי לא לדרוש migration ידני
    @PostConstruct
    public void fixSavedAtColumnType() {
        if (!isPostgres()) {
            return;
        }

        String currentType = currentSavedAtType();

        if (currentType == null) {

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
