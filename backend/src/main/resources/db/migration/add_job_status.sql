-- Reference migration (this app runs on Hibernate ddl-auto=update, so this column is also
-- created automatically at boot; this file documents the schema change and covers hosted DBs
-- where ddl-auto is intentionally restricted). See Job.status's field comment for why the
-- default lives in the column definition itself: it lets ALTER TABLE ADD COLUMN backfill every
-- pre-existing row as ACTIVE instead of failing a NOT NULL constraint with no default.
ALTER TABLE jobs
    ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
