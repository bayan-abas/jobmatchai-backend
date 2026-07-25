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

    // מייצר קוד אימות בן 6 ספרות, שומר אותו ל-10 דקות ושולח אותו למייל (או מחזיר אותו ישירות בסביבת dev)
    public String requestCode(String email) {
        // מבטלים קודים קודמים כדי שלא יהיו כמה קודים תקפים בו-זמנית למשתמש שלחץ "שלח קוד" כמה פעמים
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

            log.warn("Verification email could not be sent because mail is not configured.");
        }

        // מחזיר את הקוד בתגובה רק בסביבת dev, כדי שאפשר יהיה לבדוק בלי לחכות למייל אמיתי
        return "dev".equals(appEnvironment) ? code : null;
    }

    private void sendVerificationEmail(String email, String code) throws Exception {
        MimeMessage mimeMessage = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

        helper.setTo(email);
        helper.setSubject("Your JobMatchAI verification code");
        if (mailUsername != null && !mailUsername.isBlank()) {
            helper.setFrom(mailUsername, "JobMatchAI");

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

    // בודק שהקוד קיים, לא נוצל, לא פג תוקף ותואם למה שהוזן - ואם כן מסמן אותו כמנוצל כדי שלא ישמש שוב
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

    // ג'וב שרץ כל לילה ומנקה מה-DB קודי אימות ישנים שכבר נוצלו או פגו
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanupExpiredCodes() {
        int deleted = emailVerificationCodeRepository.deleteByUsedTrueOrExpiresAtBefore(LocalDateTime.now());
        if (deleted > 0) {
            log.info("[EMAIL-VERIFICATION-CLEANUP] removed {} expired/used verification codes", deleted);
        }
    }
}
