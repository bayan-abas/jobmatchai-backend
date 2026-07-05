package com.jobmatchai.backend.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobmatchai.backend.model.Application;
import com.jobmatchai.backend.model.Job;
import com.jobmatchai.backend.model.User;
import com.jobmatchai.backend.model.CandidateAiSummary;
import com.jobmatchai.backend.repository.ApplicationRepository;
import com.jobmatchai.backend.repository.CandidateAiSummaryRepository;
import com.jobmatchai.backend.repository.JobRepository;
import com.jobmatchai.backend.repository.UserRepository;
import com.jobmatchai.backend.service.CandidateSummaryService;
import com.jobmatchai.backend.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/applications")
public class ApplicationController {

    private static final Logger log = LoggerFactory.getLogger(ApplicationController.class);

    private static final Set<String> ALLOWED_COMPANY_STATUSES = Set.of("Accepted", "Rejected");

    private static final int FREE_PLAN_MONTHLY_APPLICATION_LIMIT = 10;

    @Autowired
    private ApplicationRepository applicationRepository;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CandidateAiSummaryRepository candidateAiSummaryRepository;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private CandidateSummaryService candidateSummaryService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @GetMapping("/test")
    public String test() {
        return "Applications API is working";
    }

    @GetMapping("/all")
    public List<Application> getAllApplications() {
        return applicationRepository.findAll();
    }

    @GetMapping("/candidate/{email}")
    public List<Application> getApplicationsByCandidate(Authentication authentication) {
        return applicationRepository.findByCandidateEmail(authentication.getName());
    }

    public record ApplicantView(
            Long id,
            Long jobId,
            String jobTitle,
            String candidateName,
            String candidateEmail,
            String status,
            String appliedDate,
            Integer matchPercent,
            String matchLabel,
            boolean viewedByCompany,
            Map<String, String> preInterviewAnswers
    ) {}

    // CandidateAiSummary (the "AI Summary" feature) is the single source of truth for the
    // score/label shown on candidate cards. It must never be mixed with JobMatchScore (the
    // separate candidate-facing "Job Matches" feature, a different OpenAI prompt entirely) -
    // doing so previously caused the card to show one AI evaluation while "AI Summary"
    // showed another, and the value would jump the moment a summary was generated.
    private CandidateAiSummary resolveCachedSummary(String candidateEmail, Long jobId) {
        return candidateAiSummaryRepository
                .findFirstByCandidateEmailAndJobIdOrderByIdDesc(candidateEmail, jobId)
                .orElse(null);
    }

    private Map<String, String> parsePreInterviewAnswers(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }

        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    // Keyed by "candidateEmail::jobId" so a batch of summaries fetched with one query
    // (findByCandidateEmailInAndJobIdIn) can be looked up the same way per-item queries
    // were before, without hitting the database once per row in a list endpoint - against
    // a remote database, N applications used to mean N extra round trips here.
    private Map<String, CandidateAiSummary> resolveCachedSummaries(List<Application> applications) {
        if (applications.isEmpty()) {
            return Map.of();
        }

        List<String> candidateEmails = applications.stream().map(Application::getCandidateEmail).distinct().toList();
        List<Long> jobIds = applications.stream().map(Application::getJobId).distinct().toList();

        Map<String, CandidateAiSummary> latestByKey = new HashMap<>();
        for (CandidateAiSummary summary : candidateAiSummaryRepository.findByCandidateEmailInAndJobIdIn(candidateEmails, jobIds)) {
            String key = summary.getCandidateEmail() + "::" + summary.getJobId();
            CandidateAiSummary existing = latestByKey.get(key);
            if (existing == null || summary.getId() > existing.getId()) {
                latestByKey.put(key, summary);
            }
        }

        return latestByKey;
    }

    @GetMapping("/company")
    @PreAuthorize("hasRole('COMPANY')")
    public List<ApplicantView> getApplicationsByCompany(Authentication authentication) {
        String companyEmail = authentication.getName();

        List<Application> applications = applicationRepository.findByCompanyEmail(companyEmail);
        Map<String, CandidateAiSummary> summariesByKey = resolveCachedSummaries(applications);

        return applications.stream()
                .map(application -> {
                    CandidateAiSummary summary = summariesByKey.get(application.getCandidateEmail() + "::" + application.getJobId());

                    return new ApplicantView(
                            application.getId(),
                            application.getJobId(),
                            application.getJobTitle(),
                            application.getCandidateName(),
                            application.getCandidateEmail(),
                            application.getStatus(),
                            application.getAppliedDate(),
                            summary != null ? summary.getMatchScore() : null,
                            summary != null ? summary.getMatchLabel() : null,
                            application.isViewedByCompany(),
                            parsePreInterviewAnswers(application.getPreInterviewAnswersJson())
                    );
                })
                .toList();
    }

    public record ApplyRequest(
            Long jobId,
            String jobTitle,
            String companyName,
            Map<String, String> preInterviewAnswers
    ) {}

    @PostMapping("/apply")
    @PreAuthorize("hasRole('CANDIDATE')")
    public Map<String, Object> applyToJob(@RequestBody ApplyRequest request, Authentication authentication) {
        Map<String, Object> response = new HashMap<>();
        String candidateEmail = authentication.getName();

        try {
            if (request.jobId() == null) {
                response.put("success", false);
                response.put("message", "jobId is required");
                return response;
            }

            Job job = jobRepository.findById(request.jobId()).orElse(null);
            if (job == null) {
                response.put("success", false);
                response.put("message", "Job not found");
                return response;
            }

            boolean alreadyApplied = applicationRepository
                    .findByCandidateEmailAndJobId(candidateEmail, request.jobId())
                    .isPresent();

            if (alreadyApplied) {
                response.put("success", false);
                response.put("message", "You already applied to this job");
                return response;
            }

            User candidate = userRepository.findByEmail(candidateEmail);

            if (candidate == null || !candidate.isPremium()) {
                LocalDateTime startOfMonth = LocalDate.now().withDayOfMonth(1).atStartOfDay();
                long applicationsThisMonth = applicationRepository
                        .countByCandidateEmailAndCreatedAtAfter(candidateEmail, startOfMonth);

                if (applicationsThisMonth >= FREE_PLAN_MONTHLY_APPLICATION_LIMIT) {
                    response.put("success", false);
                    response.put("message",
                            "You've reached the free plan limit of " + FREE_PLAN_MONTHLY_APPLICATION_LIMIT
                                    + " applications this month. Upgrade to Premium for unlimited applications.");
                    return response;
                }
            }

            Application application = new Application();
            application.setCandidateEmail(candidateEmail);
            application.setJobId(job.getId());
            application.setJobTitle(job.getTitle());
            application.setCompanyName(job.getCompanyName());
            application.setCompanyEmail(job.getCompanyEmail());
            application.setCandidateName(candidate != null ? candidate.getName() : null);
            // Starts in automated AI screening; only moves to "Under Review" once the company
            // actually opens it (see markViewed below).
            application.setStatus("AI Screening");
            application.setAppliedDate(LocalDate.now().toString());
            application.setCreatedAt(LocalDateTime.now());

            if (request.preInterviewAnswers() != null && !request.preInterviewAnswers().isEmpty()) {
                application.setPreInterviewAnswersJson(objectMapper.writeValueAsString(request.preInterviewAnswers()));
            }

            Application savedApplication = applicationRepository.save(application);

            response.put("success", true);
            response.put("message", "Application submitted successfully");
            response.put("application", savedApplication);

            return response;

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return response;
        }
    }

    public record StatusUpdateRequest(String status) {}

    @PutMapping("/{id}/status")
    @PreAuthorize("hasRole('COMPANY')")
    public Map<String, Object> updateStatus(@PathVariable long id, @RequestBody StatusUpdateRequest request, Authentication authentication) {
        Map<String, Object> response = new HashMap<>();

        if (request.status() == null || !ALLOWED_COMPANY_STATUSES.contains(request.status())) {
            response.put("success", false);
            response.put("message", "Status must be Accepted or Rejected");
            return response;
        }

        try {
            Application existing = applicationRepository.findById(id).orElse(null);

            if (existing == null || !authentication.getName().equals(existing.getCompanyEmail())) {
                response.put("success", false);
                response.put("message", "Application not found");
                return response;
            }

            return applicationRepository.findById(id)
                    .map(application -> {
                        application.setStatus(request.status());

                        // A company decision implies the application has been reviewed, even if
                        // the company never separately opened the detail view / mark-viewed call.
                        if (!application.isViewedByCompany()) {
                            application.setViewedByCompany(true);
                            application.setViewedAt(LocalDateTime.now());
                        }

                        Application saved = applicationRepository.save(application);

                        if (saved.getCandidateEmail() != null && !saved.getCandidateEmail().isBlank()) {
                            boolean accepted = "Accepted".equalsIgnoreCase(request.status());
                            boolean rejected = "Rejected".equalsIgnoreCase(request.status());

                            if (accepted || rejected) {
                                notificationService.createNotification(
                                        saved.getCandidateEmail(),
                                        accepted ? "Application Accepted" : "Application Rejected",
                                        "Your application for job ID " + saved.getJobId()
                                                + (accepted ? " has been accepted." : " has been rejected."),
                                        accepted ? "APPLICATION_ACCEPTED" : "APPLICATION_REJECTED"
                                );
                            }
                        }

                        response.put("success", true);
                        response.put("message", "Application status updated");
                        response.put("application", saved);
                        return response;
                    })
                    .orElseGet(() -> {
                        response.put("success", false);
                        response.put("message", "Application not found");
                        return response;
                    });

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return response;
        }
    }

    @PostMapping("/{id}/mark-viewed")
    @PreAuthorize("hasRole('COMPANY')")
    public Map<String, Object> markViewed(@PathVariable long id, Authentication authentication) {
        Map<String, Object> response = new HashMap<>();

        Application existing = applicationRepository.findById(id).orElse(null);

        if (existing == null || !authentication.getName().equals(existing.getCompanyEmail())) {
            response.put("success", false);
            response.put("message", "Application not found");
            return response;
        }

        existing.setViewedByCompany(true);
        existing.setViewedAt(LocalDateTime.now());

        if ("Applied".equals(existing.getStatus())
                || "AI Screening".equals(existing.getStatus())
                || existing.getStatus() == null) {
            existing.setStatus("Under Review");
        }

        Application saved = applicationRepository.save(existing);

        response.put("success", true);
        response.put("application", saved);
        return response;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteApplication(@PathVariable long id, Authentication authentication) {
        Map<String, Object> response = new HashMap<>();

        try {
            Application existing = applicationRepository.findById(id).orElse(null);

            if (existing == null) {
                response.put("success", false);
                response.put("message", "Application not found");
                return response;
            }

            boolean owner = authentication.getName().equals(existing.getCompanyEmail())
                    || authentication.getName().equals(existing.getCandidateEmail());

            if (!owner) {
                response.put("success", false);
                response.put("message", "Application not found");
                return response;
            }

            boolean isCandidate = authentication.getName().equals(existing.getCandidateEmail());

            if (isCandidate && existing.isViewedByCompany()) {
                response.put("success", false);
                response.put("message", "This application can no longer be withdrawn because the company has already viewed it.");
                return response;
            }

            return applicationRepository.findById(id)
                    .map(application -> {
                        applicationRepository.deleteById(id);

                        response.put("success", true);
                        response.put("message", "Application deleted successfully");
                        return response;
                    })
                    .orElseGet(() -> {
                        response.put("success", false);
                        response.put("message", "Application not found");
                        return response;
                    });

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return response;
        }
    }

    @PostMapping("/{id}/ai-summary")
    @PreAuthorize("hasRole('COMPANY')")
    public ResponseEntity<?> getCandidateAiSummary(
            @PathVariable Long id,
            @RequestParam(value = "language", defaultValue = "en") String language,
            Authentication authentication) {

        Application application = applicationRepository.findById(id).orElse(null);

        if (application == null) {
            return ResponseEntity.status(404).body("Application not found");
        }

        Job job = jobRepository.findById(application.getJobId()).orElse(null);

        if (job == null || !authentication.getName().equals(job.getCompanyEmail())) {
            return ResponseEntity.status(404).body("Application not found");
        }

        log.info("[AI-SUMMARY] request received: applicationId={} candidate={} jobId={} requestedBy={}",
                id, application.getCandidateEmail(), job.getId(), authentication.getName());

        CandidateSummaryService.SummaryResult result =
                candidateSummaryService.getCandidateSummary(application.getCandidateEmail(), job, language);

        if (!result.hasAnalysis()) {
            Map<String, Object> response = new HashMap<>();
            response.put("hasAnalysis", false);
            response.put("message", "This candidate has not completed a CV analysis yet.");
            return ResponseEntity.ok(response);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("hasAnalysis", true);
        response.put("professionalBackground", result.professionalBackground());
        response.put("keySkills", result.keySkills());
        response.put("yearsOfExperience", result.yearsOfExperience());
        response.put("strengths", result.strengths());
        response.put("weaknesses", result.weaknesses());
        response.put("overallSuitability", result.overallSuitability());
        response.put("matchScore", result.matchScore());
        response.put("matchLabel", result.matchLabel());

        return ResponseEntity.ok(response);
    }
}