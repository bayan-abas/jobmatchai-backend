-- Reference migration (this app runs on Hibernate ddl-auto=update, so these columns are also
-- created automatically at boot; this file documents the schema change and covers hosted DBs
-- where ddl-auto is intentionally restricted). See JobMatchService#applyParsedMatchToScore -
-- matched_skills already existed as the combined (mandatory + preferred) list; these two are the
-- same data split out, mirroring missing_required_skills/missing_preferred_skills, so the UI can
-- badge a matched skill as "required" vs "preferred" instead of showing them identically.
ALTER TABLE job_match_scores
    ADD COLUMN IF NOT EXISTS matched_required_skills TEXT,
    ADD COLUMN IF NOT EXISTS matched_preferred_skills TEXT;
