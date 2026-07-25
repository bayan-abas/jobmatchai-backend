package com.jobmatchai.backend.controller;

import com.jobmatchai.backend.dto.RegisterRequest;
import com.jobmatchai.backend.exception.EmailAlreadyExistsException;
import com.jobmatchai.backend.exception.InvalidCredentialsException;
import com.jobmatchai.backend.exception.InvalidVerificationCodeException;
import com.jobmatchai.backend.model.User;
import com.jobmatchai.backend.repository.UserRepository;
import com.jobmatchai.backend.security.TokenRevocationService;
import com.jobmatchai.backend.security.ratelimit.ClientIpResolver;
import com.jobmatchai.backend.security.ratelimit.LoginLockoutService;
import com.jobmatchai.backend.security.ratelimit.RateLimitProperties;
import com.jobmatchai.backend.security.ratelimit.RateLimiterService;
import com.jobmatchai.backend.service.AuthService;
import com.jobmatchai.backend.service.EmailVerificationService;
import com.jobmatchai.backend.service.NotificationService;
import com.jobmatchai.backend.service.PasswordResetService;
import com.jobmatchai.backend.service.UserRegistrationService;

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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthControllerRateLimitTest {

    private static final long LOGIN_CAPACITY = 2;
    private static final long SEND_CODE_CAPACITY = 2;
    private static final long VERIFY_CODE_CAPACITY = 2;
    private static final long FORGOT_PASSWORD_CAPACITY = 2;
    private static final long RESET_PASSWORD_CAPACITY = 2;

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordResetService passwordResetService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private UserRegistrationService userRegistrationService;
    @Mock
    private AuthService authService;
    @Mock
    private TokenRevocationService tokenRevocationService;
    @Mock
    private EmailVerificationService emailVerificationService;

    private AuthController authController;

    @BeforeEach
    void setUp() {
        RateLimitProperties rateLimitProperties = new RateLimitProperties();
        ReflectionTestUtils.setField(rateLimitProperties, "enabled", true);
        ReflectionTestUtils.setField(rateLimitProperties, "loginCapacity", LOGIN_CAPACITY);
        ReflectionTestUtils.setField(rateLimitProperties, "loginWindowSeconds", 60L);
        ReflectionTestUtils.setField(rateLimitProperties, "sendVerificationCodeCapacity", SEND_CODE_CAPACITY);
        ReflectionTestUtils.setField(rateLimitProperties, "sendVerificationCodeWindowSeconds", 600L);
        ReflectionTestUtils.setField(rateLimitProperties, "verifyCodeCapacity", VERIFY_CODE_CAPACITY);
        ReflectionTestUtils.setField(rateLimitProperties, "verifyCodeWindowSeconds", 600L);
        ReflectionTestUtils.setField(rateLimitProperties, "forgotPasswordCapacity", FORGOT_PASSWORD_CAPACITY);
        ReflectionTestUtils.setField(rateLimitProperties, "forgotPasswordWindowSeconds", 900L);
        ReflectionTestUtils.setField(rateLimitProperties, "resetPasswordCapacity", RESET_PASSWORD_CAPACITY);
        ReflectionTestUtils.setField(rateLimitProperties, "resetPasswordWindowSeconds", 900L);

        authController = new AuthController();
        ReflectionTestUtils.setField(authController, "userRepository", userRepository);
        ReflectionTestUtils.setField(authController, "passwordResetService", passwordResetService);
        ReflectionTestUtils.setField(authController, "notificationService", notificationService);
        ReflectionTestUtils.setField(authController, "userRegistrationService", userRegistrationService);
        ReflectionTestUtils.setField(authController, "authService", authService);
        ReflectionTestUtils.setField(authController, "tokenRevocationService", tokenRevocationService);
        ReflectionTestUtils.setField(authController, "emailVerificationService", emailVerificationService);

        ReflectionTestUtils.setField(authController, "rateLimiterService", new RateLimiterService());
        ReflectionTestUtils.setField(authController, "rateLimitProperties", rateLimitProperties);
        ReflectionTestUtils.setField(authController, "clientIpResolver", new ClientIpResolver());
        ReflectionTestUtils.setField(authController, "loginLockoutService", new LoginLockoutService());
    }

    private static HttpServletRequest requestFrom(String ip) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn(ip);
        return request;
    }

    private static Map<?, ?> bodyOf(ResponseEntity<?> response) {
        return (Map<?, ?>) response.getBody();
    }

    private static void failLogin(AuthController controller, AuthService authService, String email, HttpServletRequest ip) {

        doThrow(new InvalidCredentialsException("Invalid email or password")).when(authService).login(email, "wrongpassword", false);
        ResponseEntity<Map<String, Object>> response =
                controller.login(new AuthController.LoginRequest(email, "wrongpassword"), ip);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void login_allowsFailedAttemptsUpToCapacity_thenLocksOutSameIpWith429() {
        when(authService.login(anyString(), anyString(), anyBoolean()))
                .thenThrow(new InvalidCredentialsException("Invalid email or password"));
        HttpServletRequest ip = requestFrom("198.51.100.1");

        for (int i = 0; i < LOGIN_CAPACITY; i++) {
            ResponseEntity<Map<String, Object>> response = authController.login(
                    new AuthController.LoginRequest("user" + i + "@example.com", "wrongpassword"), ip);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        ResponseEntity<Map<String, Object>> blocked = authController.login(
                new AuthController.LoginRequest("overflow@example.com", "wrongpassword"), ip);

        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(blocked.getHeaders().getFirst("Retry-After")).isNotNull();
        assertThat(bodyOf(blocked).get("success")).isEqualTo(false);

        assertThat(blocked.getBody().get("message")).isEqualTo("Too many requests. Please try again later.");

        verify(authService, times((int) LOGIN_CAPACITY)).login(anyString(), anyString(), anyBoolean());
    }

    @Test
    void login_locksOutSameEmailEvenAcrossDifferentIps() {
        when(authService.login(anyString(), anyString(), anyBoolean()))
                .thenThrow(new InvalidCredentialsException("Invalid email or password"));

        for (int i = 0; i < LOGIN_CAPACITY; i++) {
            ResponseEntity<Map<String, Object>> response = authController.login(
                    new AuthController.LoginRequest("victim@example.com", "wrongpassword"),
                    requestFrom("203.0.113." + i));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        ResponseEntity<Map<String, Object>> blocked = authController.login(
                new AuthController.LoginRequest("victim@example.com", "wrongpassword"),
                requestFrom("203.0.113.99"));

        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(authService, times((int) LOGIN_CAPACITY)).login(anyString(), anyString(), anyBoolean());
    }

    @Test
    void login_separateIpAndEmailPairs_haveIndependentLockouts() {
        when(authService.login(anyString(), anyString(), anyBoolean()))
                .thenThrow(new InvalidCredentialsException("Invalid email or password"));
        HttpServletRequest ipA = requestFrom("10.0.0.1");

        for (int i = 0; i < LOGIN_CAPACITY; i++) {
            authController.login(new AuthController.LoginRequest("a@example.com", "wrongpassword"), ipA);
        }
        ResponseEntity<Map<String, Object>> blockedA = authController.login(
                new AuthController.LoginRequest("a@example.com", "wrongpassword"), ipA);
        assertThat(blockedA.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        AuthService.LoginResult loginResult = new AuthService.LoginResult(new User(), "token");
        doReturn(loginResult).when(authService).login("b@example.com", "password123", false);
        ResponseEntity<Map<String, Object>> okB = authController.login(
                new AuthController.LoginRequest("b@example.com", "password123"), requestFrom("10.0.0.2"));
        assertThat(okB.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void login_invalidCredentials_stillReturnsExistingBadRequestBehavior_whenWithinLimit() {
        when(authService.login(anyString(), anyString(), anyBoolean()))
                .thenThrow(new InvalidCredentialsException("Invalid email or password"));

        ResponseEntity<Map<String, Object>> response = authController.login(
                new AuthController.LoginRequest("nouser@example.com", "wrong"), requestFrom("10.0.0.5"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("message")).isEqualTo("Invalid email or password");
    }

    @Test
    void login_successfulAttempts_neverLockOut() {
        when(authService.login(anyString(), anyString(), anyBoolean()))
                .thenReturn(new AuthService.LoginResult(new User(), "token"));
        HttpServletRequest ip = requestFrom("198.51.100.20");

        for (int i = 0; i < LOGIN_CAPACITY * 3; i++) {
            ResponseEntity<Map<String, Object>> response = authController.login(
                    new AuthController.LoginRequest("frequent-user@example.com", "correct-password"), ip);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void login_successInBetween_resetsAccumulatedFailures() {
        HttpServletRequest ip = requestFrom("198.51.100.21");
        String email = "recovering-user@example.com";

        for (int i = 0; i < LOGIN_CAPACITY - 1; i++) {
            failLogin(authController, authService, email, ip);
        }

        when(authService.login(email, "correct-password", false))
                .thenReturn(new AuthService.LoginResult(new User(), "token"));
        ResponseEntity<Map<String, Object>> success =
                authController.login(new AuthController.LoginRequest(email, "correct-password"), ip);
        assertThat(success.getStatusCode()).isEqualTo(HttpStatus.OK);

        for (int i = 0; i < LOGIN_CAPACITY - 1; i++) {
            failLogin(authController, authService, email, ip);
        }
        ResponseEntity<Map<String, Object>> stillAllowed = authController.login(
                new AuthController.LoginRequest(email, "wrongpassword"), ip);
        assertThat(stillAllowed.getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void sendVerificationCode_allowsUpToCapacity_thenBlocksWith429() {
        when(emailVerificationService.requestCode(anyString())).thenReturn(null);
        HttpServletRequest ip = requestFrom("198.51.100.2");
        AuthController.SendVerificationCodeRequest request =
                new AuthController.SendVerificationCodeRequest("candidate@example.com");

        for (int i = 0; i < SEND_CODE_CAPACITY; i++) {
            ResponseEntity<Map<String, Object>> response = authController.sendVerificationCode(request, ip);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        ResponseEntity<Map<String, Object>> blocked = authController.sendVerificationCode(request, ip);

        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(blocked.getHeaders().getFirst("Retry-After")).isNotNull();
        verify(emailVerificationService, times((int) SEND_CODE_CAPACITY)).requestCode(anyString());
    }

    @Test
    void sendVerificationCode_blankEmail_stillReturnsExistingValidationError() {

        ResponseEntity<Map<String, Object>> response = authController.sendVerificationCode(
                new AuthController.SendVerificationCodeRequest(""), mock(HttpServletRequest.class));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("message")).isEqualTo("Email is required.");
    }

    @Test
    void register_successfulRegistrations_neverConsumeVerifyCodeBudget() {
        when(userRegistrationService.register(any())).thenReturn(new User());
        HttpServletRequest ip = requestFrom("198.51.100.3");

        for (int i = 0; i < VERIFY_CODE_CAPACITY * 2; i++) {
            ResponseEntity<Map<String, Object>> response = authController.register(registerRequest("a@example.com"), ip);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
    }

    @Test
    void register_emailAlreadyExists_doesNotConsumeVerifyCodeBudget() {
        when(userRegistrationService.register(any())).thenThrow(new EmailAlreadyExistsException());
        HttpServletRequest ip = requestFrom("198.51.100.4");

        for (int i = 0; i < VERIFY_CODE_CAPACITY * 2; i++) {
            ResponseEntity<Map<String, Object>> response = authController.register(registerRequest("taken@example.com"), ip);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().get("success")).isEqualTo(false);
        }
    }

    @Test
    void register_wrongVerificationCode_blocksAfterCapacityFailures_andShortCircuitsFurtherAttempts() {
        when(userRegistrationService.register(any())).thenThrow(new InvalidVerificationCodeException());
        HttpServletRequest ip = requestFrom("198.51.100.5");
        RegisterRequest request = registerRequest("guesser@example.com");

        for (int i = 0; i < VERIFY_CODE_CAPACITY; i++) {
            ResponseEntity<Map<String, Object>> response = authController.register(request, ip);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        ResponseEntity<Map<String, Object>> blocked = authController.register(request, ip);

        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(blocked.getBody().get("message")).isEqualTo("Too many requests. Please try again later.");

        verify(userRegistrationService, times((int) VERIFY_CODE_CAPACITY)).register(any());
    }

    private static RegisterRequest registerRequest(String email) {
        return new RegisterRequest("Jane Doe", email, "password1", "candidate", "0500000000", "123456");
    }

    @Test
    void forgotPassword_allowsUpToCapacity_thenBlocksWith429_withoutRevealingAccountExistence() {
        when(passwordResetService.requestReset(anyString())).thenReturn(null);
        HttpServletRequest ip = requestFrom("198.51.100.6");
        AuthController.ForgotPasswordRequest request =
                new AuthController.ForgotPasswordRequest("someone@example.com");

        for (int i = 0; i < FORGOT_PASSWORD_CAPACITY; i++) {
            ResponseEntity<?> response = authController.forgotPassword(request, ip);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(bodyOf(response).get("message"))
                    .isEqualTo("If an account with that email exists, a reset link has been sent.");
        }

        ResponseEntity<?> blocked = authController.forgotPassword(request, ip);

        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(bodyOf(blocked).get("message")).isEqualTo("Too many requests. Please try again later.");
        verify(passwordResetService, times((int) FORGOT_PASSWORD_CAPACITY)).requestReset(anyString());
    }

    @Test
    void resetPassword_isLimitedByIpOnly_thenBlocksWith429() {
        when(passwordResetService.resetPassword(anyString(), anyString())).thenReturn(false);
        HttpServletRequest ip = requestFrom("198.51.100.7");

        for (int i = 0; i < RESET_PASSWORD_CAPACITY; i++) {
            ResponseEntity<?> response = authController.resetPassword(
                    new AuthController.ResetPasswordRequest("token-" + i, "newpassword1"), ip);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(bodyOf(response).get("message")).isEqualTo("This reset link is invalid or has expired.");
        }

        ResponseEntity<?> blocked = authController.resetPassword(
                new AuthController.ResetPasswordRequest("token-overflow", "newpassword1"), ip);

        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(passwordResetService, times((int) RESET_PASSWORD_CAPACITY)).resetPassword(anyString(), anyString());
    }

    @Test
    void resetPassword_differentIps_haveIndependentBudgets() {
        when(passwordResetService.resetPassword(anyString(), anyString())).thenReturn(false);

        for (int i = 0; i < RESET_PASSWORD_CAPACITY; i++) {
            authController.resetPassword(
                    new AuthController.ResetPasswordRequest("token-" + i, "newpassword1"), requestFrom("10.1.1.1"));
        }
        ResponseEntity<?> blockedFirstIp = authController.resetPassword(
                new AuthController.ResetPasswordRequest("token-x", "newpassword1"), requestFrom("10.1.1.1"));
        assertThat(blockedFirstIp.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        ResponseEntity<?> okOtherIp = authController.resetPassword(
                new AuthController.ResetPasswordRequest("token-y", "newpassword1"), requestFrom("10.1.1.2"));
        assertThat(okOtherIp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bodyOf(okOtherIp).get("message")).isEqualTo("This reset link is invalid or has expired.");
    }
}
