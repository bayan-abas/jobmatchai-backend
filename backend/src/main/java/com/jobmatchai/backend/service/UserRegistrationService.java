package com.jobmatchai.backend.service;

import com.jobmatchai.backend.dto.RegisterRequest;
import com.jobmatchai.backend.exception.EmailAlreadyExistsException;
import com.jobmatchai.backend.exception.InvalidRoleException;
import com.jobmatchai.backend.model.User;
import com.jobmatchai.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Set;

// Registration must never bind the client's JSON straight onto the User entity - User has
// public setters for role/premium/stripeCustomerId etc., and those fields (role especially,
// since @PreAuthorize("hasRole(...)") checks it everywhere) must only ever be set here, on
// the server, never taken from the request body as-is. This is the single place both
// /api/auth/register and /api/users/register go through so that guarantee holds everywhere.
@Service
public class UserRegistrationService {

    private static final Set<String> ALLOWED_ROLES = Set.of("candidate", "company");

    @Autowired
    private UserRepository userRepository;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public User register(RegisterRequest request) {
        if (userRepository.findByEmail(request.email()) != null) {
            throw new EmailAlreadyExistsException();
        }

        String role = request.role() == null ? "" : request.role().trim().toLowerCase();

        if (!ALLOWED_ROLES.contains(role)) {
            throw new InvalidRoleException();
        }

        User user = new User();
        user.setName(request.name());
        user.setEmail(request.email());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setRole(role);
        user.setPremium(false);

        User saved = userRepository.save(user);
        saved.setPassword(null);
        return saved;
    }
}
