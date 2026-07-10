-- Reference migration (this app runs on Hibernate ddl-auto=update, so these columns are also
-- created automatically at boot; this file documents the schema change and covers hosted DBs
-- where ddl-auto is intentionally restricted). See MatchScoreCalculator and JobMatchService for
-- how these are computed - matchPercent is a backend-computed weighted combination of the
-- component percents below, never a number the AI invents directly.
CREATE TABLE IF NOT EXISTS job_match_scores (
    id BIGSERIAL PRIMARY KEY
);

ALTER TABLE job_match_scores
    ADD COLUMN IF NOT EXISTS missing_required_skills TEXT,
    ADD COLUMN IF NOT EXISTS missing_preferred_skills TEXT,
    ADD COLUMN IF NOT EXISTS field_relevance_percent INTEGER,
    ADD COLUMN IF NOT EXISTS certification_match_percent INTEGER,
    ADD COLUMN IF NOT EXISTS location_match_percent INTEGER,
    ADD COLUMN IF NOT EXISTS job_content_fingerprint VARCHAR(255);
