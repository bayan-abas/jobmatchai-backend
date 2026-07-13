package com.jobmatchai.backend.repository;

import com.jobmatchai.backend.model.EmailVerificationCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface EmailVerificationCodeRepository extends JpaRepository<EmailVerificationCode, Long> {

    // Only the most recently requested code for an email is ever valid - requestCode()
    // invalidates every prior unused row before saving a new one, so this should normally
    // return at most one entry, but ordering by id descending makes it correct even if that
    // invariant is ever violated.
    Optional<EmailVerificationCode> findFirstByEmailAndUsedFalseOrderByIdDesc(String email);

    List<EmailVerificationCode> findAllByEmailAndUsedFalse(String email);

    void deleteByEmail(String email);

    // Used-up or expired codes have zero ongoing value - without this they'd otherwise sit in
    // this table forever, since nothing else ever removes a row (unlike password reset tokens,
    // there's no user account yet to cascade-delete from).
    int deleteByUsedTrueOrExpiresAtBefore(LocalDateTime cutoff);
}
