package com.jobmatchai.backend.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobmatchai.backend.dto.JobCreateRequest;
import com.jobmatchai.backend.model.Job;
import com.jobmatchai.backend.model.JobStatus;
import com.jobmatchai.backend.repository.JobRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobControllerTest {

    @Mock
    private JobRepository jobRepository;

    @Mock
    private Authentication authentication;

    private JobController jobController;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @BeforeEach
    void setUp() {
        jobController = new JobController();
        ReflectionTestUtils.setField(jobController, "jobRepository", jobRepository);
    }

    private Job existingJobOwnedBy(long id, String companyEmail) {
        Job job = new Job();
        job.setId(id);
        job.setTitle("Original Title");
        job.setCompanyName("Original Co");
        job.setCompanyEmail(companyEmail);
        return job;
    }

    @Test
    void addJob_createsNewJob_withFieldsFromRequestAndOwnerFromAuthentication() {
        when(authentication.getName()).thenReturn("owner@company.com");
        when(jobRepository.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));

        JobCreateRequest request = new JobCreateRequest(
                "Backend Engineer", "Acme Corp", "Remote", "Full-time",
                "$100k-$120k", "Build things", "5 years Java", "Java,Spring",
                null, null, null, null);

        Map<String, Object> response = jobController.addJob(request, authentication);

        assertThat(response.get("success")).isEqualTo(true);

        ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
        verify(jobRepository).save(captor.capture());

        Job saved = captor.getValue();
        assertThat(saved.getId()).isNull();
        assertThat(saved.getTitle()).isEqualTo("Backend Engineer");
        assertThat(saved.getCompanyName()).isEqualTo("Acme Corp");
        assertThat(saved.getLocation()).isEqualTo("Remote");
        assertThat(saved.getType()).isEqualTo("Full-time");
        assertThat(saved.getSalary()).isEqualTo("$100k-$120k");
        assertThat(saved.getDescription()).isEqualTo("Build things");
        assertThat(saved.getRequirements()).isEqualTo("5 years Java");
        assertThat(saved.getSkills()).isEqualTo("Java,Spring");
        assertThat(saved.getCompanyEmail()).isEqualTo("owner@company.com");
    }

    @Test
    void addJob_ignoresClientSuppliedId_evenWhenPresentInRawJson_soExistingJobIsNeverOverwritten() throws Exception {

        Job existingJob = existingJobOwnedBy(42L, "victim-company@example.com");
        when(authentication.getName()).thenReturn("attacker@evil.com");
        when(jobRepository.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));

        String maliciousJson = """
                {
                  "id": 42,
                  "companyEmail": "victim-company@example.com",
                  "title": "Hijacked Posting",
                  "companyName": "Attacker Inc",
                  "description": "Malicious update"
                }
                """;

        JobCreateRequest request = objectMapper.readValue(maliciousJson, JobCreateRequest.class);

        jobController.addJob(request, authentication);

        ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
        verify(jobRepository).save(captor.capture());

        Job saved = captor.getValue();

        assertThat(saved.getId()).isNull();
        assertThat(saved.getTitle()).isEqualTo("Hijacked Posting");

        verify(jobRepository, never()).findById(any());
        assertThat(existingJob.getCompanyEmail()).isEqualTo("victim-company@example.com");
        assertThat(existingJob.getTitle()).isEqualTo("Original Title");
    }

    @Test
    void addJob_alwaysUsesAuthenticatedCallerAsOwner_evenWhenCompanyEmailIsSuppliedInRawJson() throws Exception {
        when(authentication.getName()).thenReturn("real-owner@company.com");
        when(jobRepository.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));

        String jsonWithSpoofedOwner = """
                {
                  "companyEmail": "someone-else@company.com",
                  "title": "Legit Job Title",
                  "description": "A real job"
                }
                """;

        JobCreateRequest request = objectMapper.readValue(jsonWithSpoofedOwner, JobCreateRequest.class);

        jobController.addJob(request, authentication);

        ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
        verify(jobRepository).save(captor.capture());

        assertThat(captor.getValue().getCompanyEmail()).isEqualTo("real-owner@company.com");
    }

    @Test
    void updateJob_updatesExistingJob_whenCallerOwnsIt() {
        long jobId = 7L;
        Job existingJob = existingJobOwnedBy(jobId, "owner@company.com");
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(existingJob));
        when(authentication.getName()).thenReturn("owner@company.com");
        when(jobRepository.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Job updatePayload = new Job();
        updatePayload.setTitle("Updated Title");
        updatePayload.setCompanyName("Updated Co");
        updatePayload.setLocation("Updated Location");
        updatePayload.setType("Part-time");
        updatePayload.setSalary("$150k");
        updatePayload.setDescription("Updated description");
        updatePayload.setRequirements("Updated requirements");
        updatePayload.setSkills("Updated skills");

        Map<String, Object> response = jobController.updateJob(jobId, updatePayload, authentication);

        assertThat(response.get("success")).isEqualTo(true);
        assertThat(existingJob.getTitle()).isEqualTo("Updated Title");
        assertThat(existingJob.getCompanyEmail()).isEqualTo("owner@company.com");
        verify(jobRepository).save(existingJob);
    }

    @Test
    void updateJob_isRejected_whenCallerDoesNotOwnJob() {
        long jobId = 7L;
        Job existingJob = existingJobOwnedBy(jobId, "real-owner@company.com");
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(existingJob));
        when(authentication.getName()).thenReturn("attacker@evil.com");

        Job updatePayload = new Job();
        updatePayload.setTitle("Hijacked Title");

        Map<String, Object> response = jobController.updateJob(jobId, updatePayload, authentication);

        assertThat(response.get("success")).isEqualTo(false);
        assertThat(existingJob.getTitle()).isEqualTo("Original Title");
        verify(jobRepository, never()).save(any(Job.class));
    }

    @Test
    void getAllJobs_returnsOnlyActiveJobs_neverAllJobsRegardlessOfStatus() {
        Job activeJob = existingJobOwnedBy(1L, "owner@company.com");
        when(jobRepository.findByStatus(JobStatus.ACTIVE)).thenReturn(List.of(activeJob));

        List<Job> result = jobController.getAllJobs();

        assertThat(result).containsExactly(activeJob);
        verify(jobRepository, never()).findAll();
    }

    @Test
    void updateJobStatus_closesJob_whenCallerOwnsIt() {
        long jobId = 7L;
        Job existingJob = existingJobOwnedBy(jobId, "owner@company.com");
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(existingJob));
        when(authentication.getName()).thenReturn("owner@company.com");
        when(jobRepository.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<Map<String, Object>> response = jobController.updateJobStatus(
                jobId, new JobController.JobStatusUpdateRequest("CLOSED"), authentication);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody().get("success")).isEqualTo(true);
        assertThat(existingJob.getStatus()).isEqualTo(JobStatus.CLOSED);
        verify(jobRepository).save(existingJob);
    }

    @Test
    void updateJobStatus_reopensClosedJob_whenCallerOwnsIt() {
        long jobId = 8L;
        Job existingJob = existingJobOwnedBy(jobId, "owner@company.com");
        existingJob.setStatus(JobStatus.CLOSED);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(existingJob));
        when(authentication.getName()).thenReturn("owner@company.com");
        when(jobRepository.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<Map<String, Object>> response = jobController.updateJobStatus(
                jobId, new JobController.JobStatusUpdateRequest("active"), authentication);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(existingJob.getStatus()).isEqualTo(JobStatus.ACTIVE);
    }

    @Test
    void updateJobStatus_isRejected_whenCallerDoesNotOwnJob() {
        long jobId = 9L;
        Job existingJob = existingJobOwnedBy(jobId, "real-owner@company.com");
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(existingJob));
        when(authentication.getName()).thenReturn("attacker@evil.com");

        ResponseEntity<Map<String, Object>> response = jobController.updateJobStatus(
                jobId, new JobController.JobStatusUpdateRequest("CLOSED"), authentication);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(existingJob.getStatus()).isEqualTo(JobStatus.ACTIVE);
        verify(jobRepository, never()).save(any(Job.class));
    }

    @Test
    void updateJobStatus_isRejected_whenStatusValueIsInvalid() {
        long jobId = 10L;
        Job existingJob = existingJobOwnedBy(jobId, "owner@company.com");
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(existingJob));
        when(authentication.getName()).thenReturn("owner@company.com");

        ResponseEntity<Map<String, Object>> response = jobController.updateJobStatus(
                jobId, new JobController.JobStatusUpdateRequest("PAUSED"), authentication);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(existingJob.getStatus()).isEqualTo(JobStatus.ACTIVE);
        verify(jobRepository, never()).save(any(Job.class));
    }
}
