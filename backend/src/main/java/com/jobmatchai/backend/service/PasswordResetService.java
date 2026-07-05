package com.jobmatchai.backend.service;

import com.jobmatchai.backend.model.PasswordResetToken;
import com.jobmatchai.backend.model.User;
import com.jobmatchai.backend.repository.PasswordResetTokenRepository;
import com.jobmatchai.backend.repository.UserRepository;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

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
        } else {
            log.info("Password reset link for {}: {}", email, resetLink);
        }

        // Only ever hand the raw reset link back in the API response in dev mode -
        // a prod deploy with mail misconfigured must never leak it to the client.
        return "dev".equals(appEnvironment) ? resetLink : null;
    }

    // Plain-text-only, link-only emails from a bare personal address are a classic spam
    // signature. Sending a proper multipart (HTML + plain text) message with a display
    // name, real body copy and an unsubscribe-style footer materially improves inbox
    // placement - it can't fix domain-level SPF/DKIM/DMARC reputation, which is outside
    // application code, but it removes the content-based red flags we do control.
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
}
