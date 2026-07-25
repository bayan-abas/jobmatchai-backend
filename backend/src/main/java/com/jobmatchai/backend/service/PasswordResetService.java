package com.jobmatchai.backend.service;

import com.jobmatchai.backend.model.PasswordResetToken;
import com.jobmatchai.backend.model.User;
import com.jobmatchai.backend.repository.PasswordResetTokenRepository;
import com.jobmatchai.backend.repository.UserRepository;
import com.jobmatchai.backend.security.TokenRevocationService;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final long TOKEN_VALIDITY_MINUTES = 30;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private TokenRevocationService tokenRevocationService;

    @Autowired(required = false)
    private JavaMailSender mailSender;

    @Value("${spring.mail.host:}")
    private String mailHost;

    @Value("${spring.mail.username:}")
    private String mailUsername;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @Value("${app.environment:dev}")
    private String appEnvironment;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    // אם המייל קיים, מייצר טוקן איפוס חד-פעמי לחצי שעה ושולח למייל לינק לאיפוס סיסמה
    public String requestReset(String email) {
        User user = userRepository.findByEmail(email);

        if (user == null) {
            return null;
        }

        String token = UUID.randomUUID().toString();
        PasswordResetToken resetToken = new PasswordResetToken(
                email, token, LocalDateTime.now().plusMinutes(TOKEN_VALIDITY_MINUTES), false
        );
        passwordResetTokenRepository.save(resetToken);

        String resetLink = frontendUrl + "/reset-password?token=" + token;

        if (mailHost != null && !mailHost.isBlank() && mailSender != null) {
            try {
                sendResetEmail(email, resetLink);
            } catch (Exception e) {
                log.error("Failed to send password reset email to {}: {}", email, e.getMessage());
            }
        } else if ("dev".equals(appEnvironment)) {
            log.info("Password reset link for {}: {}", email, resetLink);
        } else {

            log.warn("Password reset email could not be sent because mail is not configured.");
        }

        // אותו רעיון - בdev מחזירים את הלינק ישירות בתגובה כדי לא להיות תלויים בשליחת מייל
        return "dev".equals(appEnvironment) ? resetLink : null;
    }

    private void sendResetEmail(String email, String resetLink) throws Exception {
        MimeMessage mimeMessage = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

        helper.setTo(email);
        helper.setSubject("Reset your JobMatchAI password");
        if (mailUsername != null && !mailUsername.isBlank()) {
            helper.setFrom(mailUsername, "JobMatchAI");
        }

        String plainText = "Hi,\n\n"
                + "We received a request to reset the password for your JobMatchAI account.\n\n"
                + "Reset your password using the link below (valid for 30 minutes):\n"
                + resetLink + "\n\n"
                + "If you didn't request this, you can safely ignore this email - your password will not be changed.\n\n"
                + "Thanks,\nThe JobMatchAI Team";

        String htmlText = "<div style=\"font-family:Arial,Helvetica,sans-serif;font-size:15px;color:#1f2430;line-height:1.6;\">"
                + "<p>Hi,</p>"
                + "<p>We received a request to reset the password for your JobMatchAI account.</p>"
                + "<p><a href=\"" + resetLink + "\" style=\"display:inline-block;padding:10px 20px;background:#7f4cff;color:#ffffff;border-radius:8px;text-decoration:none;font-weight:bold;\">Reset your password</a></p>"
                + "<p>Or copy and paste this link into your browser (valid for 30 minutes):<br>"
                + "<a href=\"" + resetLink + "\">" + resetLink + "</a></p>"
                + "<p>If you didn't request this, you can safely ignore this email - your password will not be changed.</p>"
                + "<p>Thanks,<br>The JobMatchAI Team</p>"
                + "</div>";

        helper.setText(plainText, htmlText);
        mailSender.send(mimeMessage);
    }

    // מוודא שהטוקן תקף ולא נוצל, ואם כן מעדכן את הסיסמה, מבטל טוקנים ישנים ושולח התראת אבטחה למשתמש
    public boolean resetPassword(String token, String newPassword) {
        PasswordResetToken resetToken = passwordResetTokenRepository.findByToken(token).orElse(null);

        if (resetToken == null || resetToken.isUsed() || resetToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            return false;
        }

        User user = userRepository.findByEmail(resetToken.getEmail());

        if (user == null) {
            return false;
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        // אותה סיבה כמו ב-AuthController#changePassword - טוקן ישן חייב להיפסל מיד אחרי איפוס סיסמה
        tokenRevocationService.revokeTokensIssuedBefore(user.getEmail(), Instant.now());

        resetToken.setUsed(true);
        passwordResetTokenRepository.save(resetToken);

        notificationService.createNotification(
                user.getEmail(),
                "Security Alert",
                "Your password was changed. If this was not you, contact support immediately.",
                "SECURITY_ALERT"
        );

        return true;
    }

    // ג'וב לילי שמנקה טוקני איפוס סיסמה ישנים שכבר נוצלו או פגו, אותו רעיון כמו ניקוי קודי האימות
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanupExpiredTokens() {
        int deleted = passwordResetTokenRepository.deleteByUsedTrueOrExpiresAtBefore(LocalDateTime.now());
        if (deleted > 0) {
            log.info("[PASSWORD-RESET-CLEANUP] removed {} expired/used reset tokens", deleted);
        }
    }
}
