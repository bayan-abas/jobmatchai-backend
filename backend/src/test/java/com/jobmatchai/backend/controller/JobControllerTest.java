package com.jobmatchai.backend.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobmatchai.backend.dto.JobCreateRequest;
import com.jobmatchai.backend.model.Job;
import com.jobmatchai.backend.repository.JobRepository;

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Regression coverage for the critical job mass-assignment/IDOR fix: POST /api/jobs/add used to
// bind the Job JPA entity directly to @RequestBody. Since Job.id has a public setter, a client
// could supply "id" for an EXISTING job, and Spring Data JPA's save() treats a non-null id as an
// update (merge) rather than an insert - letting any authenticated company hijack another
// company's job posting (and, via companyEmail, everything gated on job ownership downstream:
// applicant lists, resumes, messaging). addJob now binds a dedicated JobCreateRequest DTO with no
// id/companyEmail component at all, and always builds a brand-new Job() before saving.
@ExtendWith(MockitoExtension.class)
class JobControllerTest {

    @Mock
    private JobRepository jobRepository;

    @Mock
    private Authentication authentication;

    private JobController jobController;

    // Mirrors Spring's actual @RequestBody deserialization behavior - Spring's
    // Jackson2ObjectMapperBuilder disables FAIL_ON_UNKNOWN_PROPERTIES by default, unlike a bare
    // `new ObjectMapper()` - so this test reflects what the real endpoint does with an
    // unrecognized "id"/"companyEmail" field in the request JSON, not a stricter or looser
    // behavior than production.
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

    // ---- addJob (POST /api/jobs/add) ----

    @Test
    void addJob_createsNewJob_withFieldsFromRequestAndOwnerFromAuthentication() {
        when(authentication.getName()).thenReturn("owner@company.com");
        when(jobRepository.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));

        JobCreateRequest request = new JobCreateRequest(
                "Backend Engineer", "Acme Corp", "Remote", "Full-time",
                "$100k-$120k", "Build things", "5 years Java", "Java,Spring");

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
        // Simulates the exact attack this fix closes: a client POSTs a JSON body with "id" set to
        // an EXISTING job's id, hoping save() will merge into (and take ownership of) that row.
        // JobCreateRequest has no "id" component, so Jackson silently drops the unrecognized field
        // during deserialization - there is nothing left in the DTO for the controller to act on.
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
        // No id was ever set on the entity handed to save() - Spring Data JPA can only insert a
        // brand-new row here, never merge into (and thereby hijack) job id=42.
        assertThat(saved.getId()).isNull();
        assertThat(saved.getTitle()).isEqualTo("Hijacked Posting");

        // addJob never looks up an existing job by id at all - the pre-existing victim job
        // (constructed here only as the thing that must remain untouched) is never passed to any
        // repository method.
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

    // ---- updateJob (PUT /api/jobs/{id}) - unchanged by this fix, still covered ----

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
}
