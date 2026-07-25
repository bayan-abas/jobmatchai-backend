package com.jobmatchai.backend.controller;

import com.jobmatchai.backend.dto.JobCreateRequest;
import com.jobmatchai.backend.model.Application;
import com.jobmatchai.backend.model.Job;
import com.jobmatchai.backend.model.JobStatus;
import com.jobmatchai.backend.repository.ApplicationRepository;
import com.jobmatchai.backend.repository.CandidateAiSummaryRepository;
import com.jobmatchai.backend.repository.JobMatchScoreRepository;
import com.jobmatchai.backend.repository.JobRepository;
import com.jobmatchai.backend.repository.UserRepository;
import com.jobmatchai.backend.service.NotificationService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobLifecycleFlowTest {

    @Mock
    private JobRepository jobRepository;

    @Mock
    private ApplicationRepository applicationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private JobMatchScoreRepository jobMatchScoreRepository;

    @Mock
    private CandidateAiSummaryRepository candidateAiSummaryRepository;

    private final Map<Long, Job> jobStore = new LinkedHashMap<>();
    private final List<Application> applicationStore = new ArrayList<>();
    private long nextJobId = 1L;
    private long nextApplicationId = 1L;

    private JobController jobController;
    private ApplicationController applicationController;

    @BeforeEach
    void setUp() {
        jobController = new JobController();
        ReflectionTestUtils.setField(jobController, "jobRepository", jobRepository);
        ReflectionTestUtils.setField(jobController, "applicationRepository", applicationRepository);
        ReflectionTestUtils.setField(jobController, "jobMatchScoreRepository", jobMatchScoreRepository);
        ReflectionTestUtils.setField(jobController, "candidateAiSummaryRepository", candidateAiSummaryRepository);

        applicationController = new ApplicationController();
        ReflectionTestUtils.setField(applicationController, "applicationRepository", applicationRepository);
        ReflectionTestUtils.setField(applicationController, "jobRepository", jobRepository);
        ReflectionTestUtils.setField(applicationController, "userRepository", userRepository);
        ReflectionTestUtils.setField(applicationController, "notificationService", notificationService);

        when(jobRepository.save(any(Job.class))).thenAnswer(invocation -> {
            Job job = invocation.getArgument(0);
            if (job.getId() == null) {
                job.setId(nextJobId++);
            }
            jobStore.put(job.getId(), job);
            return job;
        });
        when(jobRepository.findById(any())).thenAnswer(invocation ->
                Optional.ofNullable(jobStore.get((Long) invocation.getArgument(0))));
        when(jobRepository.findByStatus(any(JobStatus.class))).thenAnswer(invocation -> {
            JobStatus status = invocation.getArgument(0);
            return jobStore.values().stream().filter(j -> j.getStatus() == status).toList();
        });
        when(jobRepository.findAllById(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Iterable<Long> ids = (Iterable<Long>) invocation.getArgument(0);
            List<Job> result = new ArrayList<>();
            for (Long id : ids) {
                Job job = jobStore.get(id);
                if (job != null) {
                    result.add(job);
                }
            }
            return result;
        });

        when(applicationRepository.save(any(Application.class))).thenAnswer(invocation -> {
            Application application = invocation.getArgument(0);
            ReflectionTestUtils.setField(application, "id", nextApplicationId++);
            applicationStore.add(application);
            return application;
        });
        when(applicationRepository.findByCandidateEmailAndJobId(any(), any())).thenAnswer(invocation -> {
            String email = invocation.getArgument(0);
            Long jobId = invocation.getArgument(1);
            return applicationStore.stream()
                    .filter(a -> a.getCandidateEmail().equals(email) && a.getJobId().equals(jobId))
                    .findFirst();
        });
        when(applicationRepository.countByCandidateEmailAndCreatedAtAfter(any(), any())).thenReturn(0L);
        when(applicationRepository.findByCandidateEmail(any())).thenAnswer(invocation -> {
            String email = invocation.getArgument(0);
            return applicationStore.stream().filter(a -> a.getCandidateEmail().equals(email)).toList();
        });
        when(applicationRepository.findByJobId(any())).thenAnswer(invocation -> {
            Long jobId = invocation.getArgument(0);
            return applicationStore.stream().filter(a -> a.getJobId().equals(jobId)).toList();
        });
    }

    @Test
    void fullJobLifecycle_activeJobToClosedJob_behavesCorrectlyForEveryone() {
        Authentication companyAuth = mock(Authentication.class);
        when(companyAuth.getName()).thenReturn("company@example.com");

        JobCreateRequest createRequest = new JobCreateRequest(
                "Backend Engineer", "Acme Corp", "Remote", "Full-time",
                "$100k", "Build things", "5 years Java", "Java,Spring",
                null, null, null, null);
        Map<String, Object> createResponse = jobController.addJob(createRequest, companyAuth);
        assertThat(createResponse.get("success")).isEqualTo(true);
        Job job = (Job) createResponse.get("job");
        assertThat(job.getStatus()).isEqualTo(JobStatus.ACTIVE);

        List<Job> activeListing = jobController.getAllJobs();
        assertThat(activeListing).extracting(Job::getId).containsExactly(job.getId());

        Authentication candidateAuth = mock(Authentication.class);
        when(candidateAuth.getName()).thenReturn("candidate@example.com");

        ApplicationController.ApplyRequest applyRequest =
                new ApplicationController.ApplyRequest(job.getId(), job.getTitle(), job.getCompanyName(), null);
        Map<String, Object> applyResponse = applicationController.applyToJob(applyRequest, candidateAuth);
        assertThat(applyResponse.get("success")).isEqualTo(true);

        ResponseEntity<Map<String, Object>> closeResponse = jobController.updateJobStatus(
                job.getId(), new JobController.JobStatusUpdateRequest("CLOSED"), companyAuth);
        assertThat(closeResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(job.getStatus()).isEqualTo(JobStatus.CLOSED);

        List<Job> listingAfterClose = jobController.getAllJobs();
        assertThat(listingAfterClose).isEmpty();

        List<Application> candidateApplications = applicationController.getApplicationsByCandidate(candidateAuth);
        assertThat(candidateApplications).hasSize(1);
        assertThat(candidateApplications.get(0).getJobStatus()).isEqualTo("CLOSED");

        Authentication otherCandidateAuth = mock(Authentication.class);
        when(otherCandidateAuth.getName()).thenReturn("someone-else@example.com");
        Map<String, Object> secondApplyResponse = applicationController.applyToJob(applyRequest, otherCandidateAuth);
        assertThat(secondApplyResponse.get("success")).isEqualTo(false);
        assertThat(secondApplyResponse.get("message")).isEqualTo("This job is no longer accepting applications.");

        ResponseEntity<?> applicantsResponse = jobController.getApplicationsForJob(job.getId(), companyAuth);
        assertThat(applicantsResponse.getStatusCode().is2xxSuccessful()).isTrue();
        @SuppressWarnings("unchecked")
        List<JobController.JobApplicantView> applicants =
                (List<JobController.JobApplicantView>) applicantsResponse.getBody();
        assertThat(applicants).hasSize(1);
        assertThat(applicants.get(0).candidateEmail()).isEqualTo("candidate@example.com");
    }
}
