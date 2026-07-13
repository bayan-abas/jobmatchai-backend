-- Reference migration (this app runs on Hibernate ddl-auto=update, so these columns are also
-- created automatically at boot; this file documents the schema change and covers hosted DBs
-- where ddl-auto is intentionally restricted). See EmbeddingService and JobMatchService for how
-- these back a deterministic, keyword-free pre-filter gate (cosine similarity computed in
-- application code, not a Postgres vector type - the volume here never justifies pgvector).
ALTER TABLE cv_analysis
    ADD COLUMN IF NOT EXISTS profile_embedding TEXT,
    ADD COLUMN IF NOT EXISTS profile_embedding_hash VARCHAR(255),
    ADD COLUMN IF NOT EXISTS profile_embedding_model VARCHAR(255);

ALTER TABLE external_jobs
    ADD COLUMN IF NOT EXISTS content_embedding TEXT,
    ADD COLUMN IF NOT EXISTS content_embedding_hash VARCHAR(255),
    ADD COLUMN IF NOT EXISTS content_embedding_model VARCHAR(255);
