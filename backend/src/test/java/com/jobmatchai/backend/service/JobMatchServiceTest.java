package com.jobmatchai.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobmatchai.backend.model.CVAnalysis;
import com.jobmatchai.backend.model.Job;
import com.jobmatchai.backend.model.JobMatchScore;
import com.jobmatchai.backend.repository.CVAnalysisRepository;
import com.jobmatchai.backend.repository.JobMatchScoreRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// Exercises JobMatchService against a mocked OpenAICVAnalysisService, so these run without any
// real OpenAI call / API key. The AI is only ever stubbed to return CLASSIFICATIONS (a field
// relation bucket, a mandatory/preferred skill split, required-level labels) - never a raw
// score - matching the real contract: the AI classifies, MatchScoreCalculator computes every
// percentage from those classifications. These tests pin down that backend math/validation,
// independent of what a live model happens to say.
@ExtendWith(MockitoExtension.class)
class JobMatchServiceTest {

    @Mock
    private CVAnalysisRepository cvAnalysisRepository;

    @Mock
    private JobMatchScoreRepository jobMatchScoreRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private OpenAICVAnalysisService openAICVAnalysisService;

    private JobMatchService jobMatchService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final String EMAIL = "candidate@example.com";

    @BeforeEach
    void setUp() {
        jobMatchService = new JobMatchService();
        ReflectionTestUtils.setField(jobMatchService, "cvAnalysisRepository", cvAnalysisRepository);
        ReflectionTestUtils.setField(jobMatchService, "jobMatchScoreRepository", jobMatchScoreRepository);
        ReflectionTestUtils.setField(jobMatchService, "notificationService", notificationService);
        ReflectionTestUtils.setField(jobMatchService, "openAICVAnalysisService", openAICVAnalysisService);

        // cvAnalysisRepository.findByUserEmail is stubbed per-test (every test needs a
        // different CVAnalysis fixture), so it is deliberately not given a blanket default here.
        when(jobMatchScoreRepository.findByCandidateEmailAndJobIdIn(eq(EMAIL), anyList())).thenReturn(List.of());
        // lenient(): legitimately unused in the mismatched-fingerprint test, which asserts
        // save() is NEVER called for an unvalidated result - that's the behavior under test,
        // not an oversight, so it must not trip strict-stubs' unused-stub check.
        lenient().when(jobMatchScoreRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ---- fixtures ----

    private CVAnalysis doctorAnalysis() {
        CVAnalysis a = new CVAnalysis();
        a.setUserEmail(EMAIL);
        a.setCandidateField("healthcare");
        a.setProfessionTitle("Doctor");
        a.setYearsOfExperience("8+");
        a.setExperienceLevel("senior_level");
        a.setPreviousJobTitles("Attending Physician, Resident Physician");
        a.setTechnicalSkills("Patient diagnosis, Patient care, Prescribing medication, Surgical procedures");
        a.setSoftSkills("communication, empathy, teamwork");
        a.setLanguages("English (fluent)");
        a.setEducationEvidence("relevant_degree");
        a.setCertificationsEvidence("field_relevant");
        a.setLicensesEvidence("licensed");
        a.setSkills("Patient diagnosis, Patient care, Prescribing medication, Surgical procedures");
        a.setSummary("An experienced licensed physician with 8+ years treating patients.");
        a.setStrengths("Holds a medical degree and an active medical license; strong clinical judgement.");
        a.setMissingSkills("");
        a.setRecommendedRoles("Physician, Attending Doctor");
        a.setOverallScore(85);
        a.setCvTextHash("doctor-cv-hash");
        return a;
    }

    private CVAnalysis infoSystemsGradAnalysis() {
        CVAnalysis a = new CVAnalysis();
        a.setUserEmail(EMAIL);
        a.setCandidateField("software");
        a.setProfessionTitle("Information Systems Analyst");
        a.setYearsOfExperience("0-1");
        a.setExperienceLevel("entry_level");
        a.setPreviousJobTitles("IT Intern");
        a.setTechnicalSkills("SQL, Python, ERP systems, CRM systems, test automation basics, Java");
        a.setSoftSkills("communication, teamwork");
        a.setLanguages("English (fluent)");
        a.setEducationEvidence("relevant_degree");
        a.setCertificationsEvidence("general");
        a.setLicensesEvidence("none");
        a.setSkills("SQL, Python, ERP systems, CRM systems, test automation basics, Java");
        a.setSummary("A recent Information Systems graduate with coursework in databases and ERP/CRM platforms.");
        a.setStrengths("Holds a B.Sc. in Information Systems; hands-on coursework projects with SQL, ERP and CRM systems.");
        a.setMissingSkills("");
        a.setRecommendedRoles("QA Engineer, ERP Analyst, Support Engineer");
        a.setOverallScore(65);
        a.setCvTextHash("is-grad-cv-hash");
        return a;
    }

    private Job job(long id, String title, String skills, String requirements) {
        Job job = new Job(title, "Acme Co", null, "Tel Aviv", "Full-time", "20000",
                "Description mentioning " + skills, requirements, skills);
        job.setId(id);
        return job;
    }

    // Matches the AI response schema: a classification bucket + skill lists + required-level
    // labels - never a raw score. MatchScoreCalculator turns every one of these into a number.
    private record MatchFixture(
            String fieldRelationCloseness,
            String matchReason,
            List<String> matchedMandatory,
            List<String> missingMandatory,
            List<String> matchedPreferred,
            List<String> missingPreferred,
            String requiredExperienceLevel,
            String requiredEducationLevel,
            String requiredCertificationLevel
    ) {}

    private String buildResponseJson(Job job, String fingerprint, MatchFixture fx) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("jobId", job.getId());
        entry.put("jobTitle", job.getTitle());
        entry.put("jobFingerprint", fingerprint);
        entry.put("fieldRelationCloseness", fx.fieldRelationCloseness());
        entry.put("matchReason", fx.matchReason());
        entry.put("matchedMandatorySkills", fx.matchedMandatory());
        entry.put("missingMandatorySkills", fx.missingMandatory());
        entry.put("matchedPreferredSkills", fx.matchedPreferred());
        entry.put("missingPreferredSkills", fx.missingPreferred());
        entry.put("requiredExperienceLevel", fx.requiredExperienceLevel());
        entry.put("requiredEducationLevel", fx.requiredEducationLevel());
        entry.put("requiredCertificationLevel", fx.requiredCertificationLevel());

        try {
            return objectMapper.writeValueAsString(Map.of("matches", List.of(entry)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private MatchFixture relatedFixture(
            String closeness, List<String> matchedMandatory, List<String> missingMandatory,
            String requiredExperienceLevel, String requiredEducationLevel, String requiredCertificationLevel) {
        return new MatchFixture(closeness, "You are a strong match for this role.",
                matchedMandatory, missingMandatory, List.of(), List.of(),
                requiredExperienceLevel, requiredEducationLevel, requiredCertificationLevel);
    }

    private MatchFixture unrelatedFixture(String candidateField, String jobField) {
        return new MatchFixture("unrelated",
                "Your background is in " + candidateField + ", and this role is in " + jobField + ", an unrelated field.",
                List.of(), List.of(), List.of(), List.of(), null, null, null);
    }

    // Stubs computeJobMatches to answer per-request using whatever job/fingerprint it was
    // actually called with, keyed by jobId - this is what lets the same stub correctly serve
    // single-job requests for two different jobs without any manual sequencing.
    private void stubAi(Map<Long, MatchFixture> fixturesByJobId) {
        when(openAICVAnalysisService.computeJobMatches(any(), anyList(), anyMap(), any(), any()))
                .thenAnswer(invocation -> {
                    List<Job> jobs = invocation.getArgument(1);
                    Map<Long, String> fingerprints = invocation.getArgument(2);
                    Job requestedJob = jobs.get(0);
                    MatchFixture fixture = fixturesByJobId.get(requestedJob.getId());
                    return buildResponseJson(requestedJob, fingerprints.get(requestedJob.getId()), fixture);
                });
    }

    // ---- scenario 1: licensed doctor CV against physician job -> same_role, scored high ----

    @Test
    void licensedDoctorCv_vsPhysicianJob_isRelatedAndScoredHigh() {
        when(cvAnalysisRepository.findByUserEmail(EMAIL)).thenReturn(Optional.of(doctorAnalysis()));
        Job physicianJob = job(1L, "Physician", "Patient diagnosis, Patient care, Prescribing medication",
                "Must hold an active medical license.");

        stubAi(Map.of(1L, relatedFixture("same_role",
                List.of("Patient diagnosis", "Patient care", "Prescribing medication"), List.of(),
                "mid", "relevant_degree", "specific_license")));

        JobMatchService.MatchScoresResult result = jobMatchService.getMatchScores(EMAIL, List.of(physicianJob), "en");

        Map<String, Object> match = result.matches().get(0);
        assertThat(match.get("fieldRelated")).isEqualTo(true);
        assertThat((Integer) match.get("matchPercent")).isGreaterThanOrEqualTo(80);
        assertThat(match.get("skillsMatchPercent")).isEqualTo(100);
    }

    // ---- scenario 2: doctor CV against nurse job -> still related (same broad field, different
    // specific role) - this is the exact case that used to incorrectly show "-" ----

    @Test
    void doctorCv_vsNurseJob_isRelatedButWithAReducedScore() {
        when(cvAnalysisRepository.findByUserEmail(EMAIL)).thenReturn(Optional.of(doctorAnalysis()));
        Job nurseJob = job(2L, "Registered Nurse", "Patient care, Nursing license, Medication administration",
                "Requires an active nursing license.");

        stubAi(Map.of(2L, relatedFixture("same_broad_field",
                List.of("Patient care"), List.of("Nursing license", "Medication administration"),
                "mid", null, null)));

        JobMatchService.MatchScoresResult result = jobMatchService.getMatchScores(EMAIL, List.of(nurseJob), "en");

        Map<String, Object> match = result.matches().get(0);
        assertThat(match.get("fieldRelated"))
                .as("a doctor and a nurse share the same broad medical field - this must not be a field mismatch")
                .isEqualTo(true);
        assertThat(match.get("matchPercent")).isNotNull();
        // same_broad_field (55) is deterministically lower than same_role (95) - the "different
        // specific role" gap shows up here, and the missing nursing-specific skills pull the
        // skills component down too - together demonstrating a real, reduced-but-present score.
        assertThat((Integer) match.get("fieldRelevancePercent")).isEqualTo(55);
        assertThat((Integer) match.get("skillsMatchPercent")).isLessThan(50);
    }

    // ---- scenario 3: doctor CV against software developer job -> genuinely unrelated ----

    @Test
    void doctorCv_vsSoftwareDeveloperJob_isUnrelated() {
        when(cvAnalysisRepository.findByUserEmail(EMAIL)).thenReturn(Optional.of(doctorAnalysis()));
        Job devJob = job(3L, "Software Developer", "Java, Spring Boot, SQL", "3+ years of backend development.");

        stubAi(Map.of(3L, unrelatedFixture("medicine", "software engineering")));

        JobMatchService.MatchScoresResult result = jobMatchService.getMatchScores(EMAIL, List.of(devJob), "en");

        Map<String, Object> match = result.matches().get(0);
        assertThat(match.get("fieldRelated")).isEqualTo(false);
        assertThat(match.get("matchPercent")).isNull();
    }

    // ---- regression (found via live verification): a doctor CV against a general/vocational
    // role (cashier, cleaner, retail, customer service...) must still get a real percentage -
    // almost any reliable adult can do these regardless of specialized background. The AI is
    // asked to follow this, but the backend enforces it independent of AI compliance. ----

    @Test
    void doctorCv_vsGeneralVocationalRole_isRelatedEvenWhenAiSaysUnrelated() {
        when(cvAnalysisRepository.findByUserEmail(EMAIL)).thenReturn(Optional.of(doctorAnalysis()));
        Job cleanerJob = job(12L, "Office Cleaner", "Cleaning, Attention to detail",
                "Entry-level, flexible hours, no experience required.");

        // Simulates the AI following its (incomplete) instinct to call a doctor's background
        // "unrelated" to cleaning - the backend override must still produce a related, scored
        // result regardless.
        stubAi(Map.of(12L, unrelatedFixture("medicine", "cleaning")));

        JobMatchService.MatchScoresResult result = jobMatchService.getMatchScores(EMAIL, List.of(cleanerJob), "en");

        Map<String, Object> match = result.matches().get(0);
        assertThat(match.get("fieldRelated"))
                .as("a general/vocational role must never show as a field mismatch, regardless of the candidate's specialized background")
                .isEqualTo(true);
        assertThat(match.get("matchPercent")).isNotNull();
        assertThat((Integer) match.get("fieldRelevancePercent")).isEqualTo(85);
    }

    // ---- scenario 4: Information Systems graduate against a junior automation job -> related ----

    @Test
    void infoSystemsGrad_vsJuniorAutomationJob_isRelated() {
        when(cvAnalysisRepository.findByUserEmail(EMAIL)).thenReturn(Optional.of(infoSystemsGradAnalysis()));
        Job automationJob = job(4L, "Junior Automation Engineer", "Python, test automation basics",
                "Entry-level role, training provided.");

        stubAi(Map.of(4L, relatedFixture("same_broad_field",
                List.of("Python", "test automation basics"), List.of(),
                "entry", null, null)));

        JobMatchService.MatchScoresResult result = jobMatchService.getMatchScores(EMAIL, List.of(automationJob), "en");

        Map<String, Object> match = result.matches().get(0);
        assertThat(match.get("fieldRelated")).isEqualTo(true);
        assertThat(match.get("matchPercent")).isNotNull();
    }

    // ---- scenario 5: Information Systems graduate against an ERP/CRM implementation job -> related ----

    @Test
    void infoSystemsGrad_vsErpCrmJob_isRelated() {
        when(cvAnalysisRepository.findByUserEmail(EMAIL)).thenReturn(Optional.of(infoSystemsGradAnalysis()));
        Job erpJob = job(5L, "ERP/CRM Implementation Consultant", "ERP systems, CRM systems",
                "Support ERP and CRM rollouts for clients.");

        stubAi(Map.of(5L, relatedFixture("same_broad_field",
                List.of("ERP systems", "CRM systems"), List.of(),
                "entry", "any_degree", null)));

        JobMatchService.MatchScoresResult result = jobMatchService.getMatchScores(EMAIL, List.of(erpJob), "en");

        Map<String, Object> match = result.matches().get(0);
        assertThat(match.get("fieldRelated")).isEqualTo(true);
        assertThat(match.get("matchPercent")).isNotNull();
    }

    // ---- scenario 6: junior candidate against a senior job in the SAME field -> related, but
    // the experience component (and so the overall score) should be pulled down, not fieldRelated ----

    @Test
    void juniorCandidate_vsSeniorJobSameField_isRelatedWithLowExperienceComponent() {
        when(cvAnalysisRepository.findByUserEmail(EMAIL)).thenReturn(Optional.of(infoSystemsGradAnalysis()));
        Job seniorJob = job(6L, "Senior Software Architect", "Java, System design", "10+ years required.");

        stubAi(Map.of(6L, relatedFixture("same_broad_field",
                List.of("Java"), List.of("System design"),
                "senior", null, null)));

        JobMatchService.MatchScoresResult result = jobMatchService.getMatchScores(EMAIL, List.of(seniorJob), "en");

        Map<String, Object> match = result.matches().get(0);
        assertThat(match.get("fieldRelated")).isEqualTo(true);
        // entry_level candidate (rank 1) vs required "senior" (rank 3): 100 - 2*40 = 20.
        assertThat(match.get("experienceMatchPercent")).isEqualTo(20);
    }

    // ---- scenario 7: candidate missing one mandatory (certification-flavored) requirement ->
    // related, reduced score, fieldRelated must NOT flip to false ----

    @Test
    void missingMandatoryCertification_reducesScoreButStaysFieldRelated() {
        when(cvAnalysisRepository.findByUserEmail(EMAIL)).thenReturn(Optional.of(doctorAnalysis()));
        Job surgeonJob = job(7L, "Board-Certified Surgeon",
                "Surgical procedures, Board certification in surgery",
                "Requires board certification in surgery specifically.");

        stubAi(Map.of(7L, relatedFixture("same_specialization",
                List.of("Surgical procedures"), List.of("Board certification in surgery"),
                "mid", null, null)));

        JobMatchService.MatchScoresResult result = jobMatchService.getMatchScores(EMAIL, List.of(surgeonJob), "en");

        Map<String, Object> match = result.matches().get(0);
        assertThat(match.get("fieldRelated"))
                .as("a missing mandatory certification must lower the score, not flip fieldRelated to false")
                .isEqualTo(true);
        assertThat(match.get("matchPercent")).isNotNull();
        // 1 matched + 1 missing mandatory -> computeSkillsScore(1,1,0,0) = 50.
        assertThat(match.get("skillsMatchPercent")).isEqualTo(50);
    }

    // ---- scenario 8: same CV and same job, calculated multiple times -> identical score, and
    // the AI is only called once total (the second call is served from the cache) ----

    @Test
    void sameCvAndJob_computedTwice_isIdenticalAndDoesNotRecallAi() {
        when(cvAnalysisRepository.findByUserEmail(EMAIL)).thenReturn(Optional.of(doctorAnalysis()));
        Job physicianJob = job(8L, "Physician", "Patient diagnosis, Patient care", "Active medical license required.");

        stubAi(Map.of(8L, relatedFixture("same_role",
                List.of("Patient diagnosis", "Patient care"), List.of(),
                "mid", "relevant_degree", "specific_license")));

        JobMatchService.MatchScoresResult first = jobMatchService.getMatchScores(EMAIL, List.of(physicianJob), "en");
        Integer firstPercent = (Integer) first.matches().get(0).get("matchPercent");

        // Simulate the cache now holding the row ensureCoreScores just saved, matching the same
        // CV fingerprint (candidate is unchanged) and job fingerprint (job content is unchanged).
        ArgumentCaptor<JobMatchScore> savedCaptor = ArgumentCaptor.forClass(JobMatchScore.class);
        verify(jobMatchScoreRepository).save(savedCaptor.capture());
        JobMatchScore saved = savedCaptor.getValue();
        when(jobMatchScoreRepository.findByCandidateEmailAndJobIdIn(eq(EMAIL), anyList())).thenReturn(List.of(saved));

        JobMatchService.MatchScoresResult second = jobMatchService.getMatchScores(EMAIL, List.of(physicianJob), "en");
        Integer secondPercent = (Integer) second.matches().get(0).get("matchPercent");

        assertThat(secondPercent).isEqualTo(firstPercent);
        verify(openAICVAnalysisService, times(1)).computeJobMatches(any(), anyList(), anyMap(), any(), any());
    }

    // ---- scenario 9: two different jobs scored in the same request -> each gets its OWN
    // correct verdict, with no risk of one job's verdict leaking onto the other (chunk size 1
    // makes cross-job mixing structurally impossible, not just less likely) ----

    @Test
    void twoDifferentJobsInSameRequest_areScoredIndependentlyWithoutMixing() {
        when(cvAnalysisRepository.findByUserEmail(EMAIL)).thenReturn(Optional.of(doctorAnalysis()));
        Job physicianJob = job(9L, "Physician", "Patient diagnosis, Patient care", "Active medical license required.");
        Job devJob = job(10L, "Software Developer", "Java, Spring Boot", "3+ years of backend development.");

        stubAi(Map.of(
                9L, relatedFixture("same_role", List.of("Patient diagnosis", "Patient care"), List.of(),
                        "mid", "relevant_degree", "specific_license"),
                10L, unrelatedFixture("medicine", "software engineering")
        ));

        JobMatchService.MatchScoresResult result =
                jobMatchService.getMatchScores(EMAIL, List.of(physicianJob, devJob), "en");

        Map<Long, Map<String, Object>> byJobId = new LinkedHashMap<>();
        result.matches().forEach(m -> byJobId.put((Long) m.get("jobId"), m));

        assertThat(byJobId.get(9L).get("fieldRelated")).isEqualTo(true);
        assertThat(byJobId.get(9L).get("matchPercent")).isNotNull();
        assertThat(byJobId.get(10L).get("fieldRelated")).isEqualTo(false);
        assertThat(byJobId.get(10L).get("matchPercent")).isNull();

        // One call per job (chunk size 1) - never one shared batched call for both.
        verify(openAICVAnalysisService, times(2)).computeJobMatches(any(), anyList(), anyMap(), any(), any());
    }

    // ---- scenario 10: the AI echoes back a jobId/fingerprint that doesn't match what was asked
    // for (a misattributed verdict) - it must be rejected, retried once, and if still wrong,
    // fall back to the honest error sentinel rather than saving an incorrect "-" verdict ----

    @Test
    void mismatchedFingerprint_isRejectedAndFallsBackToErrorSentinelAfterRetryFails() {
        when(cvAnalysisRepository.findByUserEmail(EMAIL)).thenReturn(Optional.of(doctorAnalysis()));
        Job physicianJob = job(11L, "Physician", "Patient diagnosis, Patient care", "Active medical license required.");

        // Always echoes back a bogus fingerprint and an unrelated title, on both the first
        // attempt and the retry - simulating a persistently broken/misattributed response.
        when(openAICVAnalysisService.computeJobMatches(any(), anyList(), anyMap(), any(), any()))
                .thenAnswer(invocation -> {
                    List<Job> jobs = invocation.getArgument(1);
                    Job requestedJob = jobs.get(0);
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("jobId", requestedJob.getId());
                    entry.put("jobTitle", "Completely Different Job Title");
                    entry.put("jobFingerprint", "WRONG-FINGERPRINT-FROM-A-DIFFERENT-JOB");
                    entry.put("fieldRelationCloseness", "same_role");
                    entry.put("matchReason", "ok");
                    entry.put("matchedMandatorySkills", List.of());
                    entry.put("missingMandatorySkills", List.of());
                    entry.put("matchedPreferredSkills", List.of());
                    entry.put("missingPreferredSkills", List.of());
                    entry.put("requiredExperienceLevel", null);
                    entry.put("requiredEducationLevel", null);
                    entry.put("requiredCertificationLevel", null);
                    return objectMapper.writeValueAsString(Map.of("matches", List.of(entry)));
                });

        JobMatchService.MatchScoresResult result = jobMatchService.getMatchScores(EMAIL, List.of(physicianJob), "en");

        Map<String, Object> match = result.matches().get(0);
        assertThat(match.get("fieldRelated"))
                .as("an unvalidated verdict must never be surfaced as a real true/false answer")
                .isNull();
        assertThat(match.get("matchPercent")).isNull();

        // First attempt + exactly one feedback-guided retry - never accepted, never persisted.
        verify(openAICVAnalysisService, times(2)).computeJobMatches(any(), anyList(), anyMap(), any(), any());
        verify(jobMatchScoreRepository, never()).save(any());
    }
}
