-- Reference migration (this app runs on Hibernate ddl-auto=update, so these columns are also
-- created automatically at boot; this file documents the schema change and covers hosted DBs
-- where ddl-auto is intentionally restricted). Set only when a company accepts an application -
-- see ApplicationController#updateStatus and Application#contactMethod.
ALTER TABLE applications
    ADD COLUMN IF NOT EXISTS contact_method VARCHAR(255),
    ADD COLUMN IF NOT EXISTS contact_method_other VARCHAR(255);

-- contact_message specifically: if ddl-auto=update already created this column while the Java
-- field was still annotated @Lob (before this fix), it was created as a Postgres oid (large
-- object reference), not plain text - any row with a real value throws at the JDBC level when
-- read back. Drop and recreate as TEXT rather than ALTER COLUMN TYPE (a plain oid-to-text cast
-- would only produce the OID NUMBER as text, not the real message content - the actual bytes
-- live in a separate large-object table referenced by that number). Safe to drop outright: this
-- column only ever holds an optional, non-critical note.
ALTER TABLE applications DROP COLUMN IF EXISTS contact_message;
ALTER TABLE applications ADD COLUMN contact_message TEXT;
