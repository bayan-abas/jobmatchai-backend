CREATE TABLE IF NOT EXISTS cv_analysis (
    id BIGSERIAL PRIMARY KEY
);

ALTER TABLE cv_analysis
    ADD COLUMN IF NOT EXISTS user_email VARCHAR(255),
    ADD COLUMN IF NOT EXISTS candidate_field TEXT,
    ADD COLUMN IF NOT EXISTS skills TEXT,
    ADD COLUMN IF NOT EXISTS summary TEXT,
    ADD COLUMN IF NOT EXISTS strengths TEXT,
    ADD COLUMN IF NOT EXISTS missing_skills TEXT,
    ADD COLUMN IF NOT EXISTS recommended_roles TEXT,
    ADD COLUMN IF NOT EXISTS overall_score TEXT,
    ADD COLUMN IF NOT EXISTS score_level TEXT,
    ADD COLUMN IF NOT EXISTS evaluation_reason TEXT,
    ADD COLUMN IF NOT EXISTS missing_information TEXT,
    -- Structured evidence fields the job matcher relies on instead of free-text prose (see
    -- JobMatchService's fieldRelated guardrail and MatchScoreCalculator's education/
    -- certification components).
    ADD COLUMN IF NOT EXISTS profession_title TEXT,
    ADD COLUMN IF NOT EXISTS education_evidence TEXT,
    ADD COLUMN IF NOT EXISTS certifications_evidence TEXT,
    ADD COLUMN IF NOT EXISTS licenses_evidence TEXT,
    ADD COLUMN IF NOT EXISTS years_of_experience TEXT,
    ADD COLUMN IF NOT EXISTS technical_skills TEXT,
    ADD COLUMN IF NOT EXISTS soft_skills TEXT,
    ADD COLUMN IF NOT EXISTS languages TEXT,
    ADD COLUMN IF NOT EXISTS previous_job_titles TEXT;

CREATE TABLE IF NOT EXISTS cv_analysis_cache (
    id BIGSERIAL PRIMARY KEY
);

ALTER TABLE cv_analysis_cache
    ADD COLUMN IF NOT EXISTS profession_title TEXT,
    ADD COLUMN IF NOT EXISTS education_evidence TEXT,
    ADD COLUMN IF NOT EXISTS certifications_evidence TEXT,
    ADD COLUMN IF NOT EXISTS licenses_evidence TEXT,
    ADD COLUMN IF NOT EXISTS years_of_experience TEXT,
    ADD COLUMN IF NOT EXISTS technical_skills TEXT,
    ADD COLUMN IF NOT EXISTS soft_skills TEXT,
    ADD COLUMN IF NOT EXISTS languages TEXT,
    ADD COLUMN IF NOT EXISTS previous_job_titles TEXT;
