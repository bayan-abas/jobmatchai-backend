package com.jobmatchai.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class AIChatService {

    @Value("${openai.api.key}")
    private String apiKey;

    @Value("${openai.model}")
    private String model;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final RestClient restClient = RestClient.builder()
            .baseUrl("https://api.openai.com")
            .build();

    public String chat(String userMessage, String language) {
        try {
            String languageInstruction = switch (language) {
                case "ar" -> "You MUST reply in Arabic only. Every single word of your response must be in Arabic.";
                case "he" -> "You MUST reply in Hebrew only. Every single word of your response must be in Hebrew.";
                default -> "You MUST reply in English only.";
            };

            String systemPrompt = """
You are a helpful career assistant for JobMatchAI, a smart recruitment platform.
Your role is to help job candidates with career advice, resume tips, job search strategies, interview preparation, and professional growth.
Keep your answers concise, friendly, and practical — 2 to 4 sentences maximum.
Do not make up specific job listings or company names.
""" + languageInstruction;

            Map<String, Object> body = Map.of(
                    "model", model,
                    "store", false,
                    "input", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userMessage)
                    )
            );

            Map<String, Object> response = restClient.post()
                    .uri("/v1/responses")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(Objects.requireNonNull(body))
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});

            return extractText(response);

        } catch (Exception e) {
            return switch (language) {
                case "ar" -> "عذرًا، حدث خطأ. يرجى المحاولة مرة أخرى.";
                case "he" -> "מצטער, אירעה שגיאה. אנא נסה שנית.";
                default -> "Sorry, something went wrong. Please try again.";
            };
        }
    }

    private String extractText(Map<String, Object> response) {
        try {
            String json = objectMapper.writeValueAsString(response);
            JsonNode root = objectMapper.readTree(json);
            JsonNode output = root.path("output");
            if (output.isArray() && !output.isEmpty()) {
                JsonNode content = output.get(0).path("content");
                if (content.isArray() && !content.isEmpty()) {
                    return content.get(0).path("text").asText("").trim();
                }
            }
        } catch (Exception ignored) {}
        return "";
    }
}
