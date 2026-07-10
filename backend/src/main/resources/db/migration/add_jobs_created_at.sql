-- Reference migration (this app runs on Hibernate ddl-auto=update, so this column is also
-- created automatically at boot; this file documents the schema change and covers hosted DBs
-- where ddl-auto is intentionally restricted). See Job.onCreate() (@PrePersist) for how new
-- rows get their timestamp - it is always set server-side from the current clock, never
-- client-supplied or hardcoded.
ALTER TABLE jobs
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP;

-- Existing jobs created before this column existed have no recoverable creation time.
-- They are intentionally left NULL rather than backfilled with an approximate/fabricated
-- value; the API returns null createdAt for these until they are re-created or otherwise
-- updated. Only newly created jobs are guaranteed a real timestamp.
