package com.jobmatchai.backend.controller;

import com.jobmatchai.backend.model.User;
import com.jobmatchai.backend.repository.UserRepository;
import com.jobmatchai.backend.security.JwtService;
import com.jobmatchai.backend.service.UserDeletionService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserDeletionService userDeletionService;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private static void stripPassword(User user) {
        if (user != null) {
            user.setPassword(null);
        }
    }

    public record ProfileUpdateRequest(
            String name,
            String password,
            String phone,
            String location,
            String currentTitle,
            String yearsOfExperience,
            String skills,
            String professionalSummary
    ) {}

    @GetMapping("/test")
    public String test() {
        return "Backend users API is working";
    }

    @GetMapping("/all")
    public List<User> getAllUsers() {
        List<User> users = userRepository.findAll();
        users.forEach(UserController::stripPassword);
        return users;
    }

    @PostMapping("/register")
    public Map<String, Object> registerUser(@Valid @RequestBody User user) {
        Map<String, Object> response = new HashMap<>();

        try {
            User existingUser = userRepository.findByEmail(user.getEmail());

            if (existingUser != null) {
                response.put("success", false);
                response.put("message", "Email already exists");
                return response;
            }

            String encryptedPassword = passwordEncoder.encode(user.getPassword());
            user.setPassword(encryptedPassword);

            User savedUser = userRepository.save(user);
            stripPassword(savedUser);

            response.put("success", true);
            response.put("message", "User registered successfully");
            response.put("user", savedUser);
            return response;

        } catch (Exception e) {
            e.printStackTrace();

            response.put("success", false);
            response.put("message", e.getMessage());
            return response;
        }
    }

    @PostMapping("/login")
    public Map<String, Object> loginUser(@RequestBody Map<String, String> loginData) {
        Map<String, Object> response = new HashMap<>();

        try {
            String email = loginData.get("email");
            String password = loginData.get("password");

            User user = userRepository.findByEmail(email);

            if (user == null) {
                response.put("success", false);
                response.put("message", "User not found");
                return response;
            }

            boolean passwordMatches = passwordEncoder.matches(password, user.getPassword());

            if (!passwordMatches) {
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
            e.printStackTrace();

            response.put("success", false);
            response.put("message", e.getMessage());
            return response;
        }
    }

    @GetMapping("/{id}")
    public Map<String, Object> getUserById(@PathVariable long id) {
        Map<String, Object> response = new HashMap<>();

        return userRepository.findById(id)
                .map(user -> {
                    stripPassword(user);
                    response.put("success", true);
                    response.put("user", user);
                    return response;
                })
                .orElseGet(() -> {
                    response.put("success", false);
                    response.put("message", "User not found");
                    return response;
                });
    }

    @PutMapping("/{id}")
    public Map<String, Object> updateUser(
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
                return response;
            }

            if (!existingUser.getEmail().equals(authentication.getName())) {
                response.put("success", false);
                response.put("message", "You can only update your own account");
                return response;
            }

            if (updatedUser.name() != null) {
                existingUser.setName(updatedUser.name());
            }

            if (updatedUser.password() != null && !updatedUser.password().isEmpty()) {
                existingUser.setPassword(passwordEncoder.encode(updatedUser.password()));
            }

            if (updatedUser.phone() != null) {
                existingUser.setPhone(updatedUser.phone());
            }

            if (updatedUser.location() != null) {
                existingUser.setLocation(updatedUser.location());
            }

            if (updatedUser.currentTitle() != null) {
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

            User savedUser = userRepository.save(existingUser);
            stripPassword(savedUser);

            response.put("success", true);
            response.put("message", "User updated successfully");
            response.put("user", savedUser);
            return response;

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return response;
        }
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteUser(@PathVariable long id, Authentication authentication) {
        Map<String, Object> response = new HashMap<>();

        try {
            User existingUser = userRepository.findById(id).orElse(null);

            if (existingUser == null) {
                response.put("success", false);
                response.put("message", "User not found");
                return response;
            }

            if (!existingUser.getEmail().equals(authentication.getName())) {
                response.put("success", false);
                response.put("message", "You can only delete your own account");
                return response;
            }

            userDeletionService.deleteUserAccount(existingUser.getEmail());

            response.put("success", true);
            response.put("message", "User deleted successfully");
            return response;

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return response;
        }
    }
}
