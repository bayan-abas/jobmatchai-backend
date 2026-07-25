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
public class HotLookupIndexSchemaConfig {

    private static final Logger log = LoggerFactory.getLogger(HotLookupIndexSchemaConfig.class);

    private final JdbcTemplate jdbcTemplate;

    public HotLookupIndexSchemaConfig(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void ensureHotLookupIndexes() {
        if (!isPostgres()) {
            return;
        }

        createIndexIfTableExists("idx_external_jobs_external_job_id", "external_jobs", "external_job_id");
        createIndexIfTableExists("idx_applications_company_email", "applications", "company_email");
        createIndexIfTableExists("idx_cv_analysis_user_email", "cv_analysis", "user_email");
    }

    private void createIndexIfTableExists(String indexName, String table, String column) {
        if (!tableExists(table)) {

            return;
        }

        try {
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS " + indexName + " ON " + table + " (" + column + ")");
            log.debug("Ensured index {} on {}({}).", indexName, table, column);
        } catch (Exception e) {

            log.warn("Could not ensure index {} on {}({})", indexName, table, column, e);
        }
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
            log.warn("Could not determine database product name - skipping hot-lookup index check.", e);
            return false;
        }
    }
}
