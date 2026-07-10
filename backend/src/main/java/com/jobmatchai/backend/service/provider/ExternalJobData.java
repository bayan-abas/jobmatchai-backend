package com.jobmatchai.backend.service.provider;

/**
 * Provider-agnostic shape for a job fetched from any external job API.
 * Every {@link ExternalJobProvider} implementation maps its own API response into this record
 * so the rest of the application never has to know which provider is active.
 */
public record ExternalJobData(
        String externalId,
        String title,
        String companyName,
        String location,
        String country,
        String city,
        String type,
        String salary,
        String description,
        String requirements,
        String skills,
        String applyUrl,
        String sourceUrl,
        String sourceName,
        // One of frontend/src/utils/jobInference.ts's INDUSTRY_KEYS (e.g. "technology",
        // "healthcare", "legal"), resolved here - not left to the frontend - whenever the
        // provider's own API already tells us the job's category/industry or occupation code,
        // since that is far more reliable than guessing from free-text title/description
        // keywords. Null when the provider gives no such signal (e.g. Jooble), in which case
        // the frontend classifier falls back to title-first keyword inference. Must stay a
        // literal INDUSTRY_KEYS value - the frontend trusts this field outright when non-null
        // rather than re-validating it.
        String industry
) {}
