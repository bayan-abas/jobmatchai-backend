package com.jobmatchai.backend.controller;

import com.jobmatchai.backend.model.User;
import com.jobmatchai.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
@CrossOrigin(origins = "http://localhost:5173")
public class UserController {

    @Autowired
    private UserRepository userRepository;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @GetMapping("/test")
    public String test() {
        return "Backend users API is working";
    }

    @GetMapping("/all")
    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    @PostMapping("/register")
    public Map<String, Object> registerUser(@RequestBody User user) {
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

            response.put("success", true);
            response.put("message", "Login successful");
            response.put("user", user);
            return response;

        } catch (Exception e) {
            e.printStackTrace();

            response.put("success", false);
            response.put("message", e.getMessage());
            return response;
        }
    }
}