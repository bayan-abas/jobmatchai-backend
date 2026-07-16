package com.jobmatchai.backend.controller;

import com.jobmatchai.backend.dto.JobCreateRequest;
import com.jobmatchai.backend.model.Application;
import com.jobmatchai.backend.model.CandidateAiSummary;
import com.jobmatchai.backend.model.Job;
import com.jobmatchai.backend.repository.ApplicationRepository;
import com.jobmatchai.backend.repository.CandidateAiSummaryRepository;
import com.jobmatchai.backend.repository.JobRepository;
import com.jobmatchai.backend.service.JobMatchService;
import com.jobmatchai.backend.util.MatchLabelUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private JobMatchService jobMatchService;

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
            String createdAt
    ) {}

    public record JobApplicantView(
            Long id,
            Long jobId,
            String jobTitle,
            String candidateName,
            String candidateEmail,
            String status,
            String appliedDate,
            Integer matchPercent,
            String matchLabel
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

    @GetMapping("/all")
    public List<Job> getAllJobs() {
        return jobRepository.findAll();
    }

    @GetMapping("/company/{companyEmail}")
    public List<JobWithApplicantsView> getJobsByCompanyEmail(Authentication authentication) {
        List<Job> jobs = jobRepository.findByCompanyEmail(authentication.getName());
        List<Long> jobIds = jobs.stream().map(Job::getId).toList();

        // One query for every job's applicants instead of one count query per job - against
        // a remote database each round trip is expensive, so this matters even for a
        // handful of postings.
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
                        job.getCreatedAt() != null ? job.getCreatedAt().toString() : null
                ))
                .toList();
    }

    @GetMapping("/{jobId}/applications")
    @PreAuthorize("hasRole('COMPANY')")
    public ResponseEntity<?> getApplicationsForJob(@PathVariable Long jobId, Authentication authentication) {
        Job job = jobRepository.findById(jobId).orElse(null);

        if (job == null || !authentication.getName().equals(job.getCompanyEmail())) {
            return ResponseEntity.status(404).body("Job not found");
        }

        // CandidateAiSummary (the "AI Summary" feature) is the single source of truth for
        // the score/label shown here - it must never be mixed with the separate candidate-
        // facing "Job Matches" score, which comes from a different OpenAI prompt entirely.
        List<Application> applications = applicationRepository.findByJobId(jobId);

        // One batched query for every applicant's summary instead of one per applicant -
        // against a remote database each round trip is expensive.
        Map<String, CandidateAiSummary> summariesByEmail = new HashMap<>();
        if (!applications.isEmpty()) {
            List<String> candidateEmails = applications.stream().map(Application::getCandidateEmail).distinct().toList();
            for (CandidateAiSummary summary : candidateAiSummaryRepository.findByCandidateEmailInAndJobIdIn(candidateEmails, List.of(jobId))) {
                CandidateAiSummary existing = summariesByEmail.get(summary.getCandidateEmail());
                if (existing == null || summary.getId() > existing.getId()) {
                    summariesByEmail.put(summary.getCandidateEmail(), summary);
                }
            }
        }

        List<JobApplicantView> applicants = applications.stream()
                .map(application -> {
                    CandidateAiSummary summary = summariesByEmail.get(application.getCandidateEmail());

                    return new JobApplicantView(
                            application.getId(),
                            application.getJobId(),
                            application.getJobTitle(),
                            application.getCandidateName(),
                            application.getCandidateEmail(),
                            application.getStatus(),
                            application.getAppliedDate(),
                            summary != null ? summary.getMatchScore() : null,
                            summary != null ? MatchLabelUtil.fromScore(summary.getMatchScore()) : null
                    );
                })
                .toList();

        return ResponseEntity.ok(applicants);
    }

    @GetMapping("/{id}/company-details")
    @PreAuthorize("hasRole('COMPANY')")
    public ResponseEntity<?> getJobDetailsForCompany(@PathVariable Long id, Authentication authentication) {
        Job job = jobRepository.findById(id).orElse(null);

        if (job == null || !authentication.getName().equals(job.getCompanyEmail())) {
            return ResponseEntity.status(404).body("Job not found");
        }

        List<Application> applications = applicationRepository.findByJobId(id);

        // Same source/averaging as the company job postings list (getJobsByCompanyEmail +
        // the client-side average in CompanyJobPostings.tsx) - the latest CandidateAiSummary
        // per candidate for this job, never the separate candidate-facing JobMatchScore.
        Integer averageMatchScore = null;
        if (!applications.isEmpty()) {
            List<String> candidateEmails = applications.stream().map(Application::getCandidateEmail).distinct().toList();
            Map<String, CandidateAiSummary> summariesByEmail = new HashMap<>();
            for (CandidateAiSummary summary : candidateAiSummaryRepository.findByCandidateEmailInAndJobIdIn(candidateEmails, List.of(id))) {
                CandidateAiSummary existing = summariesByEmail.get(summary.getCandidateEmail());
                if (existing == null || summary.getId() > existing.getId()) {
                    summariesByEmail.put(summary.getCandidateEmail(), summary);
                }
            }

            List<Integer> scores = summariesByEmail.values().stream()
                    .map(CandidateAiSummary::getMatchScore)
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
                // No status column exists on Job yet - every job is treated as Active
                // everywhere else in the app (see CompanyJobPostings.tsx), so this mirrors
                // that same convention rather than inventing a new value.
                "Active"
        );

        return ResponseEntity.ok(view);
    }

    // Binds a dedicated DTO (never the Job entity itself) so a client can only ever supply the
    // fields listed here - there is no "id" field to smuggle in, so jobRepository.save() below
    // always inserts a brand-new row (Job.id stays null) and can never merge into - and thereby
    // take ownership of - an existing job by id. companyEmail is likewise never taken from the
    // request; it comes exclusively from the authenticated caller.
    @PostMapping("/add")
    @PreAuthorize("hasRole('COMPANY')")
    public Map<String, Object> addJob(@RequestBody JobCreateRequest request, Authentication authentication) {
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

            return jobRepository.findById(id)
                    .map(job -> {
                        jobRepository.deleteById(id);

                        response.put("success", true);
                        response.put("message", "Job deleted successfully");

                        return response;
                    })
                    .orElseGet(() -> {
                        response.put("success", false);
                        response.put("message", "Job not found");
                        return response;
                    });

        } catch (Exception e) {
            log.error("Failed to delete job id={} for company={}", id, authentication.getName(), e);
            response.put("success", false);
            response.put("message", "Failed to delete job. Please try again.");
            return response;
        }
    }

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
            response.put("skillsMatchPercent", result.skillsMatchPercent());
            response.put("experienceMatchPercent", result.experienceMatchPercent());
            response.put("educationMatchPercent", result.educationMatchPercent());
            response.put("languageMatchPercent", result.languageMatchPercent());
            response.put("fieldRelevancePercent", result.fieldRelevancePercent());
            response.put("certificationMatchPercent", result.certificationMatchPercent());
            response.put("locationMatchPercent", result.locationMatchPercent());
            response.put("missingRequiredSkills", result.missingRequiredSkills());
            response.put("missingPreferredSkills", result.missingPreferredSkills());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to compute match detail for candidate={} jobId={}", authentication.getName(), request.jobId(), e);
            return ResponseEntity.internalServerError().body("Failed to compute match detail. Please try again.");
        }
    }
}