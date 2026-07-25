package com.jobmatchai.backend.service.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.HtmlUtils;

import java.util.ArrayList;
import java.util.List;

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

    // מביא משרות remote מתוך ישראל מה-API של Jobicy (הספק הזה לא תומך בחיפוש לפי מילות מפתח)
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

    // ממיר משרה בודדת מהפורמט של Jobicy למודל הפנימי ExternalJobData
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

    // מנקה את תיאור המשרה מ-HTML וחותך אותו לאורך סביר, ונופל חזרה לתקציר אם אין תיאור מלא
    private String resolveDescription(JsonNode result) {
        String html = textOrNull(result, "jobDescription");
        String plainText = html == null ? null : htmlToPlainText(html);

        if (plainText != null && !plainText.isBlank()) {

            return plainText.length() > 20_000 ? plainText.substring(0, 20_000) : plainText;
        }

        return textOrNull(result, "jobExcerpt");
    }

    // הופך HTML לטקסט רגיל בעזרת regex פשוט - לא רוצים תלות בספריית parsing רק בשביל זה
    private String htmlToPlainText(String html) {
        String withBreaks = html.replaceAll("(?is)<(br|/p|/div|/li|/h[1-6])\\s*/?>", "\n");
        String stripped = withBreaks.replaceAll("(?s)<[^>]+>", " ");
        String unescaped = HtmlUtils.htmlUnescape(stripped);
        return unescaped.replaceAll("[ \\t]+", " ").replaceAll("\\n\\s*\\n+", "\n").trim();
    }

    // ממפה את תגיות התעשייה החופשיות של Jobicy לקטגוריות התעשייה הקבועות של האפליקציה
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

    // מרכיב מחרוזת שכר קריאה מטווח min/max יחד עם המטבע
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

    // הופך מערך JSON (כמו סוגי משרה) למחרוזת אחת מופרדת בפסיקים
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
