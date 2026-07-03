package com.jobmatchai.backend.controller;

import com.jobmatchai.backend.model.Notification;
import com.jobmatchai.backend.service.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    @Autowired
    private NotificationService notificationService;

    @GetMapping("/test")
    public String test() {
        return "Notifications API is working";
    }

    @GetMapping
    public ResponseEntity<List<Notification>> getNotifications(Authentication authentication) {
        List<Notification> notifications = notificationService.getNotifications(authentication.getName());
        return ResponseEntity.ok(notifications);
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Object>> getUnreadCount(Authentication authentication) {
        long count = notificationService.getUnreadCount(authentication.getName());
        Map<String, Object> response = new HashMap<>();
        response.put("unreadCount", count);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Map<String, Object>> markAsRead(@PathVariable Long id, Authentication authentication) {
        Notification updated = notificationService.markAsRead(id, authentication.getName());
        Map<String, Object> response = new HashMap<>();

        if (updated == null) {
            response.put("success", false);
            response.put("message", "Notification not found");
            return ResponseEntity.badRequest().body(response);
        }

        response.put("success", true);
        response.put("message", "Notification marked as read");
        response.put("notification", updated);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteNotification(@PathVariable Long id, Authentication authentication) {
        Map<String, Object> response = new HashMap<>();
        boolean deleted = notificationService.deleteNotification(id, authentication.getName());

        if (!deleted) {
            response.put("success", false);
            response.put("message", "Notification not found");
            return ResponseEntity.badRequest().body(response);
        }

        response.put("success", true);
        response.put("message", "Notification deleted");
        return ResponseEntity.ok(response);
    }
}
