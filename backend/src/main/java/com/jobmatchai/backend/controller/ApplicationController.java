package com.jobmatchai.backend.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobmatchai.backend.model.Application;
import com.jobmatchai.backend.model.Interview;
import com.jobmatchai.backend.model.Job;
import com.jobmatchai.backend.model.JobStatus;
import com.jobmatchai.backend.model.User;
import com.jobmatchai.backend.model.CandidateAiSummary;
import com.jobmatchai.backend.model.CVAnalysis;
import com.jobmatchai.backend.model.JobMatchScore;
import com.jobmatchai.backend.repository.ApplicationRepository;
import com.jobmatchai.backend.repository.CandidateAiSummaryRepository;
import com.jobmatchai.backend.repository.CVAnalysisRepository;
import com.jobmatchai.backend.repository.InterviewRepository;
import com.jobmatchai.backend.repository.JobMatchScoreRepository;
import com.jobmatchai.backend.repository.JobRepository;
import com.jobmatchai.backend.repository.UserRepository;
import com.jobmatchai.backend.service.CandidateSummaryService;
import com.jobmatchai.backend.service.JobMatchService;
import com.jobmatchai.backend.service.NotificationService;
import com.jobmatchai.backend.util.MatchLabelUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/applications")
public class ApplicationController {

    private static final Logger log = LoggerFactory.getLogger(ApplicationController.class);

    private static final Set<String> ALLOWED_COMPANY_STATUSES =
            Set.of("Accepted", "Rejected", "Shortlisted", "Under Review");
    private static final Set<String> FINAL_COMPANY_STATUSES = Set.of("Accepted", "Rejected");

    private static final Set<String> ALLOWED_CONTACT_METHODS =
            Set.of("phone_call", "email", "whatsapp", "linkedin", "in_person_meeting", "other");

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
    private JobMatchScoreRepository jobMatchScoreRepository;

    @Autowired
    private JobMatchService jobMatchService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private CandidateSummaryService candidateSummaryService;

    @Autowired
    private CVAnalysisRepository cvAnalysisRepository;

    @Autowired
    private InterviewRepository interviewRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @GetMapping("/test")
    public String test() {
        return "Applications API is working";
    }

    // מחזיר למועמד המחובר את כל הבקשות שהגיש, כולל סטטוס עדכני של כל משרה
    @GetMapping("/candidate/{email}")
    public List<Application> getApplicationsByCandidate(Authentication authentication) {
        List<Application> applications = applicationRepository.findByCandidateEmail(authentication.getName());

        List<Long> jobIds = applications.stream().map(Application::getJobId).distinct().toList();
        Map<Long, JobStatus> statusByJobId = new HashMap<>();
        for (Job job : jobRepository.findAllById(jobIds)) {
            statusByJobId.put(job.getId(), job.getStatus());
        }

        List<Long> applicationIds = applications.stream().map(Application::getId).toList();
        Map<Long, Interview> latestInterviewByApplicationId = new HashMap<>();
        for (Interview interview : interviewRepository.findByApplicationIdIn(applicationIds)) {
            Interview existing = latestInterviewByApplicationId.get(interview.getApplicationId());
            if (existing == null || interview.getId() > existing.getId()) {
                latestInterviewByApplicationId.put(interview.getApplicationId(), interview);
            }
        }

        for (Application application : applications) {
            JobStatus status = statusByJobId.get(application.getJobId());

            application.setJobStatus(status != null ? status.name() : null);
            application.setInterview(latestInterviewByApplicationId.get(application.getId()));
        }

        return applications;
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

            String recommendation,

            boolean hasAiSummary,
            boolean viewedByCompany,
            Map<String, String> preInterviewAnswers,
            String contactMethod,
            String contactMethodOther,
            String contactMessage,
            String rejectionReason
    ) {}

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

    private Map<String, JobMatchScore> resolveCachedMatchScores(List<Application> applications) {
        if (applications.isEmpty()) {
            return Map.of();
        }

        List<String> candidateEmails = applications.stream().map(Application::getCandidateEmail).distinct().toList();
        List<Long> jobIds = applications.stream().map(Application::getJobId).distinct().toList();

        Map<String, JobMatchScore> byKey = new HashMap<>();
        for (JobMatchScore score : jobMatchScoreRepository.findByCandidateEmailInAndJobIdIn(candidateEmails, jobIds)) {
            byKey.put(score.getCandidateEmail() + "::" + score.getJobId(), score);
        }
        return byKey;
    }

    // מחזיר לחברה את כל הבקשות שהוגשו למשרותיה כולל ציוני התאמה ותקצירי AI של מועמדים
    @GetMapping("/company")
    @PreAuthorize("hasRole('COMPANY')")
    public List<ApplicantView> getApplicationsByCompany(Authentication authentication) {
        String companyEmail = authentication.getName();

        List<Application> applications = applicationRepository.findByCompanyEmail(companyEmail);
        Map<String, CandidateAiSummary> summariesByKey = resolveCachedSummaries(applications);
        Map<String, JobMatchScore> matchScoresByKey = resolveCachedMatchScores(applications);

        return applications.stream()
                .map(application -> {
                    String key = application.getCandidateEmail() + "::" + application.getJobId();
                    CandidateAiSummary summary = summariesByKey.get(key);
                    JobMatchScore matchScore = matchScoresByKey.get(key);
                    Integer matchPercent = matchScore != null ? matchScore.getMatchPercent() : null;

                    return new ApplicantView(
                            application.getId(),
                            application.getJobId(),
                            application.getJobTitle(),
                            application.getCandidateName(),
                            application.getCandidateEmail(),
                            application.getStatus(),
                            application.getAppliedDate(),
                            matchPercent,
                            matchPercent != null ? MatchLabelUtil.fromScore(matchPercent) : null,
                            summary != null ? MatchLabelUtil.recommendationFromScore(summary.getMatchScore()) : null,
                            summary != null,
                            application.isViewedByCompany(),
                            parsePreInterviewAnswers(application.getPreInterviewAnswersJson()),
                            application.getContactMethod(),
                            application.getContactMethodOther(),
                            application.getContactMessage(),
                            application.getRejectionReason()
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

    // מגיש בקשה למשרה עבור המועמד המחובר, כולל אכיפת מגבלת המועמדויות החודשית במסלול החינמי
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

            if (job.getStatus() == JobStatus.CLOSED) {
                response.put("success", false);
                response.put("message", "This job is no longer accepting applications.");
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

            application.setStatus("AI Screening");
            application.setCreatedAt(LocalDateTime.now());

            if (request.preInterviewAnswers() != null && !request.preInterviewAnswers().isEmpty()) {
                application.setPreInterviewAnswersJson(objectMapper.writeValueAsString(request.preInterviewAnswers()));
            }

            Application savedApplication = applicationRepository.save(application);

            notificationService.createNotification(
                    candidateEmail,
                    "Application Submitted",
                    "Your application for " + job.getTitle() + " at " + job.getCompanyName() + " has been submitted.",
                    "APPLICATION_SUBMITTED"
            );

            if (job.getCompanyEmail() != null && !job.getCompanyEmail().isBlank()) {
                notificationService.createNotification(
                        job.getCompanyEmail(),
                        "New Applicant",
                        (candidate != null ? candidate.getName() : "A candidate") + " applied for " + job.getTitle() + ".",
                        "APPLICATION_SUBMITTED"
                );
            }

            response.put("success", true);
            response.put("message", "Application submitted successfully");
            response.put("application", savedApplication);

            return response;

        } catch (DataIntegrityViolationException e) {

            response.put("success", false);
            response.put("message", "You already applied to this job");
            return response;
        } catch (Exception e) {
            log.error("Failed to submit application for candidate={} jobId={}",
                    candidateEmail, request.jobId(), e);
            response.put("success", false);
            response.put("message", "Failed to submit application. Please try again.");
            return response;
        }
    }

    public record StatusUpdateRequest(
            String status, String contactMethod, String contactMethodOther, String contactMessage,
            String rejectionReason) {}

    private static final Map<String, String> CONTACT_METHOD_LABELS = Map.of(
            "phone_call", "Phone call",
            "email", "Email",
            "whatsapp", "WhatsApp",
            "linkedin", "LinkedIn",
            "in_person_meeting", "In-person meeting"
    );

    private String buildContactMethodLabel(String contactMethod, String contactMethodOther) {
        if ("other".equals(contactMethod)) {
            return contactMethodOther;
        }
        return CONTACT_METHOD_LABELS.getOrDefault(contactMethod, contactMethod);
    }

    // מעדכן סטטוס בקשה (אישור/דחייה/שורטליסט/בבדיקה) ושולח למועמד התראה מתאימה
    @PutMapping("/{id}/status")
    @PreAuthorize("hasRole('COMPANY')")
    public Map<String, Object> updateStatus(@PathVariable long id, @RequestBody StatusUpdateRequest request, Authentication authentication) {
        Map<String, Object> response = new HashMap<>();

        if (request.status() == null || !ALLOWED_COMPANY_STATUSES.contains(request.status())) {
            response.put("success", false);
            response.put("message", "Status must be one of: Accepted, Rejected, Shortlisted, Under Review");
            return response;
        }

        boolean accepting = "Accepted".equalsIgnoreCase(request.status());
        boolean rejecting = "Rejected".equalsIgnoreCase(request.status());

        if (accepting) {
            if (request.contactMethod() == null || !ALLOWED_CONTACT_METHODS.contains(request.contactMethod())) {
                response.put("success", false);
                response.put("message", "A valid contact method is required to accept an application");
                return response;
            }
            if ("other".equals(request.contactMethod())
                    && (request.contactMethodOther() == null || request.contactMethodOther().isBlank())) {
                response.put("success", false);
                response.put("message", "Please specify the custom contact method");
                return response;
            }
        }

        if (rejecting && (request.rejectionReason() == null || request.rejectionReason().isBlank())) {
            response.put("success", false);
            response.put("message", "A rejection reason is required to reject an application");
            return response;
        }

        try {
            Application existing = applicationRepository.findById(id).orElse(null);

            if (existing == null || !authentication.getName().equals(existing.getCompanyEmail())) {
                response.put("success", false);
                response.put("message", "Application not found");
                return response;
            }

            if (FINAL_COMPANY_STATUSES.contains(existing.getStatus())) {
                response.put("success", false);
                response.put("message", "This application has already been " + existing.getStatus().toLowerCase()
                        + " and cannot be changed.");
                return response;
            }

            return applicationRepository.findById(id)
                    .map(application -> {
                        application.setStatus(request.status());

                        if (accepting) {
                            application.setContactMethod(request.contactMethod());
                            application.setContactMethodOther(
                                    "other".equals(request.contactMethod()) ? request.contactMethodOther().trim() : null);
                            String message = request.contactMessage();
                            application.setContactMessage(message != null && !message.isBlank() ? message.trim() : null);

                            application.setRejectionReason(null);
                        } else if (rejecting) {
                            application.setRejectionReason(request.rejectionReason().trim());
                        } else {

                            application.setRejectionReason(null);
                        }

                        if (!application.isViewedByCompany()) {
                            application.setViewedByCompany(true);
                            application.setViewedAt(LocalDateTime.now());
                        }

                        Application saved = applicationRepository.save(application);

                        if (saved.getCandidateEmail() != null && !saved.getCandidateEmail().isBlank()) {
                            String jobTitle = saved.getJobTitle() != null ? saved.getJobTitle() : "the position";

                            String companySuffix = saved.getCompanyName() != null && !saved.getCompanyName().isBlank()
                                    ? " at " + saved.getCompanyName()
                                    : "";

                            if (accepting) {
                                String contactLabel = buildContactMethodLabel(
                                        saved.getContactMethod(), saved.getContactMethodOther());
                                StringBuilder text = new StringBuilder(
                                        "Your application for " + jobTitle + companySuffix
                                                + " has been accepted! The company will contact you via "
                                                + contactLabel + ".");
                                if (saved.getContactMessage() != null && !saved.getContactMessage().isBlank()) {
                                    text.append(" ").append(saved.getContactMessage());
                                }

                                notificationService.createNotification(
                                        saved.getCandidateEmail(), "Application Accepted", text.toString(), "APPLICATION_ACCEPTED");
                            } else if (rejecting) {

                                String text = "Your application for " + jobTitle + companySuffix
                                        + " has been rejected. Reason: " + saved.getRejectionReason();

                                notificationService.createNotification(
                                        saved.getCandidateEmail(), "Application Rejected", text, "APPLICATION_REJECTED");
                            } else if ("Shortlisted".equals(saved.getStatus())) {
                                notificationService.createNotification(
                                        saved.getCandidateEmail(), "Application Shortlisted",
                                        "Your application for " + jobTitle + " has been shortlisted.",
                                        "APPLICATION_SHORTLISTED");
                            } else if ("Under Review".equals(saved.getStatus())) {
                                notificationService.createNotification(
                                        saved.getCandidateEmail(), "Application Status Updated",
                                        "Your application for " + jobTitle + " is under review.",
                                        "APPLICATION_STATUS_UPDATED");
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
            log.error("Failed to update application status id={} status={}", id, request.status(), e);
            response.put("success", false);
            response.put("message", "Failed to update application status. Please try again.");
            return response;
        }
    }

    // מסמן בקשה כנצפתה על ידי החברה ומעביר אותה אוטומטית לסטטוס "בבדיקה"
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

    // מוחק בקשה - מאפשר למועמד למשוך מועמדות (כל עוד לא נצפתה) או לחברה להסיר אותה
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

            boolean isCompany = authentication.getName().equals(existing.getCompanyEmail());
            applicationRepository.deleteById(id);

            if (isCompany) {
                notificationService.createNotification(
                        existing.getCandidateEmail(),
                        "Application Removed",
                        "Your application for " + existing.getJobTitle() + " at " + existing.getCompanyName()
                                + " was removed by the company.",
                        "APPLICATION_REMOVED"
                );
            }

            response.put("success", true);
            response.put("message", "Application deleted successfully");
            return response;

        } catch (Exception e) {
            log.error("Failed to delete application id={}", id, e);
            response.put("success", false);
            response.put("message", "Failed to delete application. Please try again.");
            return response;
        }
    }

    // מחזיר לחברה תקציר AI על המועמד (חוזקות, חולשות, המלצה) עבור בקשה ספציפית
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

        JobMatchService.MatchScoresResult matchScoresResult =
                jobMatchService.getMatchScores(application.getCandidateEmail(), List.of(job), language);
        Integer matchPercent = null;
        if (!matchScoresResult.matches().isEmpty()) {
            Object rawPercent = matchScoresResult.matches().get(0).get("matchPercent");
            matchPercent = rawPercent instanceof Integer percent ? percent : null;
        }

        Map<String, Object> response = new HashMap<>();
        response.put("hasAnalysis", true);
        response.put("professionalBackground", result.professionalBackground());
        response.put("keySkills", result.keySkills());
        response.put("yearsOfExperience", result.yearsOfExperience());
        response.put("strengths", result.strengths());
        response.put("weaknesses", result.weaknesses());
        response.put("overallSuitability", result.overallSuitability());
        response.put("matchScore", matchPercent);
        response.put("matchLabel", matchPercent != null ? MatchLabelUtil.fromScore(matchPercent) : null);
        response.put("recommendation", result.recommendation());

        return ResponseEntity.ok(response);
    }

    // מחזיר לחברה פרופיל מועמד מפורט שחולץ מניתוח קורות החיים (כישורים, ניסיון, השכלה)
    @GetMapping("/{id}/candidate-profile")
    @PreAuthorize("hasRole('COMPANY')")
    public ResponseEntity<?> getCandidateProfile(@PathVariable Long id, Authentication authentication) {
        Application application = applicationRepository.findById(id).orElse(null);

        if (application == null) {
            return ResponseEntity.status(404).body("Application not found");
        }

        Job job = jobRepository.findById(application.getJobId()).orElse(null);

        if (job == null || !authentication.getName().equals(job.getCompanyEmail())) {
            return ResponseEntity.status(404).body("Application not found");
        }

        User candidate = userRepository.findByEmail(application.getCandidateEmail());
        boolean resumeUploaded = candidate != null
                && candidate.getCvFileName() != null
                && !candidate.getCvFileName().isBlank();

        CVAnalysis analysis = cvAnalysisRepository.findByUserEmail(application.getCandidateEmail()).orElse(null);

        Map<String, Object> response = new HashMap<>();
        response.put("resumeUploaded", resumeUploaded);

        if (analysis == null) {
            response.put("hasAnalysis", false);
            return ResponseEntity.ok(response);
        }

        response.put("hasAnalysis", true);
        response.put("skills", splitSkills(analysis.getTechnicalSkills(), analysis.getSoftSkills(), analysis.getSkills()));
        response.put("yearsOfExperience", analysis.getYearsOfExperience());
        response.put("experienceLevel", analysis.getExperienceLevel());
        response.put("previousJobTitles", analysis.getPreviousJobTitles());
        response.put("educationEvidence", analysis.getEducationEvidence());
        response.put("certificationsEvidence", analysis.getCertificationsEvidence());
        response.put("licensesEvidence", analysis.getLicensesEvidence());
        response.put("languages", analysis.getLanguages());

        return ResponseEntity.ok(response);
    }

    private List<String> splitSkills(String technicalSkills, String softSkills, String legacySkills) {
        String combined = String.join(",",
                technicalSkills == null ? "" : technicalSkills,
                softSkills == null ? "" : softSkills);

        if (combined.replace(",", "").isBlank() && legacySkills != null) {
            combined = legacySkills;
        }

        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String skill : combined.split(",")) {
            String trimmed = skill.trim();
            if (!trimmed.isEmpty()) {
                unique.add(trimmed);
            }
        }

        return List.copyOf(unique);
    }
}
