package com.jobmatchai.backend.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jobmatchai.backend.model.User;
import com.jobmatchai.backend.repository.PasswordResetTokenRepository;
import com.jobmatchai.backend.repository.UserRepository;
import com.jobmatchai.backend.security.TokenRevocationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordResetTokenRepository passwordResetTokenRepository;
    @Mock
    private NotificationService notificationService;
    @Mock
    private TokenRevocationService tokenRevocationService;

    private PasswordResetService passwordResetService;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        passwordResetService = new PasswordResetService();
        ReflectionTestUtils.setField(passwordResetService, "userRepository", userRepository);
        ReflectionTestUtils.setField(passwordResetService, "passwordResetTokenRepository", passwordResetTokenRepository);
        ReflectionTestUtils.setField(passwordResetService, "notificationService", notificationService);
        ReflectionTestUtils.setField(passwordResetService, "tokenRevocationService", tokenRevocationService);

        ReflectionTestUtils.setField(passwordResetService, "mailSender", null);
        ReflectionTestUtils.setField(passwordResetService, "mailHost", "");
        ReflectionTestUtils.setField(passwordResetService, "mailUsername", "");
        ReflectionTestUtils.setField(passwordResetService, "frontendUrl", "http://localhost:5173");

        logger = (Logger) LoggerFactory.getLogger(PasswordResetService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
    }

    private void setEnvironment(String environment) {
        ReflectionTestUtils.setField(passwordResetService, "appEnvironment", environment);
    }

    private void mockExistingUser(String email) {
        User user = new User();
        user.setEmail(email);
        when(userRepository.findByEmail(email)).thenReturn(user);
    }

    private List<String> formattedLogMessages() {
        return logAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void requestReset_dev_logsRawLinkAndReturnsItInResponse() {
        setEnvironment("dev");
        mockExistingUser("candidate@example.com");

        String returnedLink = passwordResetService.requestReset("candidate@example.com");

        assertThat(returnedLink).isNotNull().contains("/reset-password?token=");
        assertThat(formattedLogMessages())
                .anyMatch(message -> message.contains(returnedLink) && message.contains("candidate@example.com"));
    }

    @Test
    void requestReset_prod_neverLogsLinkTokenOrEmail_andReturnsNull() {
        setEnvironment("prod");
        mockExistingUser("candidate@example.com");

        String returnedLink = passwordResetService.requestReset("candidate@example.com");

        assertThat(returnedLink).isNull();

        assertThat(formattedLogMessages())
                .containsExactly("Password reset email could not be sent because mail is not configured.");
    }

    @Test
    void requestReset_otherNonDevEnvironment_alsoLogsOnlyGenericWarning() {
        setEnvironment("staging");
        mockExistingUser("candidate@example.com");

        String returnedLink = passwordResetService.requestReset("candidate@example.com");

        assertThat(returnedLink).isNull();
        assertThat(formattedLogMessages())
                .containsExactly("Password reset email could not be sent because mail is not configured.");
    }

    @Test
    void requestReset_unknownEmail_stillReturnsNull_withoutLogging_regardlessOfEnvironment() {
        setEnvironment("dev");
        when(userRepository.findByEmail("nouser@example.com")).thenReturn(null);

        String returnedLink = passwordResetService.requestReset("nouser@example.com");

        assertThat(returnedLink).isNull();
        assertThat(formattedLogMessages()).isEmpty();
    }

    @Test
    void requestReset_existingBehavior_persistsTokenRegardlessOfEnvironment() {
        setEnvironment("prod");
        mockExistingUser("candidate@example.com");

        passwordResetService.requestReset("candidate@example.com");

        verify(passwordResetTokenRepository).save(any());
    }
}
