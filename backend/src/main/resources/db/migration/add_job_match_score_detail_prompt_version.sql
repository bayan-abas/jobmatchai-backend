-- Reference migration (this app runs on Hibernate ddl-auto=update, so this column is also
-- created automatically at boot; this file documents the schema change and covers hosted DBs
-- where ddl-auto is intentionally restricted). See JobMatchService.DETAIL_PROMPT_VERSION and
-- getMatchDetail's detailStale check - existing rows have a NULL detail_prompt_version, which
-- compares unequal to the current constant and so forces every already-cached whyGoodMatch/
-- whyNotPerfectMatch narrative to regenerate (and be re-filtered) under the current rules the
-- next time its job is opened.
ALTER TABLE job_match_scores
    ADD COLUMN IF NOT EXISTS detail_prompt_version INTEGER;
