package com.jobmatchai.backend.service;

import com.jobmatchai.backend.model.PasswordResetToken;
import com.jobmatchai.backend.model.User;
import com.jobmatchai.backend.repository.PasswordResetTokenRepository;
import com.jobmatchai.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
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
                SimpleMailMessage message = new SimpleMailMessage();
                message.setTo(email);
                message.setSubject("Reset your JobMatchAI password");
                message.setText("Click the link below to reset your password (valid for 30 minutes):\n\n" + resetLink);
                mailSender.send(message);
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
