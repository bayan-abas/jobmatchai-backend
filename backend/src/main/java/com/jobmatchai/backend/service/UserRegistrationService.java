package com.jobmatchai.backend.service;

import com.jobmatchai.backend.dto.RegisterRequest;
import com.jobmatchai.backend.exception.EmailAlreadyExistsException;
import com.jobmatchai.backend.exception.InvalidRoleException;
import com.jobmatchai.backend.exception.InvalidVerificationCodeException;
import com.jobmatchai.backend.model.User;
import com.jobmatchai.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class UserRegistrationService {

    private static final Set<String> ALLOWED_ROLES = Set.of("candidate", "company");

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailVerificationService emailVerificationService;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    // בודק שהמייל פנוי, שה-role חוקי ושקוד האימות נכון, ורק אז יוצר משתמש חדש עם סיסמה מוצפנת
    public User register(RegisterRequest request) {
        if (userRepository.findByEmail(request.email()) != null) {
            throw new EmailAlreadyExistsException();
        }

        String role = request.role() == null ? "" : request.role().trim().toLowerCase();

        if (!ALLOWED_ROLES.contains(role)) {
            throw new InvalidRoleException();
        }

        if (!emailVerificationService.verifyAndConsume(request.email(), request.verificationCode())) {
            throw new InvalidVerificationCodeException();
        }

        User user = new User();
        user.setName(request.name());
        user.setEmail(request.email());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setRole(role);
        user.setPhone(request.phone());
        user.setPremium(false);

        User saved = userRepository.save(user);
        saved.setPassword(null); // לא להחזיר את ה-hash של הסיסמה בתשובה ל-client
        return saved;
    }
}
