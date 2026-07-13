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
                jobs.add(mapResult(result, countryCode));
                count++;
            }

            return jobs;
        } catch (Exception e) {
            return List.of();
        }
    }

    // ExternalJobService.isIsraelOrRemote decides whether a job is even shown to candidates by
    // checking whether its location/type text literally contains "israel" or "remote" - it only
    // trusts Jobicy's sourceName outright because Jobicy always sets sourceName to the literal
    // string "Jobicy". JSearch's sourceName is instead the underlying job board (LinkedIn,
    // Indeed, ...) via joinPublishers below, so it can't be trusted the same way. Since this
    // query was ALREADY server-side scoped to Israel (query text "in Israel" + country=il above)
    // whenever countryCode is "il", appending "Israel" onto the location text here is what lets
    // isIsraelOrRemote's existing substring check pass honestly instead of silently dropping
    // every on-site Israel job this provider returns (job_city alone, e.g. "Tel Aviv", never
    // contains the word "israel").
    private ExternalJobData mapResult(JsonNode result, String countryCode) {
        String skills = joinArray(result.path("job_required_skills"));
        String city = textOrNull(result, "job_city");
        String location = "il".equalsIgnoreCase(countryCode)
                ? (city == null ? "Israel" : city + ", Israel")
                : city;

        return new ExternalJobData(
                textOrNull(result, "job_id"),
                textOrNull(result, "job_title"),
                textOrNull(result, "employer_name"),
                location,
                "IL",
                city,
                textOrNull(result, "job_employment_type"),
                formatSalary(result),
                textOrNull(result, "job_description"),
                null,
                skills,
                textOrNull(result, "job_apply_link"),
                textOrNull(result, "job_apply_link"),
                joinPublishers(result),
                resolveIndustry(result)
        );
    }

    // JSearch results can include an O*NET-SOC occupation code (job_onet_soc, e.g.
    // "15-1252.00") when the underlying listing has one - this is a real, standardized U.S.
    // Department of Labor occupation classification, not a keyword guess, so a confidently
    // mappable major group is used directly instead of falling back to title/description
    // inference. Only the major group (the two digits before the first hyphen) is used, and
    // only for groups that map cleanly to exactly one of our industries - several SOC major
    // groups (11 Management, 13 Business/Financial, 19 Science, 21 Community/Social Service,
    // 27 Arts/Media/Design, 39 Personal Care) are deliberately left unmapped because they cut
    // across multiple of our industries too broadly to guess confidently (e.g. group 11
    // contains both "Marketing Managers" and "Construction Managers") - those fall through to
    // title-based classification instead, where the job's own title/description words settle it
    // properly. This field may not be present on every result (or at all, depending on API
    // plan/response variant); resolveIndustry simply returns null when it's missing, which is
    // exactly the signal the frontend classifier needs to fall back to title-based inference.
    private String resolveIndustry(JsonNode result) {
        String socCode = textOrNull(result, "job_onet_soc");
        if (socCode == null || socCode.isBlank()) {
            return null;
        }

        int hyphenIndex = socCode.indexOf('-');
        String majorGroup = hyphenIndex > 0 ? socCode.substring(0, hyphenIndex) : socCode;

        return switch (majorGroup) {
            case "15" -> "technology";
            case "17" -> "engineering";
            case "23" -> "legal";
            case "25" -> "education";
            case "29", "31" -> "healthcare";
            case "33" -> "security";
            case "35" -> "restaurants";
            case "37" -> "cleaning";
            case "41" -> "sales";
            case "43" -> "administration";
            case "45" -> "agriculture";
            case "47", "49" -> "construction";
            case "51" -> "factory";
            case "53" -> "logistics";
            case "55" -> "security";
            default -> null;
        };
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
