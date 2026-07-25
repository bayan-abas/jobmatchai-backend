package com.jobmatchai.backend.controller;

import com.jobmatchai.backend.dto.JobCreateRequest;
import com.jobmatchai.backend.model.Application;
import com.jobmatchai.backend.model.CandidateAiSummary;
import com.jobmatchai.backend.model.Job;
import com.jobmatchai.backend.model.JobMatchScore;
import com.jobmatchai.backend.model.JobStatus;
import com.jobmatchai.backend.repository.ApplicationRepository;
import com.jobmatchai.backend.repository.CandidateAiSummaryRepository;
import com.jobmatchai.backend.repository.JobMatchNarrativeRepository;
import com.jobmatchai.backend.repository.JobMatchScoreRepository;
import com.jobmatchai.backend.repository.JobRepository;
import com.jobmatchai.backend.repository.MatchScoreJobRepository;
import com.jobmatchai.backend.service.JobMatchService;
import com.jobmatchai.backend.util.MatchLabelUtil;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private static final Logger log = LoggerFactory.getLogger(JobController.class);

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private ApplicationRepository applicationRepository;

    @Autowired
    private CandidateAiSummaryRepository candidateAiSummaryRepository;

    @Autowired
    private JobMatchScoreRepository jobMatchScoreRepository;

    @Autowired
    private JobMatchNarrativeRepository jobMatchNarrativeRepository;

    @Autowired
    private MatchScoreJobRepository matchScoreJobRepository;

    @Autowired
    private JobMatchService jobMatchService;

    private final ExecutorService streamingExecutor = Executors.newVirtualThreadPerTaskExecutor();

    // 3 דקות - חייב להספיק גם למקרה שיש הרבה משרות שכולן צריכות קריאת AI טרייה
    private static final long SSE_TIMEOUT_MS = 180_000L;

    public record MatchScoreRequest(String email, List<Long> jobIds, String language) {}

    public record MatchDetailRequest(String email, Long jobId, String language) {}

    public record JobWithApplicantsView(
            Long id,
            String title,
            String companyName,
            String companyEmail,
            String location,
            String type,
            String salary,
            String description,
            String requirements,
            String skills,
            long applicantsCount,
            String createdAt,
            String status
    ) {}

    public record JobStatusUpdateRequest(String status) {}

    public record JobApplicantView(
            Long id,
            Long jobId,
            String jobTitle,
            String candidateName,
            String candidateEmail,
            String status,
            String appliedDate,
            Integer matchPercent,
            String matchLabel,

            boolean hasAiSummary
    ) {}

    public record JobDetailsView(
            Long id,
            String title,
            String companyName,
            String companyEmail,
            String location,
            String type,
            String salary,
            String description,
            String requirements,
            String skills,
            String createdAt,
            long applicantsCount,
            Integer averageMatchScore,
            String status
    ) {}

    @GetMapping("/test")
    public String test() {
        return "Jobs API is working";
    }

    // מחזיר את כל המשרות הפעילות להצגה בעמוד חיפוש המשרות
    @GetMapping("/all")
    public List<Job> getAllJobs() {
        return jobRepository.findByStatus(JobStatus.ACTIVE);
    }

    // מחזיר לחברה המחוברת את כל המשרות שפרסמה כולל מספר המועמדים לכל משרה
    @GetMapping("/company/{companyEmail}")
    public List<JobWithApplicantsView> getJobsByCompanyEmail(Authentication authentication) {
        List<Job> jobs = jobRepository.findByCompanyEmail(authentication.getName());
        List<Long> jobIds = jobs.stream().map(Job::getId).toList();

        Map<Long, Long> applicantCounts = new HashMap<>();
        for (Application application : applicationRepository.findByJobIdIn(jobIds)) {
            applicantCounts.merge(application.getJobId(), 1L, Long::sum);
        }

        return jobs.stream()
                .map(job -> new JobWithApplicantsView(
                        job.getId(),
                        job.getTitle(),
                        job.getCompanyName(),
                        job.getCompanyEmail(),
                        job.getLocation(),
                        job.getType(),
                        job.getSalary(),
                        job.getDescription(),
                        job.getRequirements(),
                        job.getSkills(),
                        applicantCounts.getOrDefault(job.getId(), 0L),
                        job.getCreatedAt() != null ? job.getCreatedAt().toString() : null,
                        job.getStatus().name()
                ))
                .toList();
    }

    // מחזיר לחברה את רשימת המועמדים שהגישו בקשה למשרה, כולל ציון התאמה והאם קיים תקציר AI
    @GetMapping("/{jobId}/applications")
    @PreAuthorize("hasRole('COMPANY')")
    public ResponseEntity<?> getApplicationsForJob(@PathVariable Long jobId, Authentication authentication) {
        Job job = jobRepository.findById(jobId).orElse(null);

        if (job == null || !authentication.getName().equals(job.getCompanyEmail())) {
            return ResponseEntity.status(404).body("Job not found");
        }

        List<Application> applications = applicationRepository.findByJobId(jobId);

        Map<String, JobMatchScore> scoresByEmail = new HashMap<>();

        Map<String, CandidateAiSummary> summariesByEmail = new HashMap<>();
        if (!applications.isEmpty()) {
            List<String> candidateEmails = applications.stream().map(Application::getCandidateEmail).distinct().toList();
            for (JobMatchScore score : jobMatchScoreRepository.findByCandidateEmailInAndJobIdIn(candidateEmails, List.of(jobId))) {
                scoresByEmail.put(score.getCandidateEmail(), score);
            }
            for (CandidateAiSummary summary : candidateAiSummaryRepository.findByCandidateEmailInAndJobIdIn(candidateEmails, List.of(jobId))) {
                summariesByEmail.put(summary.getCandidateEmail(), summary);
            }
        }

        List<JobApplicantView> applicants = applications.stream()
                .map(application -> {
                    JobMatchScore score = scoresByEmail.get(application.getCandidateEmail());
                    Integer matchPercent = score != null ? score.getMatchPercent() : null;
                    boolean hasAiSummary = summariesByEmail.containsKey(application.getCandidateEmail());

                    return new JobApplicantView(
                            application.getId(),
                            application.getJobId(),
                            application.getJobTitle(),
                            application.getCandidateName(),
                            application.getCandidateEmail(),
                            application.getStatus(),
                            application.getAppliedDate(),
                            matchPercent,
                            matchPercent != null ? MatchLabelUtil.fromScore(matchPercent) : null,
                            hasAiSummary
                    );
                })
                .toList();

        return ResponseEntity.ok(applicants);
    }

    // מחזיר לחברה פרטי משרה מלאים כולל ממוצע ציון ההתאמה של כלל המועמדים שהגישו
    @GetMapping("/{id}/company-details")
    @PreAuthorize("hasRole('COMPANY')")
    public ResponseEntity<?> getJobDetailsForCompany(@PathVariable Long id, Authentication authentication) {
        Job job = jobRepository.findById(id).orElse(null);

        if (job == null || !authentication.getName().equals(job.getCompanyEmail())) {
            return ResponseEntity.status(404).body("Job not found");
        }

        List<Application> applications = applicationRepository.findByJobId(id);

        Integer averageMatchScore = null;
        if (!applications.isEmpty()) {
            List<String> candidateEmails = applications.stream().map(Application::getCandidateEmail).distinct().toList();

            List<Integer> scores = jobMatchScoreRepository.findByCandidateEmailInAndJobIdIn(candidateEmails, List.of(id)).stream()
                    .map(JobMatchScore::getMatchPercent)
                    .filter(java.util.Objects::nonNull)
                    .toList();

            if (!scores.isEmpty()) {
                averageMatchScore = (int) Math.round(scores.stream().mapToInt(Integer::intValue).average().orElse(0));
            }
        }

        JobDetailsView view = new JobDetailsView(
                job.getId(),
                job.getTitle(),
                job.getCompanyName(),
                job.getCompanyEmail(),
                job.getLocation(),
                job.getType(),
                job.getSalary(),
                job.getDescription(),
                job.getRequirements(),
                job.getSkills(),
                job.getCreatedAt() != null ? job.getCreatedAt().toString() : null,
                applications.size(),
                averageMatchScore,
                job.getStatus().name()
        );

        return ResponseEntity.ok(view);
    }

    // יוצר משרה חדשה עבור החברה המחוברת ושומר אותה במסד הנתונים
    @PostMapping("/add")
    @PreAuthorize("hasRole('COMPANY')")
    public Map<String, Object> addJob(@Valid @RequestBody JobCreateRequest request, Authentication authentication) {
        Map<String, Object> response = new HashMap<>();

        try {
            Job job = new Job();
            job.setTitle(request.title());
            job.setCompanyName(request.companyName());
            job.setLocation(request.location());
            job.setType(request.type());
            job.setSalary(request.salary());
            job.setDescription(request.description());
            job.setRequirements(request.requirements());
            job.setSkills(request.skills());
            job.setCompanyEmail(authentication.getName());

            Job savedJob = jobRepository.save(job);

            response.put("success", true);
            response.put("message", "Job added successfully");
            response.put("job", savedJob);

            return response;
        } catch (Exception e) {
            log.error("Failed to add job for company={}", authentication.getName(), e);
            response.put("success", false);
            response.put("message", "Failed to add job. Please try again.");
            return response;
        }
    }

    @GetMapping("/{id}")
    public Map<String, Object> getJobById(@PathVariable long id) {
        Map<String, Object> response = new HashMap<>();

        return jobRepository.findById(id)
                .map(job -> {
                    response.put("success", true);
                    response.put("job", job);
                    return response;
                })
                .orElseGet(() -> {
                    response.put("success", false);
                    response.put("message", "Job not found");
                    return response;
                });
    }

    // מעדכן את פרטי המשרה הקיימת, רק אם המבקש הוא החברה שפרסמה אותה
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('COMPANY')")
    public Map<String, Object> updateJob(@PathVariable long id, @RequestBody Job updatedJob, Authentication authentication) {
        Map<String, Object> response = new HashMap<>();

        try {
            Job existingJob = jobRepository.findById(id).orElse(null);

            if (existingJob == null || !authentication.getName().equals(existingJob.getCompanyEmail())) {
                response.put("success", false);
                response.put("message", "Job not found");
                return response;
            }

            return jobRepository.findById(id)
                    .map(job -> {
                        job.setTitle(updatedJob.getTitle());
                        job.setCompanyName(updatedJob.getCompanyName());
                        job.setLocation(updatedJob.getLocation());
                        job.setType(updatedJob.getType());
                        job.setSalary(updatedJob.getSalary());
                        job.setDescription(updatedJob.getDescription());
                        job.setRequirements(updatedJob.getRequirements());
                        job.setSkills(updatedJob.getSkills());

                        Job savedJob = jobRepository.save(job);

                        response.put("success", true);
                        response.put("message", "Job updated successfully");
                        response.put("job", savedJob);

                        return response;
                    })
                    .orElseGet(() -> {
                        response.put("success", false);
                        response.put("message", "Job not found");
                        return response;
                    });

        } catch (Exception e) {
            log.error("Failed to update job id={} for company={}", id, authentication.getName(), e);
            response.put("success", false);
            response.put("message", "Failed to update job. Please try again.");
            return response;
        }
    }

    // סוגר או פותח מחדש משרה לקבלת מועמדויות (ACTIVE/CLOSED)
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('COMPANY')")
    public ResponseEntity<Map<String, Object>> updateJobStatus(
            @PathVariable long id, @RequestBody JobStatusUpdateRequest request, Authentication authentication) {
        Map<String, Object> response = new HashMap<>();

        Job job = jobRepository.findById(id).orElse(null);

        if (job == null || !authentication.getName().equals(job.getCompanyEmail())) {
            response.put("success", false);
            response.put("message", "Job not found");
            return ResponseEntity.status(404).body(response);
        }

        JobStatus newStatus;
        try {
            newStatus = JobStatus.valueOf(
                    request.status() == null ? "" : request.status().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", "Invalid status. Must be ACTIVE or CLOSED.");
            return ResponseEntity.badRequest().body(response);
        }

        job.setStatus(newStatus);
        Job savedJob = jobRepository.save(job);

        response.put("success", true);
        response.put("message", "Job status updated successfully");
        response.put("job", savedJob);
        return ResponseEntity.ok(response);
    }

    // מוחק משרה יחד עם כל ציוני ההתאמה וההסברים שנוצרו עבורה
    @Transactional
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('COMPANY')")
    public Map<String, Object> deleteJob(@PathVariable long id, Authentication authentication) {
        Map<String, Object> response = new HashMap<>();

        try {
            Job existingJob = jobRepository.findById(id).orElse(null);

            if (existingJob == null || !authentication.getName().equals(existingJob.getCompanyEmail())) {
                response.put("success", false);
                response.put("message", "Job not found");
                return response;
            }

            jobMatchScoreRepository.deleteByJobIdIn(List.of(id));
            jobMatchNarrativeRepository.deleteByJobIdIn(List.of(id));
            matchScoreJobRepository.deleteByJobIdInAndJobType(List.of(id), "internal");
            jobRepository.deleteById(id);

            response.put("success", true);
            response.put("message", "Job deleted successfully");
            return response;

        } catch (Exception e) {
            log.error("Failed to delete job id={} for company={}", id, authentication.getName(), e);

            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            response.put("success", false);
            response.put("message", "Failed to delete job. Please try again.");
            return response;
        }
    }

    // מחשב ומחזיר ציוני התאמה בין המועמד המחובר לרשימת משרות שנשלחה
    @PostMapping("/match-scores")
    public ResponseEntity<?> getMatchScores(@RequestBody MatchScoreRequest request, Authentication authentication) {
        try {
            List<Long> jobIds = request.jobIds() == null ? List.of() : request.jobIds();
            List<Job> jobs = jobIds.isEmpty() ? List.of() : jobRepository.findAllById(jobIds);

            JobMatchService.MatchScoresResult result =
                    jobMatchService.getMatchScores(authentication.getName(), jobs, request.language());

            Map<String, Object> response = new HashMap<>();
            response.put("hasAnalysis", result.hasAnalysis());
            response.put("matches", result.matches());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to compute match scores for candidate={}", authentication.getName(), e);
            return ResponseEntity.internalServerError().body("Failed to compute match scores. Please try again.");
        }
    }

    // מחזיר סטרים (SSE) של ציוני התאמה למשרות שמתעדכן בזמן אמת ככל שכל משרה מחושבת
    @PostMapping(path = "/match-scores/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMatchScores(@RequestBody MatchScoreRequest request, Authentication authentication) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        Object sendLock = new Object();
        String email = authentication.getName();
        List<Long> jobIds = request.jobIds() == null ? List.of() : request.jobIds();
        List<Job> jobs = jobIds.isEmpty() ? List.of() : jobRepository.findAllById(jobIds);

        streamingExecutor.execute(() -> {
            try {
                if (!jobMatchService.hasAnalysis(email)) {
                    sendEvent(emitter, sendLock, "no-analysis", Map.of());
                    emitter.complete();
                    return;
                }

                jobMatchService.computeMatchScoresStreaming(email, jobs, request.language(), Map.of(), "internal",
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

    // מחזיר ניתוח התאמה מפורט למשרה בודדת - כישורים חסרים, המלצות ופירוט לפי קטגוריות
    @PostMapping("/match-detail")
    public ResponseEntity<?> getMatchDetail(@RequestBody MatchDetailRequest request, Authentication authentication) {
        try {
            if (request.jobId() == null) {
                return ResponseEntity.badRequest().body("jobId is required");
            }

            Job job = jobRepository.findById(request.jobId()).orElse(null);
            if (job == null) {
                return ResponseEntity.status(404).body("Job not found");
            }

            JobMatchService.MatchDetailResult result =
                    jobMatchService.getMatchDetail(authentication.getName(), job, request.language());

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

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to compute match detail for candidate={} jobId={}", authentication.getName(), request.jobId(), e);
            return ResponseEntity.internalServerError().body("Failed to compute match detail. Please try again.");
        }
    }
}
