package com.jobmatchai.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.jobmatchai.backend.model.SkillExplanation;
import com.jobmatchai.backend.repository.SkillExplanationRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SkillExplanationService {

    @Autowired
    private SkillExplanationRepository skillExplanationRepository;

    @Autowired
    private OpenAICVAnalysisService openAICVAnalysisService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public Map<String, Object> getExplanation(String skillName, String jobTitle, String language) {
        String key = skillName.trim().toLowerCase();
        String lang = (language == null || language.isBlank()) ? "en" : language;

        SkillExplanation cached = skillExplanationRepository
                .findBySkillNameLowerAndLanguage(key, lang)
                .orElse(null);

        if (cached == null) {
            String result = openAICVAnalysisService.explainSkill(skillName, jobTitle, lang);
            JsonNode json = readObject(result);

            SkillExplanation row = new SkillExplanation();
            row.setSkillNameLower(key);
            row.setLanguage(lang);
            row.setSkillNameDisplay(skillName);
            row.setWhyImportant(json.path("whyImportant").asText(""));
            row.setWhereUsed(joinArray(json.path("whereUsed")));
            row.setRecommendedResources(joinArray(json.path("recommendedResources")));
            row.setLearningTips(joinArray(json.path("learningTips")));

            cached = skillExplanationRepository.save(row);
        }

        return toResponse(cached);
    }

    private Map<String, Object> toResponse(SkillExplanation row) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("skillName", row.getSkillNameDisplay());
        response.put("whyImportant", row.getWhyImportant());
        response.put("whereUsed", splitString(row.getWhereUsed()));
        response.put("recommendedResources", splitString(row.getRecommendedResources()));
        response.put("learningTips", splitString(row.getLearningTips()));
        return response;
    }

    private JsonNode readObject(String result) {
        try {
            return objectMapper.readTree(result);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private String joinArray(JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray()) {
            return "";
        }

        List<String> items = new ArrayList<>();
        for (JsonNode item : arrayNode) {
            String text = item.asText("").trim();
            if (!text.isBlank()) {
                items.add(text);
            }
        }

        return String.join("|", items);
    }

    private List<String> splitString(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }

        return List.of(value.split("\\|"));
    }
}
