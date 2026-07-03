package com.jobmatchai.backend.service.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Fetches jobs from the Jooble API (https://jooble.org/api/about), which operates a dedicated
 * Israel job board (il.jooble.org) and accepts a free-text "location" so it returns real
 * listings for Israel. Requires a free API key from jooble.org/api/about, supplied via
 * externaljobs.jooble.api-key (JOOBLE_API_KEY env var).
 */
@Component
public class JoobleJobProvider implements ExternalJobProvider {

    private static final Logger log = LoggerFactory.getLogger(JoobleJobProvider.class);

    @Value("${externaljobs.jooble.api-key:}")
    private String apiKey;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final RestClient restClient = RestClient.builder()
            .baseUrl("https://jooble.org")
            .build();

    @Override
    public List<ExternalJobData> fetchJobs(String keywords, String country, int maxResults) {
        if (apiKey == null || apiKey.isBlank()) {
            return List.of();
        }

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("keywords", keywords == null ? "" : keywords);
            body.put("location", resolveLocation(country));

            String responseBody = restClient.post()
                    .uri("/api/" + apiKey)
                    .header("Content-Type", "application/json")
                    .body(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .body(String.class);

            JsonNode response = responseBody == null ? null : objectMapper.readTree(responseBody);

            if (response == null || !response.has("jobs")) {
                log.warn("Jooble response had no 'jobs' field: {}",
                        responseBody == null ? "null" : responseBody.substring(0, Math.min(200, responseBody.length())));
                return List.of();
            }

            List<ExternalJobData> jobs = new ArrayList<>();
            int count = 0;
            for (JsonNode result : response.get("jobs")) {
                if (count >= maxResults) {
                    break;
                }
                jobs.add(mapResult(result));
                count++;
            }

            return jobs;
        } catch (Exception e) {
            log.warn("Jooble fetch failed: {}", e.getMessage());
            return List.of();
        }
    }

    private String resolveLocation(String country) {
        if (country == null || country.isBlank() || country.equalsIgnoreCase("il")) {
            return "Israel";
        }
        return country;
    }

    private ExternalJobData mapResult(JsonNode result) {
        String applyUrl = textOrNull(result, "link");
        String externalId = textOrNull(result, "id");
        if (externalId == null || externalId.isBlank()) {
            externalId = applyUrl;
        }

        return new ExternalJobData(
                externalId,
                textOrNull(result, "title"),
                textOrNull(result, "company"),
                textOrNull(result, "location"),
                "IL",
                textOrNull(result, "location"),
                textOrNull(result, "type"),
                textOrNull(result, "salary"),
                textOrNull(result, "snippet"),
                null,
                null,
                applyUrl,
                applyUrl,
                joobleSourceLabel(result)
        );
    }

    private String joobleSourceLabel(JsonNode result) {
        String source = textOrNull(result, "source");
        return (source == null || source.isBlank()) ? "Jooble" : source;
    }

    private String textOrNull(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText(null);
    }
}
