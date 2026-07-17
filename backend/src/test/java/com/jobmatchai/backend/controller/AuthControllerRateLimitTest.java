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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Exercises the rate-limiting layer added to the five auth endpoints identified in the security
// audit (login, send-verification-code, register/verify-code, forgot-password, reset-password).
// Uses REAL RateLimiterService/RateLimitProperties/ClientIpResolver instances (not mocks) so the
// actual Bucket4j-backed throttling logic runs, with small test-only capacities set via
// ReflectionTestUtils so limits are hit in a handful of calls instead of hundreds. Everything else
// downstream of the rate-limit check (AuthService, UserRegistrationService, etc.) stays mocked,
// matching this repo's existing controller-test convention (see PaymentControllerTest).
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
        // Fresh cache per test - a shared instance across tests would leak buckets between them.
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

    // ---- login (fixed lockout: LOGIN_CAPACITY failed attempts, then a full lockout) ----

    private static void failLogin(AuthController controller, AuthService authService, String email, HttpServletRequest ip) {
        // doThrow(...).when(...), not when(...).thenThrow(...): the latter invokes the mock
        // during stub setup, which would itself throw once a matching "throw" stub is already
        // active (as it is here on repeated calls for the same email) - doThrow avoids invoking
        // the mock at all while registering the stub.
        doThrow(new InvalidCredentialsException("Invalid email or password")).when(authService).login(email, "wrongpassword");
        ResponseEntity<Map<String, Object>> response =
                controller.login(new AuthController.LoginRequest(email, "wrongpassword"), ip);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void login_allowsFailedAttemptsUpToCapacity_thenLocksOutSameIpWith429() {
        when(authService.login(anyString(), anyString()))
                .thenThrow(new InvalidCredentialsException("Invalid email or password"));
        HttpServletRequest ip = requestFrom("198.51.100.1");

        // Different emails each call so only the IP dimension is exercised here.
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
        // Generic message - never reveals which dimension (IP vs email) tripped the lockout.
        assertThat(blocked.getBody().get("message")).isEqualTo("Too many requests. Please try again later.");
        // The lockout itself blocks further attempts before ever reaching AuthService again.
        verify(authService, times((int) LOGIN_CAPACITY)).login(anyString(), anyString());
    }

    @Test
    void login_locksOutSameEmailEvenAcrossDifferentIps() {
        when(authService.login(anyString(), anyString()))
                .thenThrow(new InvalidCredentialsException("Invalid email or password"));

        for (int i = 0; i < LOGIN_CAPACITY; i++) {
            ResponseEntity<Map<String, Object>> response = authController.login(
                    new AuthController.LoginRequest("victim@example.com", "wrongpassword"),
                    requestFrom("203.0.113." + i));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        // A brand-new IP, but the same targeted account - must still be locked out.
        ResponseEntity<Map<String, Object>> blocked = authController.login(
                new AuthController.LoginRequest("victim@example.com", "wrongpassword"),
                requestFrom("203.0.113.99"));

        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(authService, times((int) LOGIN_CAPACITY)).login(anyString(), anyString());
    }

    @Test
    void login_separateIpAndEmailPairs_haveIndependentLockouts() {
        when(authService.login(anyString(), anyString()))
                .thenThrow(new InvalidCredentialsException("Invalid email or password"));
        HttpServletRequest ipA = requestFrom("10.0.0.1");

        for (int i = 0; i < LOGIN_CAPACITY; i++) {
            authController.login(new AuthController.LoginRequest("a@example.com", "wrongpassword"), ipA);
        }
        ResponseEntity<Map<String, Object>> blockedA = authController.login(
                new AuthController.LoginRequest("a@example.com", "wrongpassword"), ipA);
        assertThat(blockedA.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // A completely different user, from a different IP, must be unaffected. doReturn(...)
        // here for the same reason as failLogin above - the broad any()/any() stub above is
        // already active and throwing.
        AuthService.LoginResult loginResult = new AuthService.LoginResult(new User(), "token");
        doReturn(loginResult).when(authService).login("b@example.com", "password123");
        ResponseEntity<Map<String, Object>> okB = authController.login(
                new AuthController.LoginRequest("b@example.com", "password123"), requestFrom("10.0.0.2"));
        assertThat(okB.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void login_invalidCredentials_stillReturnsExistingBadRequestBehavior_whenWithinLimit() {
        when(authService.login(anyString(), anyString()))
                .thenThrow(new InvalidCredentialsException("Invalid email or password"));

        ResponseEntity<Map<String, Object>> response = authController.login(
                new AuthController.LoginRequest("nouser@example.com", "wrong"), requestFrom("10.0.0.5"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("message")).isEqualTo("Invalid email or password");
    }

    @Test
    void login_successfulAttempts_neverLockOut() {
        when(authService.login(anyString(), anyString()))
                .thenReturn(new AuthService.LoginResult(new User(), "token"));
        HttpServletRequest ip = requestFrom("198.51.100.20");

        // Far more than LOGIN_CAPACITY successful attempts - only failures are meant to count
        // against the lockout, so this must never be blocked.
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

        // One failure short of the lockout threshold...
        for (int i = 0; i < LOGIN_CAPACITY - 1; i++) {
            failLogin(authController, authService, email, ip);
        }

        // ...then a success, which must clear the accumulated failure count entirely.
        when(authService.login(email, "correct-password"))
                .thenReturn(new AuthService.LoginResult(new User(), "token"));
        ResponseEntity<Map<String, Object>> success =
                authController.login(new AuthController.LoginRequest(email, "correct-password"), ip);
        assertThat(success.getStatusCode()).isEqualTo(HttpStatus.OK);

        // A fresh run of (LOGIN_CAPACITY - 1) failures right after the reset must NOT lock out -
        // if the reset hadn't happened, this next failure would have been the one tipping it over.
        for (int i = 0; i < LOGIN_CAPACITY - 1; i++) {
            failLogin(authController, authService, email, ip);
        }
        ResponseEntity<Map<String, Object>> stillAllowed = authController.login(
                new AuthController.LoginRequest(email, "wrongpassword"), ip);
        assertThat(stillAllowed.getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    // ---- send-verification-code ----

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
        // Blank email is rejected before the rate-limit check even runs, so the request mock
        // deliberately has no stubbing here - it must never be touched.
        ResponseEntity<Map<String, Object>> response = authController.sendVerificationCode(
                new AuthController.SendVerificationCodeRequest(""), mock(HttpServletRequest.class));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("message")).isEqualTo("Email is required.");
    }

    // ---- register / verify-code ----

    @Test
    void register_successfulRegistrations_neverConsumeVerifyCodeBudget() {
        when(userRegistrationService.register(any())).thenReturn(new User());
        HttpServletRequest ip = requestFrom("198.51.100.3");

        // Twice the failure-only capacity, all successful - must never be blocked, since only
        // failed verification-code attempts are meant to count against this limit.
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
            // Unchanged existing behavior - a taken email is not an attack pattern, so it must
            // never turn into a 429 no matter how many times it's retried.
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
        // The blocked attempt must be rejected up front, never reaching the service - otherwise
        // the gate would do nothing to stop continued code-guessing once the limit is hit.
        verify(userRegistrationService, times((int) VERIFY_CODE_CAPACITY)).register(any());
    }

    private static RegisterRequest registerRequest(String email) {
        return new RegisterRequest("Jane Doe", email, "password1", "candidate", "0500000000", "123456");
    }

    // ---- forgot-password ----

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

    // ---- reset-password ----

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
