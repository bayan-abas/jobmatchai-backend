package com.jobmatchai.backend.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jobmatchai.backend.repository.EmailVerificationCodeRepository;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

    @Mock
    private EmailVerificationCodeRepository emailVerificationCodeRepository;

    private EmailVerificationService emailVerificationService;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        when(emailVerificationCodeRepository.findAllByEmailAndUsedFalse(anyString())).thenReturn(List.of());

        emailVerificationService = new EmailVerificationService();
        ReflectionTestUtils.setField(emailVerificationService, "emailVerificationCodeRepository", emailVerificationCodeRepository);

        ReflectionTestUtils.setField(emailVerificationService, "mailSender", null);
        ReflectionTestUtils.setField(emailVerificationService, "mailHost", "");
        ReflectionTestUtils.setField(emailVerificationService, "mailUsername", "");

        logger = (Logger) LoggerFactory.getLogger(EmailVerificationService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
    }

    private void setEnvironment(String environment) {
        ReflectionTestUtils.setField(emailVerificationService, "appEnvironment", environment);
    }

    private List<String> formattedLogMessages() {
        return logAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void requestCode_dev_logsRawCodeAndReturnsItInResponse() {
        setEnvironment("dev");

        String returnedCode = emailVerificationService.requestCode("candidate@example.com");

        assertThat(returnedCode).isNotNull().matches("\\d{6}");
        assertThat(formattedLogMessages())
                .anyMatch(message -> message.contains(returnedCode) && message.contains("candidate@example.com"));
    }

    @Test
    void requestCode_prod_neverLogsCodeOrEmail_andReturnsNull() {
        setEnvironment("prod");

        String returnedCode = emailVerificationService.requestCode("candidate@example.com");

        assertThat(returnedCode).isNull();

        assertThat(formattedLogMessages())
                .containsExactly("Verification email could not be sent because mail is not configured.");
    }

    @Test
    void requestCode_otherNonDevEnvironment_alsoLogsOnlyGenericWarning() {
        setEnvironment("staging");

        String returnedCode = emailVerificationService.requestCode("candidate@example.com");

        assertThat(returnedCode).isNull();
        assertThat(formattedLogMessages())
                .containsExactly("Verification email could not be sent because mail is not configured.");
    }

    @Test
    void requestCode_existingBehavior_persistsNewCodeRegardlessOfEnvironment() {
        setEnvironment("prod");

        emailVerificationService.requestCode("candidate@example.com");

        verify(emailVerificationCodeRepository).findAllByEmailAndUsedFalse("candidate@example.com");
        verify(emailVerificationCodeRepository).save(any());
    }
}
