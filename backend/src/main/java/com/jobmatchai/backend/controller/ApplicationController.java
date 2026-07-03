package com.jobmatchai.backend.controller;

import com.jobmatchai.backend.model.Application;
import com.jobmatchai.backend.model.Job;
import com.jobmatchai.backend.model.JobMatchScore;
import com.jobmatchai.backend.model.User;
import com.jobmatchai.backend.repository.ApplicationRepository;
import com.jobmatchai.backend.repository.JobMatchScoreRepository;
import com.jobmatchai.backend.repository.JobRepository;
import com.jobmatchai.backend.repository.UserRepository;
import com.jobmatchai.backend.service.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/applications")
public class ApplicationController {

    @Autowired
    private ApplicationRepository applicationRepository;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JobMatchScoreRepository jobMatchScoreRepository;

    @Autowired
    private NotificationService notificationService;

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
            Integer matchPercent
    ) {}

    @GetMapping("/company")
    @PreAuthorize("hasRole('COMPANY')")
    public List<ApplicantView> getApplicationsByCompany(Authentication authentication) {
        String companyEmail = authentication.getName();

        return applicationRepository.findByCompanyEmail(companyEmail).stream()
                .map(application -> {
                    Integer matchPercent = jobMatchScoreRepository
                            .findByCandidateEmailAndJobId(application.getCandidateEmail(), application.getJobId())
                            .map(JobMatchScore::getMatchPercent)
                            .orElse(null);

                    return new ApplicantView(
                            application.getId(),
                            application.getJobId(),
                            application.getJobTitle(),
                            application.getCandidateName(),
                            application.getCandidateEmail(),
                            application.getStatus(),
                            application.getAppliedDate(),
                            matchPercent
                    );
                })
                .toList();
    }

    @PostMapping("/apply")
    @PreAuthorize("hasRole('CANDIDATE')")
    public Map<String, Object> applyToJob(@RequestBody Application application, Authentication authentication) {
        Map<String, Object> response = new HashMap<>();
        String candidateEmail = authentication.getName();
        application.setCandidateEmail(candidateEmail);

        try {
            if (application.getJobId() == null) {
                response.put("success", false);
                response.put("message", "jobId is required");
                return response;
            }

            Job job = jobRepository.findById(application.getJobId()).orElse(null);
            if (job == null) {
                response.put("success", false);
                response.put("message", "Job not found");
                return response;
            }

            boolean alreadyApplied = applicationRepository
                    .findByCandidateEmailAndJobId(candidateEmail, application.getJobId())
                    .isPresent();

            if (alreadyApplied) {
                response.put("success", false);
                response.put("message", "You already applied to this job");
                return response;
            }

            User candidate = userRepository.findByEmail(candidateEmail);

            application.setJobTitle(job.getTitle());
            application.setCompanyName(job.getCompanyName());
            application.setCompanyEmail(job.getCompanyEmail());
            application.setCandidateName(candidate != null ? candidate.getName() : application.getCandidateName());
            application.setStatus("Under Review");
            application.setAppliedDate(LocalDate.now().toString());

            Application savedApplication = applicationRepository.save(application);

            if (savedApplication.getCandidateEmail() != null && !savedApplication.getCandidateEmail().isBlank()) {
                notificationService.createNotification(
                        savedApplication.getCandidateEmail(),
                        "Application Submitted",
                        "Your application for job ID " + savedApplication.getJobId() + " has been submitted.",
                        "APPLICATION_SUBMITTED"
                );
            }

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

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteApplication(@PathVariable long id, Authentication authentication) {
        Map<String, Object> response = new HashMap<>();

        try {
            Application existing = applicationRepository.findById(id).orElse(null);
            boolean owner = existing != null && (
                    authentication.getName().equals(existing.getCompanyEmail())
                            || authentication.getName().equals(existing.getCandidateEmail())
            );

            if (!owner) {
                response.put("success", false);
                response.put("message", "Application not found");
                return response;
            }

            return applicationRepository.findById(id)
                    .map(application -> {
                        applicationRepository.deleteById(id);

                        if (application.getCandidateEmail() != null && !application.getCandidateEmail().isBlank()) {
                            notificationService.createNotification(
                                    application.getCandidateEmail(),
                                    "Application Removed",
                                    "Your application for job ID " + application.getJobId() + " has been deleted.",
                                    "APPLICATION_REMOVED"
                            );
                        }

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
}