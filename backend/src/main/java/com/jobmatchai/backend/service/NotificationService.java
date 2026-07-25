package com.jobmatchai.backend.service;

import com.jobmatchai.backend.model.Notification;
import com.jobmatchai.backend.repository.NotificationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class NotificationService {

    @Autowired
    private NotificationRepository notificationRepository;

    // יוצר התראה חדשה למשתמש ושומר אותה כלא נקראה
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

    // כמו createNotification, אבל עם referenceId כדי שהלקוח יוכל לנווט ישר לרשומה הרלוונטית (למשל בקשת עבודה) בלחיצה על ההתראה
    public Notification createNotification(String recipientEmail, String title, String message, String type, Long referenceId) {
        Notification notification = new Notification(
                recipientEmail,
                title,
                message,
                type,
                LocalDateTime.now(),
                false
        );
        notification.setReferenceId(referenceId);
        return notificationRepository.save(notification);
    }

    // יוצר התראה רק אם עוד לא נשלחה התראה מאותו סוג עם אותו referenceId - מונע שליחת אותה התראה כמה פעמים
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

    // מסמן התראה כנקראה, רק אם היא באמת שייכת למשתמש שביקש - מונע קריאה/עדכון של התראות של מישהו אחר
    public Notification markAsRead(Long notificationId, String requesterEmail) {
        return notificationRepository.findById(notificationId)
                .filter(notification -> notification.getRecipientEmail().equals(requesterEmail))
                .map(notification -> {
                    notification.setRead(true);
                    return notificationRepository.save(notification);
                })
                .orElse(null);
    }

    @Transactional
    public int markAllAsRead(String recipientEmail) {
        return notificationRepository.markAllAsReadByRecipientEmail(recipientEmail);
    }

    // מוחק התראה רק אם היא שייכת למשתמש שביקש למחוק אותה
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
