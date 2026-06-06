package com.jobmatchai.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Service
public class OpenAICVAnalysisService {

    @Value("${openai.api.key}")
    private String apiKey;

    @Value("${openai.model}")
    private String model;

    private final RestClient restClient = RestClient.builder()
            .baseUrl("https://api.openai.com")
            .build();

    @SuppressWarnings("null")
    public String analyzeCV(String cvText) {

        try {
            String safeCvText = cvText;

            if (safeCvText != null && safeCvText.length() > 12000) {
                safeCvText = safeCvText.substring(0, 12000);
            }

String prompt = """
Return ONLY a raw JSON object. No markdown. No titles. No explanations.

The JSON must be exactly:
{
  "skills": "",
  "summary": "",
  "strengths": "",
  "missingSkills": "",
  "recommendedRoles": ""
}

All fields must be strings. Never return null.

CV Text:
""" + safeCvText;

            Map<String, Object> body = Map.of(
                    "model", model,
                    "input", prompt,
                    "store", false,
                    "text", Map.of("format", Map.of("type", "json_object"))
            );

            Map<String, Object> response = restClient.post()
                    .uri("/v1/responses")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});

            if (response != null && response.containsKey("output")) {
                Object outputObj = response.get("output");

                if (outputObj instanceof List<?> outputList && !outputList.isEmpty()) {
                    Object firstItem = outputList.get(0);

                    if (firstItem instanceof Map<?, ?> messageMap && messageMap.containsKey("content")) {
                        Object contentObj = messageMap.get("content");

                        if (contentObj instanceof List<?> contentList && !contentList.isEmpty()) {
                            Object contentItem = contentList.get(0);

                            if (contentItem instanceof Map<?, ?> contentMap && contentMap.containsKey("text")) {
                                Object text = contentMap.get("text");

                                if (text != null && !text.toString().trim().isEmpty()) {
                                    return text.toString()
                                            .trim()
                                            .replace("```json", "")
                                            .replace("```", "")
                                            .trim();
                                }
                            }
                        }
                    }
                }
            }

            return "{\"skills\":\"\",\"summary\":\"Unable to extract analysis from OpenAI response.\",\"strengths\":\"\",\"missingSkills\":\"\",\"recommendedRoles\":\"\"}";

        } catch (HttpClientErrorException e) {
            return "{\"skills\":\"\",\"summary\":\"OpenAI API Error: " + e.getStatusCode() + "\",\"strengths\":\"\",\"missingSkills\":\"\",\"recommendedRoles\":\"\"}";
        } catch (Exception e) {
            return "{\"skills\":\"\",\"summary\":\"Error analyzing CV: " + e.getMessage() + "\",\"strengths\":\"\",\"missingSkills\":\"\",\"recommendedRoles\":\"\"}";
        }
    }
}