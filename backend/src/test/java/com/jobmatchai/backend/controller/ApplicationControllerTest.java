package com.jobmatchai.backend.controller;

import com.jobmatchai.backend.model.Application;
import com.jobmatchai.backend.repository.ApplicationRepository;
import com.jobmatchai.backend.service.NotificationService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Covers PUT /api/applications/{id}/status - specifically the candidate-facing notification this
// endpoint fires when a company Accepts/Rejects an application (see ApplicationController#updateStatus).
// Ownership enforcement, the one-final-decision guard (which is also what prevents a duplicate
// notification from an unchanged resubmission), and the notification text itself (job title +
// company name) are all exercised directly against the controller, mirroring JobControllerTest's
// approach of mocking only the repositories/services the method under test actually touches.
@ExtendWith(MockitoExtension.class)
class ApplicationControllerTest {

    @Mock
    private ApplicationRepository applicationRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private Authentication authentication;

    private ApplicationController applicationController;

    @BeforeEach
    void setUp() {
        applicationController = new ApplicationController();
        ReflectionTestUtils.setField(applicationController, "applicationRepository", applicationRepository);
        ReflectionTestUtils.setField(applicationController, "notificationService", notificationService);
    }

    private Application pendingApplication(long id, String companyEmail) {
        // Application has no public id setter (it's @GeneratedValue-only) - not needed here
        // anyway, since updateStatus looks the row up by the path-variable id, never by
        // application.getId().
        Application application = new Application();
        application.setCandidateEmail("candidate@example.com");
        application.setCompanyEmail(companyEmail);
        application.setJobTitle("Backend Engineer");
        application.setCompanyName("Acme Corp");
        application.setStatus("AI Screening");
        return application;
    }

    // ---- Accepted ----

    @Test
    void updateStatus_accepting_notifiesCandidate_withJobTitleAndCompanyName() {
        long id = 10L;
        Application application = pendingApplication(id, "owner@company.com");
        when(applicationRepository.findById(id)).thenReturn(Optional.of(application));
        when(authentication.getName()).thenReturn("owner@company.com");
        when(applicationRepository.save(any(Application.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ApplicationController.StatusUpdateRequest request =
                new ApplicationController.StatusUpdateRequest("Accepted", "email", null, null, null);

        Map<String, Object> response = applicationController.updateStatus(id, request, authentication);

        assertThat(response.get("success")).isEqualTo(true);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationService).createNotification(
                eq("candidate@example.com"), eq("Application Accepted"), message.capture(), eq("APPLICATION_ACCEPTED"));

        assertThat(message.getValue())
                .contains("Backend Engineer")
                .contains("Acme Corp")
                .contains("accepted");
    }

    @Test
    void updateStatus_accepting_omitsCompanyName_whenNotAvailable() {
        long id = 11L;
        Application application = pendingApplication(id, "owner@company.com");
        application.setCompanyName(null);
        when(applicationRepository.findById(id)).thenReturn(Optional.of(application));
        when(authentication.getName()).thenReturn("owner@company.com");
        when(applicationRepository.save(any(Application.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ApplicationController.StatusUpdateRequest request =
                new ApplicationController.StatusUpdateRequest("Accepted", "email", null, null, null);

        applicationController.updateStatus(id, request, authentication);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationService).createNotification(
                eq("candidate@example.com"), eq("Application Accepted"), message.capture(), eq("APPLICATION_ACCEPTED"));

        // No dangling "at" when companyName is missing - reads as a complete sentence either way.
        assertThat(message.getValue()).doesNotContain(" at ").contains("Backend Engineer");
    }

    // ---- Stale rejectionReason is cleared on any non-Rejected transition ----

    @Test
    void updateStatus_accepting_clearsStaleRejectionReason() {
        long id = 12L;
        Application application = pendingApplication(id, "owner@company.com");
        application.setRejectionReason("Leftover reason from a data inconsistency");
        when(applicationRepository.findById(id)).thenReturn(Optional.of(application));
        when(authentication.getName()).thenReturn("owner@company.com");
        when(applicationRepository.save(any(Application.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ApplicationController.StatusUpdateRequest request =
                new ApplicationController.StatusUpdateRequest("Accepted", "email", null, null, null);

        applicationController.updateStatus(id, request, authentication);

        assertThat(application.getRejectionReason()).isNull();
    }

    @Test
    void updateStatus_shortlisting_clearsStaleRejectionReason() {
        long id = 13L;
        Application application = pendingApplication(id, "owner@company.com");
        application.setRejectionReason("Leftover reason from a data inconsistency");
        when(applicationRepository.findById(id)).thenReturn(Optional.of(application));
        when(authentication.getName()).thenReturn("owner@company.com");
        when(applicationRepository.save(any(Application.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ApplicationController.StatusUpdateRequest request =
                new ApplicationController.StatusUpdateRequest("Shortlisted", null, null, null, null);

        applicationController.updateStatus(id, request, authentication);

        assertThat(application.getRejectionReason()).isNull();
    }

    @Test
    void updateStatus_rejecting_notifiesCandidate_withJobTitleCompanyNameAndReason() {
        long id = 20L;
        Application application = pendingApplication(id, "owner@company.com");
        when(applicationRepository.findById(id)).thenReturn(Optional.of(application));
        when(authentication.getName()).thenReturn("owner@company.com");
        when(applicationRepository.save(any(Application.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ApplicationController.StatusUpdateRequest request =
                new ApplicationController.StatusUpdateRequest("Rejected", null, null, null, "Not enough backend experience");

        Map<String, Object> response = applicationController.updateStatus(id, request, authentication);

        assertThat(response.get("success")).isEqualTo(true);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationService).createNotification(
                eq("candidate@example.com"), eq("Application Rejected"), message.capture(), eq("APPLICATION_REJECTED"));

        assertThat(message.getValue())
                .contains("Backend Engineer")
                .contains("Acme Corp")
                .contains("rejected")
                .contains("Not enough backend experience");
    }

    // ---- Duplicate-notification guard (resubmitting an already-final decision) ----

    @Test
    void updateStatus_isRejected_whenApplicationAlreadyAccepted_andNeverNotifiesAgain() {
        long id = 30L;
        Application application = pendingApplication(id, "owner@company.com");
        application.setStatus("Accepted");
        when(applicationRepository.findById(id)).thenReturn(Optional.of(application));
        when(authentication.getName()).thenReturn("owner@company.com");

        ApplicationController.StatusUpdateRequest request =
                new ApplicationController.StatusUpdateRequest("Accepted", "email", null, null, null);

        Map<String, Object> response = applicationController.updateStatus(id, request, authentication);

        assertThat(response.get("success")).isEqualTo(false);
        verify(applicationRepository, never()).save(any(Application.class));
        verify(notificationService, never()).createNotification(any(), any(), any(), any());
    }

    @Test
    void updateStatus_isRejected_whenApplicationAlreadyRejected_andNeverNotifiesAgain() {
        long id = 31L;
        Application application = pendingApplication(id, "owner@company.com");
        application.setStatus("Rejected");
        when(applicationRepository.findById(id)).thenReturn(Optional.of(application));
        when(authentication.getName()).thenReturn("owner@company.com");

        ApplicationController.StatusUpdateRequest request =
                new ApplicationController.StatusUpdateRequest("Rejected", null, null, null, "Different reason this time");

        Map<String, Object> response = applicationController.updateStatus(id, request, authentication);

        assertThat(response.get("success")).isEqualTo(false);
        verify(applicationRepository, never()).save(any(Application.class));
        verify(notificationService, never()).createNotification(any(), any(), any(), any());
    }

    // ---- Ownership ----

    @Test
    void updateStatus_isRejected_whenCallerDoesNotOwnApplication_andNeverNotifies() {
        long id = 40L;
        Application application = pendingApplication(id, "real-owner@company.com");
        when(applicationRepository.findById(id)).thenReturn(Optional.of(application));
        when(authentication.getName()).thenReturn("attacker@evil.com");

        ApplicationController.StatusUpdateRequest request =
                new ApplicationController.StatusUpdateRequest("Accepted", "email", null, null, null);

        Map<String, Object> response = applicationController.updateStatus(id, request, authentication);

        assertThat(response.get("success")).isEqualTo(false);
        assertThat(application.getStatus()).isEqualTo("AI Screening");
        verify(applicationRepository, never()).save(any(Application.class));
        verify(notificationService, never()).createNotification(any(), any(), any(), any());
    }
}
