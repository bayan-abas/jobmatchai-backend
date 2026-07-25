package com.jobmatchai.backend.controller;

import com.jobmatchai.backend.repository.ApplicationRepository;
import com.jobmatchai.backend.repository.CVAnalysisRepository;
import com.jobmatchai.backend.repository.ExternalJobRepository;
import com.jobmatchai.backend.repository.JobRepository;
import com.jobmatchai.backend.repository.NotificationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private ExternalJobRepository externalJobRepository;

    @Autowired
    private ApplicationRepository applicationRepository;

    @Autowired
    private CVAnalysisRepository cvAnalysisRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    // מרכז נתוני סטטיסטיקה כלליים ואישיים למסך הדשבורד (סה"כ משרות, בקשות, התראות שלא נקראו)
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getDashboardStats(Authentication authentication) {
        Map<String, Object> stats = new HashMap<>();

        long totalInternalJobs = jobRepository.count();
        long totalExternalJobs = externalJobRepository.count();

        stats.put("totalInternalJobs", totalInternalJobs);
        stats.put("totalExternalJobs", totalExternalJobs);
        stats.put("totalJobs", totalInternalJobs + totalExternalJobs);
        stats.put("totalApplications", applicationRepository.count());
        stats.put("totalCVAnalyses", cvAnalysisRepository.count());

        String email = authentication.getName();
        stats.put("userApplications", applicationRepository.countByCandidateEmail(email));
        stats.put("unreadNotifications", notificationRepository.countByRecipientEmailAndReadFalse(email));

        return ResponseEntity.ok(stats);
    }
}
