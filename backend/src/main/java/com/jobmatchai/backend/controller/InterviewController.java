package com.jobmatchai.backend.controller;

import com.jobmatchai.backend.model.Application;
import com.jobmatchai.backend.model.Interview;
import com.jobmatchai.backend.repository.ApplicationRepository;
import com.jobmatchai.backend.repository.InterviewRepository;
import com.jobmatchai.backend.service.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/interviews")
public class InterviewController {

    @Autowired
    private InterviewRepository interviewRepository;

    @Autowired
    private ApplicationRepository applicationRepository;

    @Autowired
    private NotificationService notificationService;

    public record ScheduleRequest(Long applicationId, LocalDateTime scheduledAt, String type, String notes) {}

    public record UpdateRequest(LocalDateTime scheduledAt, String type, String notes, String status) {}

    @PostMapping
    @PreAuthorize("hasRole('COMPANY')")
    public Map<String, Object> schedule(@RequestBody ScheduleRequest request, Authentication authentication) {
        Map<String, Object> response = new HashMap<>();

        Application application = request.applicationId() == null
                ? null
                : applicationRepository.findById(request.applicationId()).orElse(null);

        if (application == null || application.getCandidateEmail() == null) {
            response.put("success", false);
            response.put("message", "Application not found");
            return response;
        }

        Interview interview = new Interview(
                application.getId(),
                application.getCandidateEmail(),
                authentication.getName(),
                request.scheduledAt(),
                request.type(),
                request.notes(),
                "SCHEDULED"
        );

        Interview saved = interviewRepository.save(interview);

        notificationService.createNotification(
                application.getCandidateEmail(),
                "Interview Scheduled",
                "An interview has been scheduled for your application to " + application.getJobTitle() + ".",
                "INTERVIEW_SCHEDULED"
        );

        response.put("success", true);
        response.put("interview", saved);
        return response;
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('COMPANY')")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody UpdateRequest request, Authentication authentication) {
        Map<String, Object> response = new HashMap<>();

        Interview interview = interviewRepository.findById(id).orElse(null);

        if (interview == null || !authentication.getName().equals(interview.getCompanyEmail())) {
            response.put("success", false);
            response.put("message", "Interview not found");
            return response;
        }

        if (request.scheduledAt() != null) {
            interview.setScheduledAt(request.scheduledAt());
        }
        if (request.type() != null) {
            interview.setType(request.type());
        }
        if (request.notes() != null) {
            interview.setNotes(request.notes());
        }
        interview.setStatus(request.status() != null ? request.status() : "UPDATED");

        Interview saved = interviewRepository.save(interview);

        notificationService.createNotification(
                interview.getCandidateEmail(),
                "Interview Updated",
                "Your interview details have been updated.",
                "INTERVIEW_UPDATED"
        );

        response.put("success", true);
        response.put("interview", saved);
        return response;
    }
}
