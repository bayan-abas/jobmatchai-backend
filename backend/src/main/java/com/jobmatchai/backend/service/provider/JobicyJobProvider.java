package com.jobmatchai.backend.service.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.HtmlUtils;

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

    private final RestClient restClient = ExternalJobRestClients.timeoutBuilder()
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
                resolveDescription(result),
                null,
                null,
                applyUrl,
                applyUrl,
                "Jobicy",
                resolveIndustry(result.path("jobIndustry")),
                ExternalDateParser.parse(textOrNull(result, "pubDate"))
        );
    }

    // Jobicy's "jobExcerpt" is a short marketing teaser (a few hundred characters - "About
    // Company X, we're solving..."), never the posting's actual requirements/duties. The API
    // separately returns "jobDescription", the full posting as HTML (often 5,000-10,000+
    // characters, containing the real "Requirements"/"What you'll do" sections) - found via
    // live testing to be the actual reason almost every external job was scoring the same flat
    // ~55%: with only the teaser text available, the AI had nothing concrete to extract
    // required skills/experience/education from, so every component except field relevance came
    // back null and the score collapsed to field-relevance-alone. Preferring jobDescription
    // (stripped to plain text) here gives the matching AI the same real signal a human reading
    // the posting would have. Falls back to jobExcerpt only if jobDescription is missing/blank.
    private String resolveDescription(JsonNode result) {
        String html = textOrNull(result, "jobDescription");
        String plainText = html == null ? null : htmlToPlainText(html);

        if (plainText != null && !plainText.isBlank()) {
            // The column is now an unbounded TEXT (match scoring needs the COMPLETE posting,
            // not a truncated excerpt - see ExternalJob#description) - this cap is a defensive
            // ceiling against a pathologically huge response, not a normal-case truncation. Real
            // Jobicy postings observed in testing top out well under this (~10,540 raw HTML,
            // a few thousand plain-text characters after stripping), so it should essentially
            // never actually trigger.
            return plainText.length() > 20_000 ? plainText.substring(0, 20_000) : plainText;
        }

        return textOrNull(result, "jobExcerpt");
    }

    private String htmlToPlainText(String html) {
        String withBreaks = html.replaceAll("(?is)<(br|/p|/div|/li|/h[1-6])\\s*/?>", "\n");
        String stripped = withBreaks.replaceAll("(?s)<[^>]+>", " ");
        String unescaped = HtmlUtils.htmlUnescape(stripped);
        return unescaped.replaceAll("[ \\t]+", " ").replaceAll("\\n\\s*\\n+", "\n").trim();
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
