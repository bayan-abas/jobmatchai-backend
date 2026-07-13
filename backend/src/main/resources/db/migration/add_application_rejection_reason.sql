-- Reference migration (this app runs on Hibernate ddl-auto=update, so this column is also
-- created automatically at boot; this file documents the schema change and covers hosted DBs
-- where ddl-auto is intentionally restricted). Mandatory, company-written free text - set only
-- when a company rejects an application. See ApplicationController#updateStatus and
-- Application#rejectionReason. Declared TEXT from the start (not @Lob) - see
-- add_application_contact_fields.sql for why @Lob on a String breaks reads on this schema.
ALTER TABLE applications
    ADD COLUMN IF NOT EXISTS rejection_reason TEXT;
