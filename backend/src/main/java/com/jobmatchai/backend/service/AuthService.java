package com.jobmatchai.backend.service;

import com.jobmatchai.backend.exception.InvalidCredentialsException;
import com.jobmatchai.backend.model.User;
import com.jobmatchai.backend.repository.UserRepository;
import com.jobmatchai.backend.security.JwtService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public record LoginResult(User user, String token) {}

    private static final String INVALID_CREDENTIALS_MESSAGE = "Invalid email or password";

    public LoginResult login(String email, String password) {
        return login(email, password, false);
    }

    // בודק שהמייל קיים והסיסמה תואמת ל-hash השמור, ואם הכל תקין מנפיק JWT ומחזיר אותו עם המשתמש
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
