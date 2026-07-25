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

@Component
public class JoobleJobProvider implements ExternalJobProvider {

    private static final Logger log = LoggerFactory.getLogger(JoobleJobProvider.class);

    @Value("${externaljobs.jooble.api-key:}")
    private String apiKey;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final RestClient restClient = ExternalJobRestClients.timeoutBuilder()
            .baseUrl("https://jooble.org")
            .build();

    // שולח בקשת POST ל-API של Jooble עם מילות מפתח ומיקום, ומחזיר עד maxResults משרות ממופות למודל הפנימי
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

            boolean israelScoped = "Israel".equals(resolveLocation(country));

            List<ExternalJobData> jobs = new ArrayList<>();
            int count = 0;
            for (JsonNode result : response.get("jobs")) {
                if (count >= maxResults) {
                    break;
                }
                jobs.add(mapResult(result, israelScoped));
                count++;
            }

            return jobs;
        } catch (Exception e) {
            log.warn("Jooble fetch failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ממיר קוד מדינה למחרוזת מיקום שה-API של Jooble מבין, וברירת המחדל היא ישראל
    private String resolveLocation(String country) {
        if (country == null || country.isBlank() || country.equalsIgnoreCase("il")) {
            return "Israel";
        }
        return country;
    }

    // ממיר משרה בודדת מהפורמט של Jooble למודל הפנימי ExternalJobData
    private ExternalJobData mapResult(JsonNode result, boolean israelScoped) {
        String applyUrl = textOrNull(result, "link");
        String externalId = textOrNull(result, "id");
        if (externalId == null || externalId.isBlank()) {
            externalId = applyUrl;
        }
        String rawLocation = textOrNull(result, "location");
        // Jooble לפעמים מחזיר עיר בלי "Israel" כשמחפשים כאן - מוסיפים ידנית כדי שהסינון לפי מדינה בהמשך לא יפספס את המשרה
        String location = (israelScoped && (rawLocation == null || !rawLocation.toLowerCase().contains("israel")))
                ? (rawLocation == null ? "Israel" : rawLocation + ", Israel")
                : rawLocation;

        return new ExternalJobData(
                externalId,
                textOrNull(result, "title"),
                textOrNull(result, "company"),
                location,
                "IL",
                location,
                textOrNull(result, "type"),
                textOrNull(result, "salary"),
                textOrNull(result, "snippet"),
                null,
                null,
                applyUrl,
                applyUrl,
                joobleSourceLabel(result),

                null,
                ExternalDateParser.parse(textOrNull(result, "updated"))
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
