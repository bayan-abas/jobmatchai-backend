package com.jobmatchai.backend.service.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;

@Component
public class JSearchJobProvider implements ExternalJobProvider {

    @Value("${externaljobs.jsearch.api-key:}")
    private String apiKey;

    @Value("${externaljobs.jsearch.host:jsearch.p.rapidapi.com}")
    private String apiHost;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final RestClient restClient = ExternalJobRestClients.timeoutBuilder()
            .baseUrl("https://jsearch.p.rapidapi.com")
            .build();

    // שולח בקשת חיפוש ל-API של JSearch (דרך RapidAPI) ומחזיר עד maxResults משרות ממופות למודל הפנימי
    @Override
    public List<ExternalJobData> fetchJobs(String keywords, String country, int maxResults) {
        if (apiKey == null || apiKey.isBlank()) {
            return List.of();
        }

        try {
            String countryCode = (country == null || country.isBlank()) ? "il" : country.toLowerCase();
            String query = (keywords == null || keywords.isBlank()) ? "jobs" : keywords;

            // בלי "in Israel" בשאילתה ה-API מחזיר גם המון תוצאות מארה"ב
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
            // אם הספק הזה נופל לא רוצים להפיל את כל חיפוש המשרות, פשוט מחזירים ריק
            return List.of();
        }
    }

    // ממיר משרה בודדת מהפורמט של JSearch למודל הפנימי ExternalJobData
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
                resolveIndustry(result),
                ExternalDateParser.parse(textOrNull(result, "job_posted_at_datetime_utc"))
        );
    }

    // מנחש קטגוריית תעשייה מקוד ה-SOC של המשרה (JSearch לא מחזיר תחום תעשייה ישיר)
    private String resolveIndustry(JsonNode result) {
        String socCode = textOrNull(result, "job_onet_soc");
        if (socCode == null || socCode.isBlank()) {
            return null;
        }

        // ה-2 ספרות לפני המקף בקוד ה-SOC (מיון תעסוקות אמריקאי) מזהות את הקטגוריה המקצועית
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

    // מרכיב מחרוזת שכר קריאה מטווח min/max, או מציג ערך יחיד אם שניהם זהים
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

    // הופך מערך JSON (כמו רשימת כישורים נדרשים) למחרוזת אחת מופרדת ב-"|"
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
