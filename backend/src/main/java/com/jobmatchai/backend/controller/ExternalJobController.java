package com.jobmatchai.backend.controller;

import com.jobmatchai.backend.model.ExternalJob;
import com.jobmatchai.backend.service.ExternalJobService;
import com.jobmatchai.backend.service.JobMatchService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/external-jobs")
public class ExternalJobController {

    private static final Logger log = LoggerFactory.getLogger(ExternalJobController.class);

    @Autowired
    private ExternalJobService externalJobService;

    @Value("${app.internal-api-key:}")
    private String internalApiKey;

    private boolean isAuthorizedForInternalOps(String providedKey) {
        return internalApiKey != null && !internalApiKey.isBlank()
                && internalApiKey.equals(providedKey);
    }

    private final ExecutorService streamingExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private static final long SSE_TIMEOUT_MS = 180_000L;

    public record ImportRequest(String keywords, String country) {}

    public record ExternalMatchScoreRequest(String email, List<Long> externalJobIds, String language) {}

    public record ExternalMatchDetailRequest(String email, Long externalJobId, String language) {}

    @GetMapping("/test")
    public String test() {
        return "External Jobs API is working";
    }

    // מחזיר את כל המשרות שנאספו ממקורות חיצוניים להצגה יחד עם המשרות הפנימיות
    @GetMapping("/all")
    public List<ExternalJob> getAllExternalJobs() {
        return externalJobService.getAllExternalJobs();
    }

    // מחזיר משרה חיצונית בודדת יחד עם תקציר "about" שנוצר או נשלף מהקאש
    @GetMapping("/{id}")
    public Map<String, Object> getExternalJobById(
            @PathVariable long id, @RequestParam(defaultValue = "en") String language) {
        Map<String, Object> response = new HashMap<>();

        return externalJobService.getExternalJobById(id)
                .map(job -> {
                    response.put("success", true);
                    response.put("job", job);

                    try {
                        response.put("aboutSummary", externalJobService.getOrGenerateAboutSummary(id, language));
                    } catch (Exception e) {
                        response.put("aboutSummary", null);
                    }
                    return response;
                })
                .orElseGet(() -> {
                    response.put("success", false);
                    response.put("message", "External job not found");
                    return response;
                });
    }

    // מייבא משרות חדשות ממקור חיצוני; פעולה פנימית המוגנת במפתח API ולא פתוחה למשתמשי הקצה
    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importJobs(
            @RequestBody(required = false) ImportRequest request,
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String providedKey) {
        Map<String, Object> response = new HashMap<>();

        if (!isAuthorizedForInternalOps(providedKey)) {
            response.put("success", false);
            response.put("message", "Not authorized to trigger an external job import.");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
        }

        try {
            String keywords = request == null ? null : request.keywords();
            String country = request == null ? null : request.country();

            ExternalJobService.ImportResult result = externalJobService.importJobs(keywords, country);

            response.put("success", true);
            response.put("imported", result.imported());
            response.put("skipped", result.skipped());
            response.put("total", result.total());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("External job import failed (keywords='{}', country='{}')",
                    request == null ? null : request.keywords(), request == null ? null : request.country(), e);
            response.put("success", false);
            response.put("message", "Import failed. Please try again.");
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // משלים תוכן חסר במשרות חיצוניות קיימות; פעולת תחזוקה פנימית מוגנת במפתח API
    @PostMapping("/backfill-content")
    public ResponseEntity<Map<String, Object>> backfillContent(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String providedKey) {
        Map<String, Object> response = new HashMap<>();

        if (!isAuthorizedForInternalOps(providedKey)) {
            response.put("success", false);
            response.put("message", "Not authorized to trigger a content backfill.");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
        }

        try {
            ExternalJobService.BackfillResult result = externalJobService.backfillMissingContent();

            response.put("success", true);
            response.put("candidatesFound", result.candidatesFound());
            response.put("fullyCompleted", result.fullyCompleted());
            response.put("failures", result.failures());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("External job content backfill failed", e);
            response.put("success", false);
            response.put("message", "Backfill failed. Please try again.");
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // מזהה ומוחק משרות חיצוניות כפולות; פעולת תחזוקה פנימית מוגנת במפתח API
    @PostMapping("/remove-duplicates")
    public ResponseEntity<Map<String, Object>> removeDuplicates(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String providedKey) {
        Map<String, Object> response = new HashMap<>();

        if (!isAuthorizedForInternalOps(providedKey)) {
            response.put("success", false);
            response.put("message", "Not authorized to trigger duplicate removal.");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
        }

        try {
            ExternalJobService.DuplicateCleanupResult result = externalJobService.removeDuplicateExternalJobs();

            response.put("success", true);
            response.put("duplicateGroupsFound", result.duplicateGroupsFound());
            response.put("rowsRemoved", result.rowsRemoved());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("External job duplicate removal failed", e);
            response.put("success", false);
            response.put("message", "Duplicate removal failed. Please try again.");
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // מנקה מחדש את רשימות הכישורים של משרות חיצוניות קיימות; פעולת תחזוקה פנימית מוגנת במפתח API
    @PostMapping("/resanitize-skills")
    public ResponseEntity<Map<String, Object>> resanitizeSkills(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String providedKey) {
        Map<String, Object> response = new HashMap<>();

        if (!isAuthorizedForInternalOps(providedKey)) {
            response.put("success", false);
            response.put("message", "Not authorized to trigger a skills resanitization.");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
        }

        try {
            int changed = externalJobService.resanitizeExistingSkills();

            response.put("success", true);
            response.put("jobsChanged", changed);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("External job skills resanitization failed", e);
            response.put("success", false);
            response.put("message", "Resanitization failed. Please try again.");
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // מחשב ציוני התאמה בין המועמד המחובר לרשימת משרות חיצוניות
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
            log.error("Failed to compute external job match scores for candidate={}", authentication.getName(), e);
            return ResponseEntity.internalServerError().body("Failed to compute match scores. Please try again.");
        }
    }

    // מחזיר סטרים (SSE) של ציוני התאמה למשרות חיצוניות שמתעדכן בזמן אמת
    @PostMapping(path = "/match-scores/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMatchScores(@RequestBody ExternalMatchScoreRequest request, Authentication authentication) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        Object sendLock = new Object();
        String email = authentication.getName();
        List<Long> externalJobIds = request.externalJobIds() == null ? List.of() : request.externalJobIds();

        streamingExecutor.execute(() -> {
            try {
                if (!externalJobService.hasAnalysis(email)) {
                    sendEvent(emitter, sendLock, "no-analysis", Map.of());
                    emitter.complete();
                    return;
                }

                externalJobService.streamMatchScoresForExternalJobs(email, externalJobIds, request.language(),
                        (jobId, payload) -> sendEvent(emitter, sendLock, "score", payload),
                        () -> {
                            sendEvent(emitter, sendLock, "done", Map.of());
                            emitter.complete();
                        });
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    private void sendEvent(SseEmitter emitter, Object lock, String name, Object data) {

        synchronized (lock) {
            try {
                emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                emitter.completeWithError(e);
            }
        }
    }

    // מחזיר ניתוח התאמה מפורט בין המועמד למשרה חיצונית ספציפית
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
            log.error("Failed to compute external job match detail for candidate={} externalJobId={}",
                    authentication.getName(), request.externalJobId(), e);
            return ResponseEntity.internalServerError().body("Failed to compute match detail. Please try again.");
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
        response.put("generalVocationalRole", result.generalVocationalRole());
        response.put("skillsMatchPercent", result.skillsMatchPercent());
        response.put("experienceMatchPercent", result.experienceMatchPercent());
        response.put("educationMatchPercent", result.educationMatchPercent());
        response.put("fieldRelevancePercent", result.fieldRelevancePercent());
        response.put("certificationMatchPercent", result.certificationMatchPercent());
        response.put("locationMatchPercent", result.locationMatchPercent());
        response.put("missingRequiredSkills", result.missingRequiredSkills());
        response.put("missingPreferredSkills", result.missingPreferredSkills());
        response.put("matchedRequiredSkills", result.matchedRequiredSkills());
        response.put("matchedPreferredSkills", result.matchedPreferredSkills());
        response.put("requiredExperienceLevel", result.requiredExperienceLevel());
        response.put("requiredEducationLevel", result.requiredEducationLevel());
        response.put("requiredCertificationLevel", result.requiredCertificationLevel());
        response.put("lastAnalyzedAt", result.lastAnalyzedAt());
        return response;
    }
}
