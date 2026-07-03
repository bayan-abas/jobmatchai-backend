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

    public void createNotificationOnce(String recipientEmail, String title, String message, String type, Long referenceId) {
        if (notificationRepository.existsByRecipientEmailAndTypeAndReferenceId(recipientEmail, type, referenceId)) {
            return;
        }

        Notification notification = new Notification(
                recipientEmail,
                title,
                message,
                type,
                LocalDateTime.now(),
                false
        );
        notification.setReferenceId(referenceId);
        notificationRepository.save(notification);
    }

    public List<Notification> getNotifications(String recipientEmail) {
        return notificationRepository.findByRecipientEmailOrderByCreatedAtDesc(recipientEmail);
    }

    public long getUnreadCount(String recipientEmail) {
        return notificationRepository.countByRecipientEmailAndReadFalse(recipientEmail);
    }

    public Notification markAsRead(Long notificationId, String requesterEmail) {
        return notificationRepository.findById(notificationId)
                .filter(notification -> notification.getRecipientEmail().equals(requesterEmail))
                .map(notification -> {
                    notification.setRead(true);
                    return notificationRepository.save(notification);
                })
                .orElse(null);
    }

    public boolean deleteNotification(Long notificationId, String requesterEmail) {
        return notificationRepository.findById(notificationId)
                .filter(notification -> notification.getRecipientEmail().equals(requesterEmail))
                .map(notification -> {
                    notificationRepository.delete(notification);
                    return true;
                })
                .orElse(false);
    }
}
