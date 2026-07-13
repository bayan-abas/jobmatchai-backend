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
 * Adds indexes on columns that are looked up individually on nearly every request, but which
 * spring.jpa.hibernate.ddl-auto=update never adds by itself: ddl-auto=update only ever ADDS new
 * columns/tables to bring the schema in line with the entities - it does not add indexes (or
 * unique constraints, see CvAnalysisUniqueConstraintSchemaConfig) to a table that already exists,
 * even if the entity gained an @Table(indexes = ...) annotation, since that annotation only takes
 * effect on CREATE TABLE.
 *
 * Three real, currently-missing indexes, each backing a derived single-column lookup that runs on
 * a hot path against this app's live Postgres database:
 *   - external_jobs.external_job_id - looked up (via findByExternalJobIdOrApplyUrl) once per
 *     fetched job on every import cycle, to decide insert-vs-update.
 *   - applications.company_email - looked up (via findByCompanyEmail) on every company dashboard
 *     load.
 *   - cv_analysis.user_email - looked up (via findByUserEmail) on nearly every CV/match endpoint.
 *
 * CREATE INDEX IF NOT EXISTS makes this idempotent on every startup, and a no-op entirely on
 * non-Postgres datasources (e.g. local H2 dev, which doesn't have this app's performance profile
 * to begin with).
 */
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
            // Fresh database, table not created yet by Hibernate on this boot - nothing to
            // index yet; a later restart (once the table exists) will pick this up.
            return;
        }

        try {
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS " + indexName + " ON " + table + " (" + column + ")");
            log.debug("Ensured index {} on {}({}).", indexName, table, column);
        } catch (Exception e) {
            // Never let an index-creation problem take down application startup - the app is
            // fully functional (just slower on this one lookup) without the index.
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
