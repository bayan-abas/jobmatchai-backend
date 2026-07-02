package com.jobmatchai.backend.service.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Fetches jobs from the JSearch API on RapidAPI (aggregates Google for Jobs, LinkedIn, Indeed
 * and others), which supports country-scoped search including Israel ("il"). Requires a
 * RapidAPI key subscribed to JSearch, supplied via externaljobs.jsearch.api-key (RAPIDAPI_KEY
 * env var) - left unset by default, in which case this provider contributes zero jobs.
 *
 * Note: a plain free-tier RapidAPI JSearch subscription may only grant access to /job-details
 * and /estimated-salary, not this class's /search call (confirmed via live testing - RapidAPI's
 * proxy returns a 404 "Endpoint does not exist" for /search on such keys). If jobs never show up
 * from this provider, check the "Endpoints" tab on your RapidAPI JSearch subscription to confirm
 * /search is actually included before assuming a bug here.
 */
@Component
public class JSearchJobProvider implements ExternalJobProvider {

    @Value("${externaljobs.jsearch.api-key:}")
    private String apiKey;

    @Value("${externaljobs.jsearch.host:jsearch.p.rapidapi.com}")
    private String apiHost;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final RestClient restClient = RestClient.builder()
            .baseUrl("https://jsearch.p.rapidapi.com")
            .build();

    @Override
    public List<ExternalJobData> fetchJobs(String keywords, String country, int maxResults) {
        if (apiKey == null || apiKey.isBlank()) {
            return List.of();
        }

        try {
            String countryCode = (country == null || country.isBlank()) ? "il" : country.toLowerCase();
            String query = (keywords == null || keywords.isBlank()) ? "jobs" : keywords;

            String uri = UriComponentsBuilder
                    .fromPath("/search")
                    .queryParam("query", query + " in Israel")
                    .queryParam("country", countryCode)
                    .queryParam("num_pages", 1)
                    .build()
                    .toUriString();

            String responseBody = restClient.get()
                    .uri(uri)
                    .header("X-RapidAPI-Key", apiKey)
                    .header("X-RapidAPI-Host", apiHost)
                    .retrieve()
                    .body(String.class);

            JsonNode response = responseBody == null ? null : objectMapper.readTree(responseBody);

            if (response == null || !response.has("data")) {
                return List.of();
            }

            List<ExternalJobData> jobs = new ArrayList<>();
            int count = 0;
            for (JsonNode result : response.get("data")) {
                if (count >= maxResults) {
                    break;
                }
                jobs.add(mapResult(result));
                count++;
            }

            return jobs;
        } catch (Exception e) {
            return List.of();
        }
    }

    private ExternalJobData mapResult(JsonNode result) {
        String skills = joinArray(result.path("job_required_skills"));

        return new ExternalJobData(
                textOrNull(result, "job_id"),
                textOrNull(result, "job_title"),
                textOrNull(result, "employer_name"),
                textOrNull(result, "job_city"),
                "IL",
                textOrNull(result, "job_city"),
                textOrNull(result, "job_employment_type"),
                formatSalary(result),
                textOrNull(result, "job_description"),
                null,
                skills,
                textOrNull(result, "job_apply_link"),
                textOrNull(result, "job_apply_link"),
                joinPublishers(result)
        );
    }

    private String joinPublishers(JsonNode result) {
        String publisher = textOrNull(result, "job_publisher");
        return (publisher == null || publisher.isBlank()) ? "JSearch" : publisher;
    }

    private String formatSalary(JsonNode result) {
        double min = result.path("job_min_salary").asDouble(0);
        double max = result.path("job_max_salary").asDouble(0);

        if (min <= 0 && max <= 0) {
            return null;
        }
        if (min > 0 && max > 0 && min != max) {
            return String.format("%.0f - %.0f", min, max);
        }
        return String.format("%.0f", Math.max(min, max));
    }

    private String joinArray(JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray() || arrayNode.isEmpty()) {
            return null;
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : arrayNode) {
            String text = item.asText("").trim();
            if (!text.isBlank()) {
                values.add(text);
            }
        }
        return values.isEmpty() ? null : String.join("|", values);
    }

    private String textOrNull(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText(null);
    }
}
