package com.jobmatchai.backend.controller;

import com.jobmatchai.backend.model.Application;
import com.jobmatchai.backend.model.CandidateAiSummary;
import com.jobmatchai.backend.model.Job;
import com.jobmatchai.backend.repository.ApplicationRepository;
import com.jobmatchai.backend.repository.CandidateAiSummaryRepository;
import com.jobmatchai.backend.repository.JobRepository;
import com.jobmatchai.backend.service.JobMatchService;

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
            long applicantsCount
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
                        applicantCounts.getOrDefault(job.getId(), 0L)
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
                            summary != null ? summary.getMatchLabel() : null
                    );
                })
                .toList();

        return ResponseEntity.ok(applicants);
    }

    @PostMapping("/add")
    @PreAuthorize("hasRole('COMPANY')")
    public Map<String, Object> addJob(@RequestBody Job job, Authentication authentication) {
        Map<String, Object> response = new HashMap<>();
        job.setCompanyEmail(authentication.getName());

        try {
            Job savedJob = jobRepository.save(job);

            response.put("success", true);
            response.put("message", "Job added successfully");
            response.put("job", savedJob);

            return response;
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
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
            response.put("success", false);
            response.put("message", e.getMessage());
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
            response.put("success", false);
            response.put("message", e.getMessage());
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
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Failed to compute match scores: " + e.getMessage());
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

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Failed to compute match detail: " + e.getMessage());
        }
    }
}