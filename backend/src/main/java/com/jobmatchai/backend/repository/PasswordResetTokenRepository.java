package com.jobmatchai.backend.repository;

import com.jobmatchai.backend.model.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {
    Optional<PasswordResetToken> findByToken(String token);
    void deleteByEmail(String email);

    // Used-up or expired tokens have zero ongoing value - without this they'd otherwise sit
    // in this table forever, since the only other thing that ever removes a row is the whole
    // account being deleted.
    int deleteByUsedTrueOrExpiresAtBefore(LocalDateTime cutoff);
}
