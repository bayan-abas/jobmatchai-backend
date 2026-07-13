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

// Backs the generic, keyword-free pre-filter in JobMatchService: a cheap, deterministic,
// purely arithmetic (no per-request AI call) semantic-similarity gate computed from OpenAI
// embeddings, so "is this job even worth an AI classification call" never depends on matching
// job-title/skill substrings against a hand-maintained keyword list - the exact kind of
// heuristic that caused two real scoring bugs earlier this session (a job's own bad "skills"
// text misleading the model, and a "delivery" substring false-matching a Director-level role).
//
// Every public method here fails OPEN, never closed: any error (missing key, network failure,
// malformed response, request timeout) returns an empty/null result rather than throwing, so a
// caller that can't get an embedding always falls back to "send this to the AI" - the pre-filter
// can only ever skip an AI call when it has real, positive evidence of low similarity, never as
// a side effect of an embeddings-API hiccup.
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    @Value("${openai.api.key:}")
    private String apiKey;

    @Value("${openai.embedding-model:text-embedding-3-small}")
    private String embeddingModel;

    // A shortened, still-unit-normalized vector (OpenAI's Matryoshka-representation support for
    // the text-embedding-3-* family) - this is a coarse "same field or not" gate, not a
    // fine-grained search-ranking use case, so the default 1536 dimensions would only cost more
    // storage/compute for no real gain here.
    @Value("${openai.embedding-dimensions:512}")
    private int embeddingDimensions;

    @Autowired
    @Qualifier("openAIRestClient")
    private RestClient restClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Returns one float[] per input text, in the SAME order as `texts`. Never throws - returns
    // an empty list on any failure (missing key, HTTP error, malformed response), which every
    // caller must treat as "no embedding available", never as "these are unrelated".
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

            // Response order is not guaranteed to match input order - each item carries its own
            // "index", so size the result array up front and place each vector by that index
            // rather than assuming positional order.
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

    // Full formula (dot / (|a|*|b|)) rather than assuming pre-normalized input - OpenAI
    // embeddings are documented as unit-normalized (including the shortened `dimensions` form),
    // but computing it properly costs nothing and stays correct even if that ever changes.
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

    // Lazy + fingerprinted, same shape as JobMatchService#fingerprintCv: only calls the
    // embeddings API the first time a given candidate profile is matched against anything, or
    // after the profile text actually changes (new CV analysis) or the embedding model/dimension
    // config changes - every request after that is a pure cache hit, zero API calls.
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
