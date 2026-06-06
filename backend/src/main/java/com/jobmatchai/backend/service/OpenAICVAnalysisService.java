package com.jobmatchai.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;

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

    @SuppressWarnings("unchecked")
    public String analyzeCV(String cvText) {

        try {
            String prompt = """
                    Analyze this CV and return the result in this format:

                    Skills:
                    Summary:
                    Strengths:
                    Missing Skills:
                    Recommended Roles:

                    CV Text:
                    """ + cvText;

            Map<String, Object> body = Map.of(
                    "model", model,
                    "input", prompt,
                    "store", true);

            System.out.println("Calling OpenAI API with model: " + model);
            System.out.println("Endpoint: https://api.openai.com/v1/responses");

            Map<String, Object> response = restClient.post()
                    .uri("/v1/responses")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });

            System.out.println("OpenAI Response received");

            // Extract text from the response
            // Structure: output[0] -> {type: message, content: [{type: output_text, text: "..."}]}
            if (response != null && response.containsKey("output")) {
                Object outputObj = response.get("output");

                if (outputObj instanceof List) {
                    List<?> outputList = (List<?>) outputObj;
                    if (!outputList.isEmpty()) {
                        Object firstItem = outputList.get(0);

                        if (firstItem instanceof Map) {
                            Map<String, Object> messageMap = (Map<String, Object>) firstItem;
                            // The message has a "content" field which is an array
                            if (messageMap.containsKey("content")) {
                                Object contentObj = messageMap.get("content");
                                if (contentObj instanceof List) {
                                    List<?> contentList = (List<?>) contentObj;
                                    if (!contentList.isEmpty()) {
                                        Object contentItem = contentList.get(0);
                                        if (contentItem instanceof Map) {
                                            Map<String, Object> contentMap = (Map<String, Object>) contentItem;
                                            if (contentMap.containsKey("text")) {
                                                Object text = contentMap.get("text");
                                                if (text != null && !text.toString().trim().isEmpty()) {
                                                    return text.toString().trim();
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            return "Unable to extract analysis from OpenAI response.";
        } catch (HttpClientErrorException e) {
            System.err.println("OpenAI API HTTP Error: " + e.getStatusCode() + " - " + e.getMessage());
            System.err.println("Response body: " + e.getResponseBodyAsString());
            return "OpenAI API Error: " + e.getStatusCode() + " - " + e.getStatusText();
        } catch (Exception e) {
            System.err.println("Error in OpenAI API call: " + e.getClass().getName() + " - " + e.getMessage());
            e.printStackTrace();
            return "Error analyzing CV: " + e.getMessage();
        }
    }
}