package com.jobmatchai.backend.controller;

import com.jobmatchai.backend.model.User;
import com.jobmatchai.backend.repository.UserRepository;
import com.jobmatchai.backend.security.JwtService;
import com.jobmatchai.backend.service.PasswordResetService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordResetService passwordResetService;

    @Autowired
    private JwtService jwtService;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public record LoginRequest(String email, String password) {}

    private static void stripPassword(User user) {
        if (user != null) {
            user.setPassword(null);
        }
    }

    public record ForgotPasswordRequest(String email) {}

    public record ResetPasswordRequest(String token, String newPassword) {}

    @PostMapping("/register")
    public Map<String, Object> register(@Valid @RequestBody User user) {
        Map<String, Object> response = new HashMap<>();

        try {
            User existingUser = userRepository.findByEmail(user.getEmail());

            if (existingUser != null) {
                response.put("success", false);
                response.put("message", "Email already exists");
                return response;
            }

            user.setPassword(passwordEncoder.encode(user.getPassword()));

            User savedUser = userRepository.save(user);
            stripPassword(savedUser);

            response.put("success", true);
            response.put("message", "User registered successfully");
            response.put("user", savedUser);
            return response;
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return response;
        }
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest request) {
        Map<String, Object> response = new HashMap<>();

        try {
            User user = userRepository.findByEmail(request.email());

            if (user == null) {
                response.put("success", false);
                response.put("message", "User not found");
                return response;
            }

            if (!passwordEncoder.matches(request.password(), user.getPassword())) {
                response.put("success", false);
                response.put("message", "Wrong password");
                return response;
            }

            String token = jwtService.generateToken(user.getEmail(), user.getRole());
            stripPassword(user);

            response.put("success", true);
            response.put("message", "Login successful");
            response.put("token", token);
            response.put("user", user);
            return response;
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return response;
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
}
