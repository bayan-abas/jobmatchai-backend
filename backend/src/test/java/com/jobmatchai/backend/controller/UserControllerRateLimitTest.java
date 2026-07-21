package com.jobmatchai.backend.controller;

import com.jobmatchai.backend.dto.RegisterRequest;
import com.jobmatchai.backend.exception.EmailAlreadyExistsException;
import com.jobmatchai.backend.exception.InvalidCredentialsException;
import com.jobmatchai.backend.exception.InvalidVerificationCodeException;
import com.jobmatchai.backend.model.User;
import com.jobmatchai.backend.repository.UserRepository;
import com.jobmatchai.backend.security.ratelimit.ClientIpResolver;
import com.jobmatchai.backend.security.ratelimit.LoginLockoutService;
import com.jobmatchai.backend.security.ratelimit.RateLimitProperties;
import com.jobmatchai.backend.security.ratelimit.RateLimiterService;
import com.jobmatchai.backend.service.AuthService;
import com.jobmatchai.backend.service.EmailVerificationService;
import com.jobmatchai.backend.service.NotificationService;
import com.jobmatchai.backend.service.PasswordResetService;
import com.jobmatchai.backend.service.UserDeletionService;
import com.jobmatchai.backend.service.UserRegistrationService;
import com.jobmatchai.backend.security.TokenRevocationService;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Mirrors AuthControllerRateLimitTest, but for the /api/users/login and /api/users/register
// endpoints - the routes the real frontend actually calls for login/registration (see
// LoginPage.tsx and CandidateRegisterPage.tsx/CompanyRegisterPage.tsx). AuthController's
// /api/auth/login and /api/auth/register were rate-limited first, but the frontend never hits
// them, so this closes the gap by applying the identical rules to UserController's routes -
// reusing the same RateLimiterService/RateLimitProperties/ClientIpResolver/RateLimitSupport
// rather than a second implementation.
@ExtendWith(MockitoExtension.class)
class UserControllerRateLimitTest {

    private static final long LOGIN_CAPACITY = 2;
    private static final long VERIFY_CODE_CAPACITY = 2;

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserDeletionService userDeletionService;
    @Mock
    private UserRegistrationService userRegistrationService;
    @Mock
    private AuthService authService;

    private UserController userController;

    @BeforeEach
    void setUp() {
        userController = new UserController();
        ReflectionTestUtils.setField(userController, "userRepository", userRepository);
        ReflectionTestUtils.setField(userController, "userDeletionService", userDeletionService);
        ReflectionTestUtils.setField(userController, "userRegistrationService", userRegistrationService);
        ReflectionTestUtils.setField(userController, "authService", authService);
        ReflectionTestUtils.setField(userController, "rateLimiterService", new RateLimiterService());
        ReflectionTestUtils.setField(userController, "rateLimitProperties", testProperties());
        ReflectionTestUtils.setField(userController, "clientIpResolver", new ClientIpResolver());
        ReflectionTestUtils.setField(userController, "loginLockoutService", new LoginLockoutService());
    }

    private static RateLimitProperties testProperties() {
        RateLimitProperties rateLimitProperties = new RateLimitProperties();
        ReflectionTestUtils.setField(rateLimitProperties, "enabled", true);
        ReflectionTestUtils.setField(rateLimitProperties, "loginCapacity", LOGIN_CAPACITY);
        ReflectionTestUtils.setField(rateLimitProperties, "loginWindowSeconds", 60L);
        ReflectionTestUtils.setField(rateLimitProperties, "verifyCodeCapacity", VERIFY_CODE_CAPACITY);
        ReflectionTestUtils.setField(rateLimitProperties, "verifyCodeWindowSeconds", 600L);
        return rateLimitProperties;
    }

    private static HttpServletRequest requestFrom(String ip) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn(ip);
        return request;
    }

    private static Map<String, String> loginData(String email, String password) {
        return Map.of("email", email, "password", password);
    }

    private static RegisterRequest registerRequest(String email) {
        return new RegisterRequest("Jane Doe", email, "password1", "candidate", "0500000000", "123456");
    }

    // ---- /api/users/login (fixed lockout: LOGIN_CAPACITY failed attempts, then a full lockout) ----

    @Test
    void loginUser_allowsFailedAttemptsUpToCapacity_thenLocksOutSameIpWith429() {
        when(authService.login(anyString(), anyString(), anyBoolean()))
                .thenThrow(new InvalidCredentialsException("Invalid email or password"));
        HttpServletRequest ip = requestFrom("198.51.100.11");

        for (int i = 0; i < LOGIN_CAPACITY; i++) {
            ResponseEntity<Map<String, Object>> response =
                    userController.loginUser(loginData("user" + i + "@example.com", "wrongpassword"), ip);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        ResponseEntity<Map<String, Object>> blocked =
                userController.loginUser(loginData("overflow@example.com", "wrongpassword"), ip);

        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(blocked.getHeaders().getFirst("Retry-After")).isNotNull();
        assertThat(blocked.getBody().get("message")).isEqualTo("Too many requests. Please try again later.");
        verify(authService, times((int) LOGIN_CAPACITY)).login(anyString(), anyString(), anyBoolean());
    }

    @Test
    void loginUser_locksOutSameEmailEvenAcrossDifferentIps() {
        when(authService.login(anyString(), anyString(), anyBoolean()))
                .thenThrow(new InvalidCredentialsException("Invalid email or password"));

        for (int i = 0; i < LOGIN_CAPACITY; i++) {
            ResponseEntity<Map<String, Object>> response = userController.loginUser(
                    loginData("victim@example.com", "wrongpassword"), requestFrom("203.0.113." + i));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        ResponseEntity<Map<String, Object>> blocked = userController.loginUser(
                loginData("victim@example.com", "wrongpassword"), requestFrom("203.0.113.99"));

        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(authService, times((int) LOGIN_CAPACITY)).login(anyString(), anyString(), anyBoolean());
    }

    @Test
    void loginUser_invalidCredentials_stillReturnsExistingBadRequestBehavior_whenWithinLimit() {
        when(authService.login(anyString(), anyString(), anyBoolean()))
                .thenThrow(new InvalidCredentialsException("Invalid email or password"));

        ResponseEntity<Map<String, Object>> response = userController.loginUser(
                loginData("nouser@example.com", "wrong"), requestFrom("10.2.0.5"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("message")).isEqualTo("Invalid email or password");
    }

    @Test
    void loginUser_successfulAttempts_neverLockOut() {
        when(authService.login(anyString(), anyString(), anyBoolean()))
                .thenReturn(new AuthService.LoginResult(new User(), "token"));
        HttpServletRequest ip = requestFrom("198.51.100.22");

        for (int i = 0; i < LOGIN_CAPACITY * 3; i++) {
            ResponseEntity<Map<String, Object>> response = userController.loginUser(
                    loginData("frequent-user@example.com", "correct-password"), ip);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void loginUser_sharesLockoutWithAuthControllerLogin() {
        // AuthController#login and UserController#loginUser must lock out together - otherwise
        // an attacker doubles their effective failed-attempt budget by alternating between the
        // two routes to the same AuthService#login action.
        LoginLockoutService sharedLockoutService = new LoginLockoutService();
        RateLimitProperties sharedProperties = testProperties();
        ClientIpResolver sharedIpResolver = new ClientIpResolver();

        ReflectionTestUtils.setField(userController, "loginLockoutService", sharedLockoutService);
        ReflectionTestUtils.setField(userController, "rateLimitProperties", sharedProperties);
        ReflectionTestUtils.setField(userController, "clientIpResolver", sharedIpResolver);

        AuthController authController = new AuthController();
        AuthService authServiceForAuthController = mock(AuthService.class);
        when(authServiceForAuthController.login(anyString(), anyString(), anyBoolean()))
                .thenThrow(new InvalidCredentialsException("Invalid email or password"));
        ReflectionTestUtils.setField(authController, "authService", authServiceForAuthController);
        ReflectionTestUtils.setField(authController, "userRepository", mock(UserRepository.class));
        ReflectionTestUtils.setField(authController, "passwordResetService", mock(PasswordResetService.class));
        ReflectionTestUtils.setField(authController, "notificationService", mock(NotificationService.class));
        ReflectionTestUtils.setField(authController, "userRegistrationService", mock(UserRegistrationService.class));
        ReflectionTestUtils.setField(authController, "tokenRevocationService", mock(TokenRevocationService.class));
        ReflectionTestUtils.setField(authController, "emailVerificationService", mock(EmailVerificationService.class));
        ReflectionTestUtils.setField(authController, "rateLimiterService", new RateLimiterService());
        ReflectionTestUtils.setField(authController, "loginLockoutService", sharedLockoutService);
        ReflectionTestUtils.setField(authController, "rateLimitProperties", sharedProperties);
        ReflectionTestUtils.setField(authController, "clientIpResolver", sharedIpResolver);

        HttpServletRequest ip = requestFrom("198.51.100.12");

        // Exhaust the lockout threshold entirely through /api/auth/login...
        for (int i = 0; i < LOGIN_CAPACITY; i++) {
            authController.login(new AuthController.LoginRequest("shared@example.com", "wrongpassword"), ip);
        }

        // ...and /api/users/login for the same IP/email must already be locked out, without ever
        // reaching this controller's own authService.
        ResponseEntity<Map<String, Object>> blocked =
                userController.loginUser(loginData("shared@example.com", "wrongpassword"), ip);

        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(authService, times(0)).login(anyString(), anyString(), anyBoolean());
    }

    // ---- /api/users/register ----

    @Test
    void registerUser_successfulRegistrations_neverConsumeVerifyCodeBudget() {
        when(userRegistrationService.register(any())).thenReturn(new User());
        HttpServletRequest ip = requestFrom("198.51.100.13");

        for (int i = 0; i < VERIFY_CODE_CAPACITY * 2; i++) {
            ResponseEntity<Map<String, Object>> response =
                    userController.registerUser(registerRequest("a@example.com"), ip);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
    }

    @Test
    void registerUser_emailAlreadyExists_doesNotConsumeVerifyCodeBudget() {
        when(userRegistrationService.register(any())).thenThrow(new EmailAlreadyExistsException());
        HttpServletRequest ip = requestFrom("198.51.100.14");

        for (int i = 0; i < VERIFY_CODE_CAPACITY * 2; i++) {
            ResponseEntity<Map<String, Object>> response =
                    userController.registerUser(registerRequest("taken@example.com"), ip);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().get("success")).isEqualTo(false);
        }
    }

    @Test
    void registerUser_wrongVerificationCode_blocksAfterCapacityFailures_andShortCircuitsFurtherAttempts() {
        when(userRegistrationService.register(any())).thenThrow(new InvalidVerificationCodeException());
        HttpServletRequest ip = requestFrom("198.51.100.15");
        RegisterRequest request = registerRequest("guesser@example.com");

        for (int i = 0; i < VERIFY_CODE_CAPACITY; i++) {
            ResponseEntity<Map<String, Object>> response = userController.registerUser(request, ip);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        ResponseEntity<Map<String, Object>> blocked = userController.registerUser(request, ip);

        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(blocked.getBody().get("message")).isEqualTo("Too many requests. Please try again later.");
        verify(userRegistrationService, times((int) VERIFY_CODE_CAPACITY)).register(any());
    }

    @Test
    void registerUser_sharesVerifyCodeBudgetWithAuthControllerRegister() {
        RateLimiterService sharedRateLimiter = new RateLimiterService();
        RateLimitProperties sharedProperties = testProperties();
        ClientIpResolver sharedIpResolver = new ClientIpResolver();

        ReflectionTestUtils.setField(userController, "rateLimiterService", sharedRateLimiter);
        ReflectionTestUtils.setField(userController, "rateLimitProperties", sharedProperties);
        ReflectionTestUtils.setField(userController, "clientIpResolver", sharedIpResolver);

        AuthController authController = new AuthController();
        UserRegistrationService registrationServiceForAuthController = mock(UserRegistrationService.class);
        when(registrationServiceForAuthController.register(any())).thenThrow(new InvalidVerificationCodeException());
        ReflectionTestUtils.setField(authController, "userRegistrationService", registrationServiceForAuthController);
        ReflectionTestUtils.setField(authController, "userRepository", mock(UserRepository.class));
        ReflectionTestUtils.setField(authController, "passwordResetService", mock(PasswordResetService.class));
        ReflectionTestUtils.setField(authController, "notificationService", mock(NotificationService.class));
        ReflectionTestUtils.setField(authController, "authService", mock(AuthService.class));
        ReflectionTestUtils.setField(authController, "tokenRevocationService", mock(TokenRevocationService.class));
        ReflectionTestUtils.setField(authController, "emailVerificationService", mock(EmailVerificationService.class));
        ReflectionTestUtils.setField(authController, "rateLimiterService", sharedRateLimiter);
        ReflectionTestUtils.setField(authController, "rateLimitProperties", sharedProperties);
        ReflectionTestUtils.setField(authController, "clientIpResolver", sharedIpResolver);

        HttpServletRequest ip = requestFrom("198.51.100.16");
        RegisterRequest request = registerRequest("shared-guesser@example.com");

        // Exhaust the failed-attempt budget entirely through /api/auth/register...
        for (int i = 0; i < VERIFY_CODE_CAPACITY; i++) {
            authController.register(request, ip);
        }

        // ...and /api/users/register for the same IP/email must already be blocked, without ever
        // reaching userRegistrationService.register.
        ResponseEntity<Map<String, Object>> blocked = userController.registerUser(request, ip);

        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(userRegistrationService, times(0)).register(any());
    }
}
