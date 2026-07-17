package com.jobmatchai.backend.service;

import com.jobmatchai.backend.model.EmailVerificationCode;
import com.jobmatchai.backend.repository.EmailVerificationCodeRepository;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;

@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);
    private static final long CODE_VALIDITY_MINUTES = 10;

    @Autowired
    private EmailVerificationCodeRepository emailVerificationCodeRepository;

    @Autowired(required = false)
    private JavaMailSender mailSender;

    @Value("${spring.mail.host:}")
    private String mailHost;

    @Value("${spring.mail.username:}")
    private String mailUsername;

    @Value("${app.environment:dev}")
    private String appEnvironment;

    private final SecureRandom secureRandom = new SecureRandom();

    // No account exists yet at this point (this runs BEFORE registration, unlike password
    // reset which always targets an existing user) - so unlike PasswordResetService#requestReset,
    // this never checks whether a user already exists for the email. That check still happens
    // where it always has, in UserRegistrationService#register, via EmailAlreadyExistsException.
    public String requestCode(String email) {
        // Only the latest requested code is ever valid - stops a candidate who re-clicks "send
        // code" from ending up with several simultaneously-valid codes floating around, only
        // one of which is the one actually just emailed to them.
        emailVerificationCodeRepository.findAllByEmailAndUsedFalse(email)
                .forEach(existing -> {
                    existing.setUsed(true);
                    emailVerificationCodeRepository.save(existing);
                });

        String code = String.format("%06d", secureRandom.nextInt(1_000_000));
        EmailVerificationCode verificationCode = new EmailVerificationCode(
                email, code, LocalDateTime.now().plusMinutes(CODE_VALIDITY_MINUTES), false
        );
        emailVerificationCodeRepository.save(verificationCode);

        if (mailHost != null && !mailHost.isBlank() && mailSender != null) {
            try {
                sendVerificationEmail(email, code);
            } catch (Exception e) {
                log.error("Failed to send verification code email to {}: {}", email, e.getMessage());
            }
        } else if ("dev".equals(appEnvironment)) {
            log.info("Verification code for {}: {}", email, code);
        } else {
            // Never log the raw code (or the email tied to it) outside dev - this branch means
            // mail isn't configured at all, so unlike the catch block above there's no delivery
            // failure detail worth trading that off for.
            log.warn("Verification email could not be sent because mail is not configured.");
        }

        // Only ever hand the raw code back in the API response in dev mode - a prod deploy
        // with mail misconfigured must never leak it to the client, same rule as
        // PasswordResetService#requestReset.
        return "dev".equals(appEnvironment) ? code : null;
    }

    private void sendVerificationEmail(String email, String code) throws Exception {
        MimeMessage mimeMessage = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

        helper.setTo(email);
        helper.setSubject("Your JobMatchAI verification code");
        if (mailUsername != null && !mailUsername.isBlank()) {
            helper.setFrom(mailUsername, "JobMatchAI");
            // A missing/absent Reply-To is a minor spam-scoring signal on some filters (it
            // reads as more automated/no-reply-ish than a normal transactional sender) - setting
            // it to the same verified mailbox costs nothing and can only help deliverability.
            helper.setReplyTo(mailUsername);
        }

        String plainText = "Hi,\n\n"
                + "Your JobMatchAI verification code is: " + code + "\n\n"
                + "This code is valid for 10 minutes. Enter it to finish creating your account.\n\n"
                + "If you didn't request this, you can safely ignore this email.\n\n"
                + "Thanks,\nThe JobMatchAI Team";

        String htmlText = "<div style=\"font-family:Arial,Helvetica,sans-serif;font-size:15px;color:#1f2430;line-height:1.6;\">"
                + "<p>Hi,</p>"
                + "<p>Your JobMatchAI verification code is:</p>"
                + "<p style=\"font-size:28px;font-weight:bold;letter-spacing:6px;color:#7f4cff;\">" + code + "</p>"
                + "<p>This code is valid for 10 minutes. Enter it to finish creating your account.</p>"
                + "<p>If you didn't request this, you can safely ignore this email.</p>"
                + "<p>Thanks,<br>The JobMatchAI Team</p>"
                + "</div>";

        helper.setText(plainText, htmlText);
        mailSender.send(mimeMessage);
    }

    public boolean verifyAndConsume(String email, String code) {
        EmailVerificationCode verificationCode = emailVerificationCodeRepository
                .findFirstByEmailAndUsedFalseOrderByIdDesc(email)
                .orElse(null);

        if (verificationCode == null
                || verificationCode.isUsed()
                || verificationCode.getExpiresAt().isBefore(LocalDateTime.now())
                || !verificationCode.getCode().equals(code)) {
            return false;
        }

        verificationCode.setUsed(true);
        emailVerificationCodeRepository.save(verificationCode);
        return true;
    }

    // Used and expired codes have no ongoing value - without this this table would otherwise
    // grow forever, since there's no account yet at this stage for a deletion to cascade from.
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanupExpiredCodes() {
        int deleted = emailVerificationCodeRepository.deleteByUsedTrueOrExpiresAtBefore(LocalDateTime.now());
        if (deleted > 0) {
            log.info("[EMAIL-VERIFICATION-CLEANUP] removed {} expired/used verification codes", deleted);
        }
    }
}
