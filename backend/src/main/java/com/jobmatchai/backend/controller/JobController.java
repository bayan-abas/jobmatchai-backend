package com.jobmatchai.backend.controller;

import com.jobmatchai.backend.model.Job;
import com.jobmatchai.backend.model.SavedJob;
import com.jobmatchai.backend.repository.JobRepository;
import com.jobmatchai.backend.repository.SavedJobRepository;
import com.jobmatchai.backend.service.JobMatchService;
import com.jobmatchai.backend.service.NotificationService;

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
    private SavedJobRepository savedJobRepository;

    @Autowired
    private JobMatchService jobMatchService;

    @Autowired
    private NotificationService notificationService;

    public record MatchScoreRequest(String email, List<Long> jobIds, String language) {}

    public record MatchDetailRequest(String email, Long jobId, String language) {}

    @GetMapping("/test")
    public String test() {
        return "Jobs API is working";
    }

    @GetMapping("/all")
    public List<Job> getAllJobs() {
        return jobRepository.findAll();
    }

    @GetMapping("/company/{companyEmail}")
    public List<Job> getJobsByCompanyEmail(Authentication authentication) {
        return jobRepository.findByCompanyEmail(authentication.getName());
    }

    @PostMapping("/add")
    @PreAuthorize("hasRole('COMPANY')")
    public Map<String, Object> addJob(@RequestBody Job job, Authentication authentication) {
        Map<String, Object> response = new HashMap<>();
        job.setCompanyEmail(authentication.getName());

        try {
            Job savedJob = jobRepository.save(job);

            if (savedJob.getCompanyEmail() != null && !savedJob.getCompanyEmail().isBlank()) {
                notificationService.createNotification(
                        savedJob.getCompanyEmail(),
                        "Job Posted Successfully",
                        "Your job posting '" + savedJob.getTitle() + "' has been created.",
                        "JOB_POSTED"
                );
            }

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

                        if (savedJob.getCompanyEmail() != null && !savedJob.getCompanyEmail().isBlank()) {
                            notificationService.createNotification(
                                    savedJob.getCompanyEmail(),
                                    "Job Updated",
                                    "Your job posting '" + savedJob.getTitle() + "' has been updated.",
                                    "JOB_UPDATED"
                            );
                        }

                        for (SavedJob bookmark : savedJobRepository.findByJobIdAndJobType(savedJob.getId(), "internal")) {
                            notificationService.createNotification(
                                    bookmark.getCandidateEmail(),
                                    "Saved Job Updated",
                                    "A job you saved ('" + savedJob.getTitle() + "') has been updated.",
                                    "SAVED_JOB_UPDATED"
                            );
                        }

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

                        if (job.getCompanyEmail() != null && !job.getCompanyEmail().isBlank()) {
                            notificationService.createNotification(
                                    job.getCompanyEmail(),
                                    "Job Deleted",
                                    "Your job posting '" + job.getTitle() + "' has been deleted.",
                                    "JOB_DELETED"
                            );
                        }

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

            notifyHighMatches(authentication.getName(), result.matches());

            Map<String, Object> response = new HashMap<>();
            response.put("hasAnalysis", result.hasAnalysis());
            response.put("matches", result.matches());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Failed to compute match scores: " + e.getMessage());
        }
    }

    private static final int HIGH_MATCH_THRESHOLD = 85;

    private void notifyHighMatches(String candidateEmail, List<Map<String, Object>> matches) {
        for (Map<String, Object> match : matches) {
            Object jobIdObj = match.get("jobId");
            Object matchPercentObj = match.get("matchPercent");

            if (!(jobIdObj instanceof Long jobId) || !(matchPercentObj instanceof Integer matchPercent)) {
                continue;
            }

            if (matchPercent >= HIGH_MATCH_THRESHOLD) {
                notificationService.createNotificationOnce(
                        candidateEmail,
                        "High Match Found",
                        "You have a " + matchPercent + "% match with a job posting.",
                        "JOB_MATCH_HIGH",
                        jobId
                );
            }
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