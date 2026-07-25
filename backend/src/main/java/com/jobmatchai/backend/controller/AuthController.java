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
import com.jobmatchai.backend.security.ratelimit.ClientIpResolver;
import com.jobmatchai.backend.security.ratelimit.LockoutStatus;
import com.jobmatchai.backend.security.ratelimit.LoginLockoutService;
import com.jobmatchai.backend.security.ratelimit.RateLimitProperties;
import com.jobmatchai.backend.security.ratelimit.RateLimitResult;
import com.jobmatchai.backend.security.ratelimit.RateLimitRule;
import com.jobmatchai.backend.security.ratelimit.RateLimiterService;
import com.jobmatchai.backend.security.ratelimit.RateLimitSupport;
import jakarta.servlet.http.HttpServletRequest;
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

    @Autowired
    private RateLimiterService rateLimiterService;

    @Autowired
    private RateLimitProperties rateLimitProperties;

    @Autowired
    private ClientIpResolver clientIpResolver;

    @Autowired
    private LoginLockoutService loginLockoutService;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public record LoginRequest(String email, String password, boolean rememberMe) {

        public LoginRequest(String email, String password) {
            this(email, password, false);
        }
    }

    public record ForgotPasswordRequest(String email) {}

    public record ResetPasswordRequest(String token, String newPassword) {}

    public record SendVerificationCodeRequest(String email) {}

    // שולח קוד אימות בן 6 ספרות למייל, עם הגבלת קצב לפי IP ולפי כתובת מייל
    @PostMapping("/send-verification-code")
    public ResponseEntity<Map<String, Object>> sendVerificationCode(
            @RequestBody SendVerificationCodeRequest request,
            HttpServletRequest httpRequest) {
        Map<String, Object> response = new HashMap<>();

        if (request.email() == null || request.email().isBlank()) {
            response.put("success", false);
            response.put("message", "Email is required.");
            return ResponseEntity.badRequest().body(response);
        }

        String normalizedEmail = RateLimitSupport.normalizeEmail(request.email());

        if (rateLimitProperties.isEnabled()) {
            RateLimitRule rule = rateLimitProperties.sendVerificationCode();
            RateLimitResult ipResult = rateLimiterService.tryConsume(
                    "send-code:ip:" + clientIpResolver.resolve(httpRequest), rule);
            RateLimitResult emailResult = rateLimiterService.tryConsume("send-code:email:" + normalizedEmail, rule);
            if (!ipResult.allowed() || !emailResult.allowed()) {
                return RateLimitSupport.tooManyRequests(Math.max(ipResult.retryAfterSeconds(), emailResult.retryAfterSeconds()));
            }
        }

        String devCode = emailVerificationService.requestCode(normalizedEmail);

        response.put("success", true);
        response.put("message", "A verification code has been sent to your email.");
        if (devCode != null) {
            response.put("devCode", devCode);
        }
        return ResponseEntity.ok(response);
    }

    // רושם משתמש חדש אחרי בדיקת קוד האימות שנשלח למייל, כולל הגבלת קצב על ניסיונות אימות
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest) {
        Map<String, Object> response = new HashMap<>();

        RateLimitRule verifyRule = rateLimitProperties.verifyCode();
        String ipKey = "verify-code:ip:" + clientIpResolver.resolve(httpRequest);
        String normalizedEmail = RateLimitSupport.normalizeEmail(request.email());
        String emailKey = normalizedEmail != null ? "verify-code:email:" + normalizedEmail : null;

        if (rateLimitProperties.isEnabled()) {
            boolean ipHasCapacity = rateLimiterService.hasCapacity(ipKey, verifyRule);
            boolean emailHasCapacity = emailKey == null || rateLimiterService.hasCapacity(emailKey, verifyRule);
            if (!ipHasCapacity || !emailHasCapacity) {
                return RateLimitSupport.tooManyRequests(verifyRule.window().toSeconds());
            }
        }

        try {
            User savedUser = userRegistrationService.register(request);

            response.put("success", true);
            response.put("message", "User registered successfully");
            response.put("user", savedUser);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (InvalidVerificationCodeException e) {
            if (rateLimitProperties.isEnabled()) {
                rateLimiterService.recordFailure(ipKey, verifyRule);
                if (emailKey != null) {
                    rateLimiterService.recordFailure(emailKey, verifyRule);
                }
            }
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (EmailAlreadyExistsException | InvalidRoleException e) {
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

    // מאמת אימייל וסיסמה מול ה-DB, מנעל התחברות אחרי יותר מדי כישלונות, ומחזיר JWT בהצלחה
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        Map<String, Object> response = new HashMap<>();

        RateLimitRule loginRule = rateLimitProperties.login();
        String ipKey = "login:ip:" + clientIpResolver.resolve(httpRequest);
        String normalizedEmail = RateLimitSupport.normalizeEmail(request.email());
        String emailKey = normalizedEmail != null ? "login:email:" + normalizedEmail : null;

        if (rateLimitProperties.isEnabled()) {
            LockoutStatus ipStatus = loginLockoutService.check(ipKey);
            LockoutStatus emailStatus = emailKey != null ? loginLockoutService.check(emailKey) : LockoutStatus.notLockedOut();
            if (ipStatus.lockedOut() || emailStatus.lockedOut()) {
                return RateLimitSupport.tooManyRequests(Math.max(ipStatus.retryAfterSeconds(), emailStatus.retryAfterSeconds()));
            }
        }

        try {
            AuthService.LoginResult result = authService.login(request.email(), request.password(), request.rememberMe());

            if (rateLimitProperties.isEnabled()) {
                loginLockoutService.recordSuccess(ipKey);
                if (emailKey != null) {
                    loginLockoutService.recordSuccess(emailKey);
                }
            }

            response.put("success", true);
            response.put("message", "Login successful");
            response.put("token", result.token());
            response.put("user", result.user());
            return ResponseEntity.ok(response);
        } catch (InvalidCredentialsException e) {
            if (rateLimitProperties.isEnabled()) {
                loginLockoutService.recordFailure(ipKey, loginRule.capacity(), loginRule.window());
                if (emailKey != null) {
                    loginLockoutService.recordFailure(emailKey, loginRule.capacity(), loginRule.window());
                }
            }
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

    // מחזיר את פרטי המשתמש המחובר לפי האימייל שחולץ מהטוקן (בלי הסיסמה)
    @GetMapping("/me")
    public ResponseEntity<?> me(Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName());

        if (user == null) {
            return ResponseEntity.status(404).body("User not found");
        }

        user.setPassword(null);
        return ResponseEntity.ok(user);
    }

    // מתחיל תהליך שכחתי-סיסמה - יוצר טוקן איפוס וסולח מייל, אבל תמיד מחזיר תשובה זהה כדי לא לחשוף אילו מיילים קיימים
    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@RequestBody ForgotPasswordRequest request, HttpServletRequest httpRequest) {
        Map<String, Object> response = new HashMap<>();

        if (rateLimitProperties.isEnabled()) {
            RateLimitRule rule = rateLimitProperties.forgotPassword();
            RateLimitResult ipResult = rateLimiterService.tryConsume(
                    "forgot-password:ip:" + clientIpResolver.resolve(httpRequest), rule);
            String normalizedEmail = RateLimitSupport.normalizeEmail(request.email());
            RateLimitResult emailResult = normalizedEmail != null
                    ? rateLimiterService.tryConsume("forgot-password:email:" + normalizedEmail, rule)
                    : RateLimitResult.allow();

            if (!ipResult.allowed() || !emailResult.allowed()) {
                return RateLimitSupport.tooManyRequests(Math.max(ipResult.retryAfterSeconds(), emailResult.retryAfterSeconds()));
            }
        }

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

    // מאמת את טוקן האיפוס ואת חוזק הסיסמה החדשה, ואם הכל תקין מעדכן את הסיסמה בפועל
    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody ResetPasswordRequest request, HttpServletRequest httpRequest) {
        Map<String, Object> response = new HashMap<>();

        if (rateLimitProperties.isEnabled()) {
            RateLimitRule rule = rateLimitProperties.resetPassword();
            RateLimitResult ipResult = rateLimiterService.tryConsume(
                    "reset-password:ip:" + clientIpResolver.resolve(httpRequest), rule);
            if (!ipResult.allowed()) {
                return RateLimitSupport.tooManyRequests(ipResult.retryAfterSeconds());
            }
        }

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

    // מחליף סיסמה למשתמש מחובר אחרי בדיקת הסיסמה הנוכחית, ומבטל טוקנים ישנים כדי לנתק סשנים קודמים
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

        // טוקנים שהונפקו לפני השינוי (למשל סשן שנחטף) צריכים להיפסל מיד ולא לחכות לתפוגה הרגילה
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
