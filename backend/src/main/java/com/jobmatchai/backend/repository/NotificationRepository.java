package com.jobmatchai.backend.repository;

import com.jobmatchai.backend.model.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findByRecipientEmailOrderByCreatedAtDesc(String recipientEmail);
    long countByRecipientEmailAndReadFalse(String recipientEmail);
    boolean existsByRecipientEmailAndTypeAndReferenceId(String recipientEmail, String type, Long referenceId);
    void deleteByRecipientEmail(String recipientEmail);

    // מסמן בבת אחת את כל ההתראות של המשתמש כנקראו, במקום לעדכן כל אחת בנפרד
    @Modifying
    @Query("UPDATE Notification n SET n.read = true WHERE n.recipientEmail = :recipientEmail AND n.read = false")
    int markAllAsReadByRecipientEmail(@Param("recipientEmail") String recipientEmail);
}
