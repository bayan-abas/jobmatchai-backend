package com.jobmatchai.backend.controller;

import com.jobmatchai.backend.dto.RegisterRequest;
import com.jobmatchai.backend.exception.EmailAlreadyExistsException;
import com.jobmatchai.backend.exception.InvalidCredentialsException;
import com.jobmatchai.backend.exception.InvalidRoleException;
import com.jobmatchai.backend.exception.InvalidVerificationCodeException;
import com.jobmatchai.backend.model.User;
import com.jobmatchai.backend.repository.UserRepository;
import com.jobmatchai.backend.security.ratelimit.ClientIpResolver;
import com.jobmatchai.backend.security.ratelimit.LockoutStatus;
import com.jobmatchai.backend.security.ratelimit.LoginLockoutService;
import com.jobmatchai.backend.security.ratelimit.RateLimitProperties;
import com.jobmatchai.backend.security.ratelimit.RateLimitRule;
import com.jobmatchai.backend.security.ratelimit.RateLimitSupport;
import com.jobmatchai.backend.security.ratelimit.RateLimiterService;
import com.jobmatchai.backend.service.AuthService;
import com.jobmatchai.backend.service.UserDeletionService;
import com.jobmatchai.backend.service.UserRegistrationService;
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

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserDeletionService userDeletionService;

    @Autowired
    private UserRegistrationService userRegistrationService;

    @Autowired
    private AuthService authService;

    @Autowired
    private RateLimiterService rateLimiterService;

    @Autowired
    private RateLimitProperties rateLimitProperties;

    @Autowired
    private ClientIpResolver clientIpResolver;

    @Autowired
    private LoginLockoutService loginLockoutService;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public record DeleteAccountRequest(String currentPassword) {}

    private static void stripPassword(User user) {
        if (user != null) {
            user.setPassword(null);
        }
    }

    // No password field here on purpose - password changes go through the dedicated
    // /api/auth/change-password endpoint, which verifies the current password first.
    // This endpoint is authenticated as the account owner, but that alone shouldn't be
    // enough to silently rotate the password (e.g. from a hijacked session) with no check.
    public record ProfileUpdateRequest(
            String name,
            String phone,
            String location,
            String currentTitle,
            String yearsOfExperience,
            String skills,
            String professionalSummary,
            String industry,
            String companySize,
            String website,
            String companyDescription,
            String linkedin,
            String github,
            String founded,
            String companyType
    ) {}

    @GetMapping("/test")
    public String test() {
        return "Backend users API is working";
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> registerUser(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest) {
        Map<String, Object> response = new HashMap<>();

        // Same keys ("verify-code:ip:"/"verify-code:email:") as AuthController#register - this
        // and /api/auth/register are two routes to the identical registration action, so they
        // must share one failed-attempt budget. Otherwise an attacker could double their
        // effective guesses by alternating between the two endpoints. Only wrong verification-
        // code guesses count against this limit (see the catch block below); the pre-check here
        // only peeks (never consumes) so a run of successful registrations can't exhaust it.
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
            log.error("User registration failed", e);

            response.put("success", false);
            response.put("message", "Registration failed. Please try again.");
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> loginUser(
            @RequestBody Map<String, String> loginData,
            HttpServletRequest httpRequest) {
        Map<String, Object> response = new HashMap<>();

        // Same keys ("login:ip:"/"login:email:") as AuthController#login - this and
        // /api/auth/login both authenticate through the identical AuthService#login, so they
        // must share one lockout rather than doubling an attacker's effective attempt budget by
        // splitting requests across the two endpoints.
        RateLimitRule loginRule = rateLimitProperties.login();
        String ipKey = "login:ip:" + clientIpResolver.resolve(httpRequest);
        String normalizedEmail = RateLimitSupport.normalizeEmail(loginData.get("email"));
        String emailKey = normalizedEmail != null ? "login:email:" + normalizedEmail : null;

        if (rateLimitProperties.isEnabled()) {
            LockoutStatus ipStatus = loginLockoutService.check(ipKey);
            LockoutStatus emailStatus = emailKey != null ? loginLockoutService.check(emailKey) : LockoutStatus.notLockedOut();
            if (ipStatus.lockedOut() || emailStatus.lockedOut()) {
                return RateLimitSupport.tooManyRequests(Math.max(ipStatus.retryAfterSeconds(), emailStatus.retryAfterSeconds()));
            }
        }

        try {
            AuthService.LoginResult result = authService.login(loginData.get("email"), loginData.get("password"));

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
            log.error("Login failed", e);

            response.put("success", false);
            response.put("message", "Login failed. Please try again.");
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // Unused by the frontend today, but reachable by any authenticated user with no role/
    // ownership check at all - a candidate could enumerate /api/users/1, /2, ... and read every
    // other user's full profile (email, phone, location, company details). Scoped to the caller's
    // own record only, mirroring the ownership check updateUser/deleteUser below already enforce.
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getUserById(@PathVariable long id, Authentication authentication) {
        Map<String, Object> response = new HashMap<>();

        return userRepository.findById(id)
                .filter(user -> user.getEmail().equals(authentication.getName()))
                .map(user -> {
                    stripPassword(user);
                    response.put("success", true);
                    response.put("user", user);
                    return ResponseEntity.ok(response);
                })
                .orElseGet(() -> {
                    response.put("success", false);
                    response.put("message", "User not found");
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
                });
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateUser(
            @PathVariable long id,
            @RequestBody ProfileUpdateRequest updatedUser,
            Authentication authentication
    ) {
        Map<String, Object> response = new HashMap<>();

        try {
            User existingUser = userRepository.findById(id).orElse(null);

            if (existingUser == null) {
                response.put("success", false);
                response.put("message", "User not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            if (!existingUser.getEmail().equals(authentication.getName())) {
                response.put("success", false);
                response.put("message", "You can only update your own account");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
            }

            // Required fields (mirrors registration's required-field set): only apply an
            // update if it's a real value, not blank - otherwise a stray empty string in
            // the request would silently wipe out the candidate's name/phone/location/title.
            if (updatedUser.name() != null && !updatedUser.name().isBlank()) {
                existingUser.setName(updatedUser.name());
            }

            if (updatedUser.phone() != null && !updatedUser.phone().isBlank()) {
                existingUser.setPhone(updatedUser.phone());
            }

            if (updatedUser.location() != null && !updatedUser.location().isBlank()) {
                existingUser.setLocation(updatedUser.location());
            }

            if (updatedUser.currentTitle() != null && !updatedUser.currentTitle().isBlank()) {
                existingUser.setCurrentTitle(updatedUser.currentTitle());
            }

            if (updatedUser.yearsOfExperience() != null) {
                existingUser.setYearsOfExperience(updatedUser.yearsOfExperience());
            }

            if (updatedUser.skills() != null) {
                existingUser.setSkills(updatedUser.skills());
            }

            if (updatedUser.professionalSummary() != null) {
                existingUser.setProfessionalSummary(updatedUser.professionalSummary());
            }

            // Company profile fields. industry/companySize are required on the company
            // registration form, so they get the same blank-guard as the candidate required
            // fields above; website/companyDescription are optional and may legitimately be
            // cleared to blank.
            if (updatedUser.industry() != null && !updatedUser.industry().isBlank()) {
                existingUser.setIndustry(updatedUser.industry());
            }

            if (updatedUser.companySize() != null && !updatedUser.companySize().isBlank()) {
                existingUser.setCompanySize(updatedUser.companySize());
            }

            if (updatedUser.website() != null) {
                existingUser.setWebsite(updatedUser.website());
            }

            if (updatedUser.companyDescription() != null) {
                existingUser.setCompanyDescription(updatedUser.companyDescription());
            }

            if (updatedUser.linkedin() != null) {
                existingUser.setLinkedin(updatedUser.linkedin());
            }

            if (updatedUser.github() != null) {
                existingUser.setGithub(updatedUser.github());
            }

            if (updatedUser.founded() != null) {
                existingUser.setFounded(updatedUser.founded());
            }

            if (updatedUser.companyType() != null) {
                existingUser.setCompanyType(updatedUser.companyType());
            }

            User savedUser = userRepository.save(existingUser);
            stripPassword(savedUser);

            response.put("success", true);
            response.put("message", "User updated successfully");
            response.put("user", savedUser);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to update user id={}", id, e);
            response.put("success", false);
            response.put("message", "Failed to update user. Please try again.");
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteUser(
            @PathVariable long id,
            @RequestBody(required = false) DeleteAccountRequest request,
            Authentication authentication
    ) {
        Map<String, Object> response = new HashMap<>();

        try {
            User existingUser = userRepository.findById(id).orElse(null);

            if (existingUser == null) {
                response.put("success", false);
                response.put("message", "User not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            if (!existingUser.getEmail().equals(authentication.getName())) {
                response.put("success", false);
                response.put("message", "You can only delete your own account");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
            }

            // Company deletion cascades through every job the company posted and every
            // application against those jobs (see UserDeletionService) - far more destructive
            // than a candidate deleting their own account, so it's the one role gated behind
            // re-entering the current password rather than just the ownership check above.
            if ("company".equalsIgnoreCase(existingUser.getRole())) {
                String currentPassword = request != null ? request.currentPassword() : null;

                if (currentPassword == null || currentPassword.isBlank()) {
                    response.put("success", false);
                    response.put("message", "Current password is required to delete your account.");
                    return ResponseEntity.badRequest().body(response);
                }

                if (!passwordEncoder.matches(currentPassword, existingUser.getPassword())) {
                    response.put("success", false);
                    response.put("message", "Current password is incorrect.");
                    return ResponseEntity.badRequest().body(response);
                }
            }

            userDeletionService.deleteUserAccount(existingUser.getEmail());

            response.put("success", true);
            response.put("message", "User deleted successfully");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to delete user id={}", id, e);
            response.put("success", false);
            response.put("message", "Failed to delete user. Please try again.");
            return ResponseEntity.internalServerError().body(response);
        }
    }
}
