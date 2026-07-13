-- Reference migration (this app runs on Hibernate ddl-auto=update, so this is also applied
-- automatically at boot; this file documents the schema change and covers hosted DBs where
-- ddl-auto is intentionally restricted). Widens external_jobs.description from a bounded
-- VARCHAR to unbounded TEXT - match scoring must see a posting's COMPLETE description, not a
-- truncated excerpt (see JobicyJobProvider#resolveDescription and
-- OpenAICVAnalysisService#buildJobsBlock) - and adds the AI-generated "About this job" summary
-- cache (see ExternalJobService#getOrGenerateAboutSummary).
ALTER TABLE external_jobs
    ALTER COLUMN description TYPE TEXT;

ALTER TABLE external_jobs
    ADD COLUMN IF NOT EXISTS about_summary TEXT,
    ADD COLUMN IF NOT EXISTS about_summary_content_hash VARCHAR(255);
