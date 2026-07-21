package com.jobmatchai.backend.service;

import com.jobmatchai.backend.exception.InvalidCredentialsException;
import com.jobmatchai.backend.model.User;
import com.jobmatchai.backend.repository.UserRepository;
import com.jobmatchai.backend.security.JwtService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

// Single place both /api/auth/login and /api/users/login go through, so credential
// checking and token issuance can't drift between the two copies of this endpoint.
@Service
public class AuthService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public record LoginResult(User user, String token) {}

    // Same message for both cases - a distinct "User not found" vs "Wrong password"
    // lets an attacker enumerate which emails have accounts on this platform.
    private static final String INVALID_CREDENTIALS_MESSAGE = "Invalid email or password";

    public LoginResult login(String email, String password) {
        return login(email, password, false);
    }

    public LoginResult login(String email, String password, boolean rememberMe) {
        User user = userRepository.findByEmail(email);

        if (user == null) {
            throw new InvalidCredentialsException(INVALID_CREDENTIALS_MESSAGE);
        }

        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new InvalidCredentialsException(INVALID_CREDENTIALS_MESSAGE);
        }

        String token = jwtService.generateToken(user.getEmail(), user.getRole(), rememberMe);
        user.setPassword(null);
        return new LoginResult(user, token);
    }
}
