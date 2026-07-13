package com.jobmatchai.backend.controller;

import com.jobmatchai.backend.service.SkillExplanationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private static final Logger log = LoggerFactory.getLogger(SkillController.class);

    @Autowired
    private SkillExplanationService skillExplanationService;

    public record ExplainRequest(String skillName, String jobTitle, String language) {}

    @PostMapping("/explain")
    public ResponseEntity<?> explainSkill(@RequestBody ExplainRequest request) {
        try {
            if (request.skillName() == null || request.skillName().isBlank()) {
                return ResponseEntity.badRequest().body("skillName is required");
            }

            Map<String, Object> response = skillExplanationService.getExplanation(
                    request.skillName(), request.jobTitle(), request.language());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to explain skill '{}'", request.skillName(), e);
            return ResponseEntity.internalServerError().body("Failed to explain skill. Please try again.");
        }
    }
}
