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

    // There's no admin role in this app, and importJobs fans out to paid third-party APIs
    // (Jooble/JSearch/Jobicy) plus OpenAI embedding calls for every changed job - reachable by
    // ANY authenticated candidate or company account otherwise (the scheduled cron in
    // ExternalJobService already covers the intended, unattended use). Gated behind a
    // separately-configured secret rather than a user role so it stays operable for manual
    // triggering (e.g. by whoever operates this deployment) without trusting the app's own
    // candidate/company auth for an operation that costs real money. Closed by default - an
    // unset INTERNAL_API_KEY means this endpoint always rejects, not "open to everyone."
    @Value("${app.internal-api-key:}")
    private String internalApiKey;

    private boolean isAuthorizedForInternalOps(String providedKey) {
        return internalApiKey != null && !internalApiKey.isBlank()
                && internalApiKey.equals(providedKey);
    }

    // One virtual thread per open SSE connection - cheap, and matches the concurrency style
    // JobMatchService already uses for its own internal per-job AI call parallelism.
    private final ExecutorService streamingExecutor = Executors.newVirtualThreadPerTaskExecutor();

    // Hard backstop if a client goes away mid-stream without cleanly aborting (dropped mobile
    // connection, etc.) - independent of, and much larger than, the per-AI-call timeout
    // configured on the OpenAI RestClient (see OpenAIRestClientConfig), which bounds each job's
    // own call, not the whole multi-job stream.
    private static final long SSE_TIMEOUT_MS = 180_000L;

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
    public Map<String, Object> getExternalJobById(
            @PathVariable long id, @RequestParam(defaultValue = "en") String language) {
        Map<String, Object> response = new HashMap<>();

        return externalJobService.getExternalJobById(id)
                .map(job -> {
                    response.put("success", true);
                    response.put("job", job);
                    // Lazily generated/cached (see getOrGenerateAboutSummary) - a structured
                    // AI summary of the full description for the frontend's "About this job"
                    // section, never used for match scoring (that always reads job.description
                    // directly, in full). Best-effort: a summary failure must never take down
                    // the job-details page itself, since the raw description is still present
                    // in `job` as a fallback.
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

    // Progressive counterpart of /match-scores: opens immediately, emits one "score" event per
    // job as soon as its result is known (cache hit, pre-filter skip, or AI verdict), then a
    // final "done" event. Consumed via an authenticated fetch() + manual SSE-frame parsing on
    // the frontend, NOT the native EventSource API - EventSource cannot send the Authorization
    // header this app's JWT auth requires (the same class of bug already found and fixed once
    // this session for the "View CV" button, which hit the identical limitation with
    // window.open()). POST (not GET) is what lets the job-id list travel in the body instead of
    // a query string, and mirrors the existing synchronous endpoint's contract - no new DTO.
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
        // SseEmitter.send is documented as not safe for concurrent calls from multiple threads -
        // every job's own async completion callback sends independently, so this lock is load-
        // bearing, not defensive-for-show.
        synchronized (lock) {
            try {
                emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                emitter.completeWithError(e);
            }
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
