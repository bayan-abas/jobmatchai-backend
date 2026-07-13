package com.jobmatchai.backend.controller;

import com.jobmatchai.backend.dto.RegisterRequest;
import com.jobmatchai.backend.exception.EmailAlreadyExistsException;
import com.jobmatchai.backend.exception.InvalidCredentialsException;
import com.jobmatchai.backend.exception.InvalidRoleException;
import com.jobmatchai.backend.exception.InvalidVerificationCodeException;
import com.jobmatchai.backend.model.User;
import com.jobmatchai.backend.repository.UserRepository;
import com.jobmatchai.backend.service.AuthService;
import com.jobmatchai.backend.service.EmailVerificationService;
import com.jobmatchai.backend.service.NotificationService;
import com.jobmatchai.backend.service.PasswordResetService;
import com.jobmatchai.backend.service.UserRegistrationService;
import com.jobmatchai.backend.security.TokenRevocationService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private static final int MIN_PASSWORD_LENGTH = 6;

    // At least MIN_PASSWORD_LENGTH characters, containing at least one letter and one digit.
    private static final Pattern PASSWORD_PATTERN =
            Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).{" + MIN_PASSWORD_LENGTH + ",}$");

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordResetService passwordResetService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private UserRegistrationService userRegistrationService;

    @Autowired
    private AuthService authService;

    @Autowired
    private TokenRevocationService tokenRevocationService;

    @Autowired
    private EmailVerificationService emailVerificationService;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public record LoginRequest(String email, String password) {}

    public record ForgotPasswordRequest(String email) {}

    public record ResetPasswordRequest(String token, String newPassword) {}

    public record SendVerificationCodeRequest(String email) {}

    @PostMapping("/send-verification-code")
    public ResponseEntity<Map<String, Object>> sendVerificationCode(@RequestBody SendVerificationCodeRequest request) {
        Map<String, Object> response = new HashMap<>();

        if (request.email() == null || request.email().isBlank()) {
            response.put("success", false);
            response.put("message", "Email is required.");
            return ResponseEntity.badRequest().body(response);
        }

        String devCode = emailVerificationService.requestCode(request.email().trim().toLowerCase());

        response.put("success", true);
        response.put("message", "A verification code has been sent to your email.");
        if (devCode != null) {
            response.put("devCode", devCode);
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@Valid @RequestBody RegisterRequest request) {
        Map<String, Object> response = new HashMap<>();

        try {
            User savedUser = userRegistrationService.register(request);

            response.put("success", true);
            response.put("message", "User registered successfully");
            response.put("user", savedUser);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (EmailAlreadyExistsException | InvalidRoleException | InvalidVerificationCodeException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.error("Registration failed (via /api/auth/register)", e);
            response.put("success", false);
            response.put("message", "Registration failed. Please try again.");
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest request) {
        Map<String, Object> response = new HashMap<>();

        try {
            AuthService.LoginResult result = authService.login(request.email(), request.password());

            response.put("success", true);
            response.put("message", "Login successful");
            response.put("token", result.token());
            response.put("user", result.user());
            return ResponseEntity.ok(response);
        } catch (InvalidCredentialsException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.error("Login failed (via /api/auth/login)", e);
            response.put("success", false);
            response.put("message", "Login failed. Please try again.");
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName());

        if (user == null) {
            return ResponseEntity.status(404).body("User not found");
        }

        user.setPassword(null);
        return ResponseEntity.ok(user);
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@RequestBody ForgotPasswordRequest request) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "If an account with that email exists, a reset link has been sent.");

        if (request.email() != null && !request.email().isBlank()) {
            String devResetLink = passwordResetService.requestReset(request.email().trim().toLowerCase());

            if (devResetLink != null) {
                response.put("devResetLink", devResetLink);
            }
        }

        return ResponseEntity.ok(response);
    }

    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody ResetPasswordRequest request) {
        Map<String, Object> response = new HashMap<>();

        if (request.token() == null || request.newPassword() == null || request.newPassword().isBlank()) {
            response.put("success", false);
            response.put("message", "Token and new password are required.");
            return ResponseEntity.badRequest().body(response);
        }

        if (!PASSWORD_PATTERN.matcher(request.newPassword()).matches()) {
            response.put("success", false);
            response.put("message", "Password must be at least 6 characters and contain both letters and numbers.");
            return ResponseEntity.badRequest().body(response);
        }

        boolean success = passwordResetService.resetPassword(request.token(), request.newPassword());

        if (!success) {
            response.put("success", false);
            response.put("message", "This reset link is invalid or has expired.");
            return ResponseEntity.badRequest().body(response);
        }

        response.put("success", true);
        response.put("message", "Password updated successfully.");
        return ResponseEntity.ok(response);
    }

    public record ChangePasswordRequest(String currentPassword, String newPassword) {}

    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@RequestBody ChangePasswordRequest request, Authentication authentication) {
        Map<String, Object> response = new HashMap<>();

        if (request.newPassword() == null || !PASSWORD_PATTERN.matcher(request.newPassword()).matches()) {
            response.put("success", false);
            response.put("message", "New password must be at least " + MIN_PASSWORD_LENGTH + " characters and contain both letters and numbers.");
            return ResponseEntity.badRequest().body(response);
        }

        User user = userRepository.findByEmail(authentication.getName());

        if (user == null) {
            response.put("success", false);
            response.put("message", "User not found.");
            return ResponseEntity.badRequest().body(response);
        }

        if (request.currentPassword() == null || !passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            response.put("success", false);
            response.put("message", "Current password is incorrect.");
            return ResponseEntity.badRequest().body(response);
        }

        user.setPassword(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);

        // A token issued before this change (e.g. a hijacked session that prompted the change)
        // must stop working immediately rather than staying valid until it naturally expires.
        tokenRevocationService.revokeTokensIssuedBefore(user.getEmail(), Instant.now());

        notificationService.createNotification(
                user.getEmail(),
                "Security Alert",
                "Your password was changed. If this was not you, contact support immediately.",
                "SECURITY_ALERT"
        );

        response.put("success", true);
        response.put("message", "Password updated successfully.");
        return ResponseEntity.ok(response);
    }
}
