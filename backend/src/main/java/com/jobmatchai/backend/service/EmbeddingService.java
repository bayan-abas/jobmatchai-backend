package com.jobmatchai.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.jobmatchai.backend.model.CVAnalysis;
import com.jobmatchai.backend.repository.CVAnalysisRepository;
import com.jobmatchai.backend.util.HashUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    @Value("${openai.api.key:}")
    private String apiKey;

    @Value("${openai.embedding-model:text-embedding-3-small}")
    private String embeddingModel;

    @Value("${openai.embedding-dimensions:512}")
    private int embeddingDimensions;

    @Autowired
    @Qualifier("openAIRestClient")
    private RestClient restClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // שולח batch של טקסטים ל-OpenAI ומחזיר את וקטורי ה-embedding שלהם, מסודרים לפי סדר הקלט המקורי
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty() || apiKey == null || apiKey.isBlank()) {
            return List.of();
        }

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", embeddingModel);
            body.put("dimensions", embeddingDimensions);
            ArrayNode input = body.putArray("input");
            texts.forEach(input::add);

            String responseBody = restClient.post()
                    .uri("/v1/embeddings")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .body(String.class);

            JsonNode response = responseBody == null ? null : objectMapper.readTree(responseBody);
            if (response == null || !response.has("data") || !response.get("data").isArray()) {
                log.warn("Embeddings response had no 'data' array: {}",
                        responseBody == null ? "null" : responseBody.substring(0, Math.min(200, responseBody.length())));
                return List.of();
            }

            // OpenAI לא מבטיח שהתשובות יחזרו באותו סדר של הקלט, לכן ממפים לפי שדה index
            float[][] ordered = new float[texts.size()][];
            for (JsonNode item : response.get("data")) {
                int index = item.path("index").asInt(-1);
                JsonNode embeddingNode = item.get("embedding");
                if (index < 0 || index >= ordered.length || embeddingNode == null || !embeddingNode.isArray()) {
                    continue;
                }
                float[] vector = new float[embeddingNode.size()];
                for (int i = 0; i < vector.length; i++) {
                    vector[i] = (float) embeddingNode.get(i).asDouble();
                }
                ordered[index] = vector;
            }

            List<float[]> result = new ArrayList<>();
            for (float[] vector : ordered) {
                if (vector == null) {
                    // עדיף לזרוק את כל ה-batch מאשר להחזיר embeddings חלקיים שלא מסונכרנים עם הטקסטים
                    return List.of();
                }
                result.add(vector);
            }
            return result;
        } catch (Exception e) {
            log.warn("Embedding request failed: {}", e.getMessage());
            return List.of();
        }
    }

    public float[] embedSingle(String text) {
        List<float[]> result = embedBatch(List.of(text));
        return result.isEmpty() ? null : result.get(0);
    }

    // מחשב את דמיון הקוסינוס בין שני וקטורי embedding - זה הבסיס למדידת קרבה סמנטית בין קו"ח למשרה
    public static float cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0f;
        }

        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        if (normA == 0 || normB == 0) {
            return 0f;
        }

        return (float) (dot / (Math.sqrt(normA) * Math.sqrt(normB)));
    }

    public String toJson(float[] vector) {
        try {
            return objectMapper.writeValueAsString(vector);
        } catch (Exception e) {
            return null;
        }
    }

    public float[] fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, float[].class);
        } catch (Exception e) {
            return null;
        }
    }

    public String modelKey() {
        return embeddingModel + "@" + embeddingDimensions;
    }

    // מחזיר embedding קיים אם הפרופיל לא השתנה (לפי hash), ורק אם צריך מחשב מחדש ושומר - חוסך קריאות API מיותרות
    public float[] ensureProfileEmbedding(CVAnalysis analysis, CVAnalysisRepository cvAnalysisRepository) {
        String inputText = buildProfileEmbeddingText(analysis);
        if (inputText.isBlank()) {
            return null;
        }

        String hash = HashUtil.sha256(inputText);
        String modelKey = modelKey();

        if (hash.equals(analysis.getProfileEmbeddingHash())
                && modelKey.equals(analysis.getProfileEmbeddingModel())
                && analysis.getProfileEmbedding() != null) {
            return fromJson(analysis.getProfileEmbedding());
        }

        float[] vector = embedSingle(inputText);
        if (vector == null) {
            return null;
        }

        analysis.setProfileEmbedding(toJson(vector));
        analysis.setProfileEmbeddingHash(hash);
        analysis.setProfileEmbeddingModel(modelKey);
        cvAnalysisRepository.save(analysis);
        return vector;
    }

    // בונה את הטקסט המייצג את פרופיל המועמד שעליו מחושב ה-embedding (תפקיד, תחום, מיומנויות, תקציר)
    private String buildProfileEmbeddingText(CVAnalysis analysis) {
        return String.join(". ",
                nullToEmpty(analysis.getProfessionTitle()),
                nullToEmpty(analysis.getCandidateField()),
                "Skills: " + nullToEmpty(analysis.getTechnicalSkills()) + ", " + nullToEmpty(analysis.getSoftSkills()),
                nullToEmpty(analysis.getSummary())
        ).trim();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
