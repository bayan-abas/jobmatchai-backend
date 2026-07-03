package com.jobmatchai.backend.controller;

import com.jobmatchai.backend.model.Application;
import com.jobmatchai.backend.model.Message;
import com.jobmatchai.backend.repository.ApplicationRepository;
import com.jobmatchai.backend.repository.MessageRepository;
import com.jobmatchai.backend.service.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/messages")
public class MessageController {

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private ApplicationRepository applicationRepository;

    @Autowired
    private NotificationService notificationService;

    public record SendRequest(Long applicationId, String content) {}

    @PostMapping
    @PreAuthorize("hasRole('COMPANY')")
    public Map<String, Object> send(@RequestBody SendRequest request, Authentication authentication) {
        Map<String, Object> response = new HashMap<>();

        Application application = request.applicationId() == null
                ? null
                : applicationRepository.findById(request.applicationId()).orElse(null);

        if (application == null || application.getCandidateEmail() == null
                || request.content() == null || request.content().isBlank()) {
            response.put("success", false);
            response.put("message", "applicationId and content are required");
            return response;
        }

        Message message = new Message(
                application.getId(),
                authentication.getName(),
                application.getCandidateEmail(),
                request.content(),
                LocalDateTime.now()
        );

        Message saved = messageRepository.save(message);

        notificationService.createNotification(
                application.getCandidateEmail(),
                "New Message from " + application.getCompanyName(),
                request.content(),
                "COMPANY_MESSAGE"
        );

        response.put("success", true);
        response.put("message", "Message sent");
        response.put("data", saved);
        return response;
    }
}
