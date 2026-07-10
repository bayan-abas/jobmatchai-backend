package com.jobmatchai.backend.service.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Fetches remote jobs from the Jobicy API (https://jobicy.com/apidocs) that are open to
 * candidates based in Israel. Jobicy resolves geo=israel server-side into jobs actually
 * eligible for Israel-based remote workers (returned under broader region tags like "EMEA"),
 * so no local eligibility guessing is needed here. No API key required - Jobicy's API is free
 * and public; their only requirement is crediting Jobicy with a link to the original job URL,
 * which ExternalJobCard already does via sourceName/applyUrl.
 *
 * This provider always searches geo=israel regardless of the requested country, since that is
 * its whole purpose - use JoobleJobProvider (or a future provider) for on-site Israel jobs.
 */
@Component
public class JobicyJobProvider implements ExternalJobProvider {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final RestClient restClient = RestClient.builder()
            .baseUrl("https://jobicy.com")
            .build();

    @Override
    public boolean usesKeywords() {
        return false;
    }

    @Override
    public List<ExternalJobData> fetchJobs(String keywords, String country, int maxResults) {
        try {
            String responseBody = restClient.get()
                    .uri("/api/v2/remote-jobs?count={count}&geo=israel", Math.max(1, maxResults))
                    .retrieve()
                    .body(String.class);

            JsonNode response = responseBody == null ? null : objectMapper.readTree(responseBody);

            if (response == null || !response.has("jobs")) {
                return List.of();
            }

            List<ExternalJobData> jobs = new ArrayList<>();
            for (JsonNode result : response.get("jobs")) {
                jobs.add(mapResult(result));
            }

            return jobs;
        } catch (Exception e) {
            return List.of();
        }
    }

    private ExternalJobData mapResult(JsonNode result) {
        String applyUrl = textOrNull(result, "url");
        String id = result.has("id") ? result.get("id").asText(null) : null;

        return new ExternalJobData(
                id,
                textOrNull(result, "jobTitle"),
                textOrNull(result, "companyName"),
                textOrNull(result, "jobGeo"),
                "IL",
                null,
                joinArray(result.path("jobType")),
                formatSalary(result),
                textOrNull(result, "jobExcerpt"),
                null,
                null,
                applyUrl,
                applyUrl,
                "Jobicy",
                resolveIndustry(result.path("jobIndustry"))
        );
    }

    // Jobicy tags every listing with its own category slugs (its "jobIndustry" array - e.g.
    // "dev", "marketing", "customer-service", "finance-legal") - a real, provider-supplied
    // category, not a keyword guess, so it's used directly instead of falling back to
    // title/description inference. Matched by substring against Jobicy's own (small, controlled)
    // slug vocabulary rather than an exact-string dictionary, since the exact slug spelling can
    // vary slightly (e.g. "customer-service" vs "customer_service") - substring matching is safe
    // here specifically because the input is a short controlled tag, not free-text job prose.
    // Deliberately conservative: a tag with no confident, unambiguous mapping (Jobicy's generic
    // "business", "management", "all-others", "wellness" tags cut across multiple of our
    // industries) is left unmapped so the job falls through to title-based classification
    // instead of being guessed into the wrong bucket.
    private String resolveIndustry(JsonNode industryTags) {
        if (industryTags == null || !industryTags.isArray()) {
            return null;
        }

        for (JsonNode tagNode : industryTags) {
            String tag = tagNode.asText("").toLowerCase();
            if (tag.isBlank()) {
                continue;
            }

            if (tag.contains("dev") || tag.contains("data") || tag.contains("engineer")
                    || tag.contains("it-")) {
                return "technology";
            }
            if (tag.contains("design")) {
                return "design";
            }
            if (tag.contains("market")) {
                return "marketing";
            }
            if (tag.contains("customer")) {
                return "customerService";
            }
            if (tag.contains("sale")) {
                return "sales";
            }
            if (tag.contains("hr") || tag.contains("human-resources")) {
                return "humanResources";
            }
            if (tag.contains("legal")) {
                return "legal";
            }
            if (tag.contains("writ") || tag.contains("copywriting")) {
                return "writing";
            }
            if (tag.contains("health") || tag.contains("biotech") || tag.contains("medical")) {
                return "healthcare";
            }
            if (tag.contains("supply-chain") || tag.contains("logistics")) {
                return "logistics";
            }
            if (tag.contains("finance") && !tag.contains("legal")) {
                return "finance";
            }
        }

        return null;
    }

    private String formatSalary(JsonNode result) {
        double min = result.path("salaryMin").asDouble(0);
        double max = result.path("salaryMax").asDouble(0);
        String currency = textOrNull(result, "salaryCurrency");

        if (min <= 0 && max <= 0) {
            return null;
        }

        String amount = (min > 0 && max > 0 && min != max)
                ? String.format("%.0f - %.0f", min, max)
                : String.format("%.0f", Math.max(min, max));

        return currency == null ? amount : amount + " " + currency;
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
        return values.isEmpty() ? null : String.join(", ", values);
    }

    private String textOrNull(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText(null);
    }
}
