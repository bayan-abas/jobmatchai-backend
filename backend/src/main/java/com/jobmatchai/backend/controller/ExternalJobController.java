package com.jobmatchai.backend.controller;

import com.jobmatchai.backend.model.ExternalJob;
import com.jobmatchai.backend.service.ExternalJobService;
import com.jobmatchai.backend.service.JobMatchService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/external-jobs")
public class ExternalJobController {

    @Autowired
    private ExternalJobService externalJobService;

    public record ImportRequest(String keywords, String country) {}

    public record ExternalMatchScoreRequest(String email, List<Long> externalJobIds, String language) {}

    public record ExternalMatchDetailRequest(String email, Long externalJobId, String language) {}

    @GetMapping("/test")
    public String test() {
        return "External Jobs API is working";
    }

    @GetMapping("/all")
    public List<ExternalJob> getAllExternalJobs() {
        return externalJobService.getAllExternalJobs();
    }

    @GetMapping("/{id}")
    public Map<String, Object> getExternalJobById(@PathVariable long id) {
        Map<String, Object> response = new HashMap<>();

        return externalJobService.getExternalJobById(id)
                .map(job -> {
                    response.put("success", true);
                    response.put("job", job);
                    return response;
                })
                .orElseGet(() -> {
                    response.put("success", false);
                    response.put("message", "External job not found");
                    return response;
                });
    }

    @PostMapping("/import")
    public Map<String, Object> importJobs(@RequestBody(required = false) ImportRequest request) {
        Map<String, Object> response = new HashMap<>();

        try {
            String keywords = request == null ? null : request.keywords();
            String country = request == null ? null : request.country();

            ExternalJobService.ImportResult result = externalJobService.importJobs(keywords, country);

            response.put("success", true);
            response.put("imported", result.imported());
            response.put("skipped", result.skipped());
            response.put("total", result.total());

            return response;
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return response;
        }
    }

    @PostMapping("/match-scores")
    public ResponseEntity<?> getMatchScores(@RequestBody ExternalMatchScoreRequest request, Authentication authentication) {
        try {
            List<Long> externalJobIds = request.externalJobIds() == null ? List.of() : request.externalJobIds();

            JobMatchService.MatchScoresResult result = externalJobService.getMatchScoresForExternalJobs(
                    authentication.getName(), externalJobIds, request.language());

            Map<String, Object> response = new HashMap<>();
            response.put("hasAnalysis", result.hasAnalysis());
            response.put("matches", result.matches());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Failed to compute match scores: " + e.getMessage());
        }
    }

    @PostMapping("/match-detail")
    public ResponseEntity<?> getMatchDetail(@RequestBody ExternalMatchDetailRequest request, Authentication authentication) {
        try {
            if (request.externalJobId() == null) {
                return ResponseEntity.badRequest().body("externalJobId is required");
            }

            JobMatchService.MatchDetailResult result = externalJobService.getMatchDetailForExternalJob(
                    authentication.getName(), request.externalJobId(), request.language());

            return ResponseEntity.ok(toMatchDetailResponse(result));

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Failed to compute match detail: " + e.getMessage());
        }
    }

    private Map<String, Object> toMatchDetailResponse(JobMatchService.MatchDetailResult result) {
        Map<String, Object> response = new HashMap<>();
        response.put("hasAnalysis", result.hasAnalysis());
        response.put("jobId", result.jobId());
        response.put("matchPercent", result.matchPercent());
        response.put("matchReason", result.matchReason());
        response.put("matchedSkills", result.matchedSkills());
        response.put("missingSkills", result.missingSkills());
        response.put("whyGoodMatch", result.whyGoodMatch());
        response.put("whyNotPerfectMatch", result.whyNotPerfectMatch());
        response.put("improvementSuggestions", result.improvementSuggestions());
        response.put("recommendation", result.recommendation());
        response.put("shouldApply", result.shouldApply());
        response.put("fieldRelated", result.fieldRelated());
        response.put("skillsMatchPercent", result.skillsMatchPercent());
        response.put("experienceMatchPercent", result.experienceMatchPercent());
        response.put("educationMatchPercent", result.educationMatchPercent());
        response.put("languageMatchPercent", result.languageMatchPercent());
        response.put("fieldRelevancePercent", result.fieldRelevancePercent());
        response.put("certificationMatchPercent", result.certificationMatchPercent());
        response.put("locationMatchPercent", result.locationMatchPercent());
        response.put("missingRequiredSkills", result.missingRequiredSkills());
        response.put("missingPreferredSkills", result.missingPreferredSkills());
        return response;
    }
}
