package com.jobmatchai.backend.repository;

import com.jobmatchai.backend.model.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {
    Optional<PasswordResetToken> findByToken(String token);
    void deleteByEmail(String email);

    // ניקוי תקופתי של טוקנים שכבר נוצלו או שפג תוקפם
    int deleteByUsedTrueOrExpiresAtBefore(LocalDateTime cutoff);
}
