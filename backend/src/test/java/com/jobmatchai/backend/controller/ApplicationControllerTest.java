package com.jobmatchai.backend.controller;

import com.jobmatchai.backend.model.Application;
import com.jobmatchai.backend.model.Job;
import com.jobmatchai.backend.model.JobStatus;
import com.jobmatchai.backend.repository.ApplicationRepository;
import com.jobmatchai.backend.repository.JobRepository;
import com.jobmatchai.backend.repository.UserRepository;
import com.jobmatchai.backend.service.NotificationService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApplicationControllerTest {

    @Mock
    private ApplicationRepository applicationRepository;

    @Mock
    private JobRepository jobRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private Authentication authentication;

    private ApplicationController applicationController;

    @BeforeEach
    void setUp() {
        applicationController = new ApplicationController();
        ReflectionTestUtils.setField(applicationController, "applicationRepository", applicationRepository);
        ReflectionTestUtils.setField(applicationController, "jobRepository", jobRepository);
        ReflectionTestUtils.setField(applicationController, "userRepository", userRepository);
        ReflectionTestUtils.setField(applicationController, "notificationService", notificationService);
    }

    private Job jobWithStatus(long id, String companyEmail, JobStatus status) {
        Job job = new Job();
        job.setId(id);
        job.setTitle("Backend Engineer");
        job.setCompanyName("Acme Corp");
        job.setCompanyEmail(companyEmail);
        job.setStatus(status);
        return job;
    }

    private Application pendingApplication(long id, String companyEmail) {

        Application application = new Application();
        application.setCandidateEmail("candidate@example.com");
        application.setCompanyEmail(companyEmail);
        application.setJobTitle("Backend Engineer");
        application.setCompanyName("Acme Corp");
        application.setStatus("AI Screening");
        return application;
    }

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

        assertThat(message.getValue()).doesNotContain(" at ").contains("Backend Engineer");
    }

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

    @Test
    void applyToJob_isRejected_whenJobIsClosed_evenWhenCalledDirectlyWithoutGoingThroughTheListing() {
        Job closedJob = jobWithStatus(50L, "owner@company.com", JobStatus.CLOSED);
        when(jobRepository.findById(50L)).thenReturn(Optional.of(closedJob));
        when(authentication.getName()).thenReturn("candidate@example.com");

        ApplicationController.ApplyRequest request =
                new ApplicationController.ApplyRequest(50L, "Backend Engineer", "Acme Corp", null);

        Map<String, Object> response = applicationController.applyToJob(request, authentication);

        assertThat(response.get("success")).isEqualTo(false);
        assertThat(response.get("message")).isEqualTo("This job is no longer accepting applications.");
        verify(applicationRepository, never()).save(any(Application.class));
        verify(notificationService, never()).createNotification(any(), any(), any(), any());
    }

    @Test
    void applyToJob_succeeds_whenJobIsActive() {
        Job activeJob = jobWithStatus(51L, "owner@company.com", JobStatus.ACTIVE);
        when(jobRepository.findById(51L)).thenReturn(Optional.of(activeJob));
        when(authentication.getName()).thenReturn("candidate@example.com");
        when(applicationRepository.findByCandidateEmailAndJobId("candidate@example.com", 51L))
                .thenReturn(Optional.empty());
        when(applicationRepository.save(any(Application.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ApplicationController.ApplyRequest request =
                new ApplicationController.ApplyRequest(51L, "Backend Engineer", "Acme Corp", null);

        Map<String, Object> response = applicationController.applyToJob(request, authentication);

        assertThat(response.get("success")).isEqualTo(true);
        verify(applicationRepository).save(any(Application.class));
    }

    @Test
    void getApplicationsByCandidate_enrichesEachApplicationWithTheJobsCurrentStatus() {
        Application forClosedJob = pendingApplication(1L, "owner@company.com");
        forClosedJob.setJobId(60L);
        Application forActiveJob = pendingApplication(2L, "owner@company.com");
        forActiveJob.setJobId(61L);

        when(authentication.getName()).thenReturn("candidate@example.com");
        when(applicationRepository.findByCandidateEmail("candidate@example.com"))
                .thenReturn(List.of(forClosedJob, forActiveJob));
        when(jobRepository.findAllById(List.of(60L, 61L))).thenReturn(List.of(
                jobWithStatus(60L, "owner@company.com", JobStatus.CLOSED),
                jobWithStatus(61L, "owner@company.com", JobStatus.ACTIVE)));

        List<Application> result = applicationController.getApplicationsByCandidate(authentication);

        assertThat(result).hasSize(2);
        assertThat(forClosedJob.getJobStatus()).isEqualTo("CLOSED");
        assertThat(forActiveJob.getJobStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void getApplicationsByCandidate_setsNullJobStatus_whenTheJobNoLongerExists() {
        Application forDeletedJob = pendingApplication(3L, "owner@company.com");
        forDeletedJob.setJobId(70L);

        when(authentication.getName()).thenReturn("candidate@example.com");
        when(applicationRepository.findByCandidateEmail("candidate@example.com"))
                .thenReturn(List.of(forDeletedJob));
        when(jobRepository.findAllById(List.of(70L))).thenReturn(List.of());

        List<Application> result = applicationController.getApplicationsByCandidate(authentication);

        assertThat(result).hasSize(1);
        assertThat(forDeletedJob.getJobStatus()).isNull();
    }
}
