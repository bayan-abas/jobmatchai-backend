package com.jobmatchai.backend.service;

import com.jobmatchai.backend.model.Notification;
import com.jobmatchai.backend.repository.NotificationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class NotificationService {

    @Autowired
    private NotificationRepository notificationRepository;

    public Notification createNotification(String recipientEmail, String title, String message, String type) {
        Notification notification = new Notification(
                recipientEmail,
                title,
                message,
                type,
                LocalDateTime.now(),
                false
        );
        return notificationRepository.save(notification);
    }

    public List<Notification> getNotifications(String recipientEmail) {
        return notificationRepository.findByRecipientEmailOrderByCreatedAtDesc(recipientEmail);
    }

    public long getUnreadCount(String recipientEmail) {
        return notificationRepository.countByRecipientEmailAndReadFalse(recipientEmail);
    }

    public Notification markAsRead(Long notificationId) {
        return notificationRepository.findById(notificationId)
                .map(notification -> {
                    notification.setRead(true);
                    return notificationRepository.save(notification);
                })
                .orElse(null);
    }
}
