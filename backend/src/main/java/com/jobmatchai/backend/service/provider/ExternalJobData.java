package com.jobmatchai.backend.service.provider;

import java.time.LocalDateTime;

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

        String industry,

        LocalDateTime publishedAt
) {}
