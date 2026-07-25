package com.jobmatchai.backend.repository;

import com.jobmatchai.backend.model.EmailVerificationCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface EmailVerificationCodeRepository extends JpaRepository<EmailVerificationCode, Long> {

    // מביא את קוד האימות האחרון והפעיל שנשלח למייל הזה - כדי לבדוק מול הקוד שהמשתמש הזין
    Optional<EmailVerificationCode> findFirstByEmailAndUsedFalseOrderByIdDesc(String email);

    List<EmailVerificationCode> findAllByEmailAndUsedFalse(String email);

    void deleteByEmail(String email);

    // ניקוי תקופתי של קודים שכבר נוצלו או שפג תוקפם, כדי שהטבלה לא תגדל לנצח
    int deleteByUsedTrueOrExpiresAtBefore(LocalDateTime cutoff);
}
