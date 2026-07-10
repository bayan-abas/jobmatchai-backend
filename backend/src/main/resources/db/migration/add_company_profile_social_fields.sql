-- Reference migration (this app runs on Hibernate ddl-auto=update, so these columns are also
-- created automatically at boot; this file documents the schema change and covers hosted DBs
-- where ddl-auto is intentionally restricted). Adds the additional Company Information fields
-- (LinkedIn, GitHub, founding year, company type) shown on the Company Profile page.
ALTER TABLE "user"
    ADD COLUMN IF NOT EXISTS linkedin VARCHAR(255),
    ADD COLUMN IF NOT EXISTS github VARCHAR(255),
    ADD COLUMN IF NOT EXISTS founded VARCHAR(4),
    ADD COLUMN IF NOT EXISTS company_type VARCHAR(50);
