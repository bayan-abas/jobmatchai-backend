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
        String sourceName
) {}
