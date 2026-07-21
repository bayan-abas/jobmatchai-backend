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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.list;
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

    @Mock
    private MatchMetrics matchMetrics;

    @Mock
    private MatchScoreQueueService matchScoreQueueService;

    @Mock
    private com.jobmatchai.backend.repository.JobRepository jobRepository;

    @Mock
    private EmbeddingService embeddingService;

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
        ReflectionTestUtils.setField(jobMatchService, "matchMetrics", matchMetrics);
        ReflectionTestUtils.setField(jobMatchService, "matchScoreQueueService", matchScoreQueueService);
        ReflectionTestUtils.setField(jobMatchService, "jobRepository", jobRepository);
        ReflectionTestUtils.setField(jobMatchService, "embeddingService", embeddingService);
        ReflectionTestUtils.setField(jobMatchService, "queueAwaitTimeoutMs", 60000L);

        // cvAnalysisRepository.findByUserEmail is stubbed per-test (every test needs a
        // different CVAnalysis fixture), so it is deliberately not given a blanket default here.
        // lenient(): several tests (e.g. the fresh-cached-row streaming test) override this with
        // their own when() for the exact same (EMAIL, anyList()) matcher, which makes this
        // default one unreachable in THOSE tests - not an oversight, just this default's normal
        // "empty cache, everything is a miss" case not applying to a test about the opposite.
        lenient().when(jobMatchScoreRepository.findByCandidateEmailAndJobIdIn(eq(EMAIL), anyList())).thenReturn(List.of());
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
    // matchedMandatoryInferred/matchedPreferredInferred/requiredExperienceType/
    // candidateHasRequiredExperienceType default to empty/null via relatedFixture/unrelatedFixture
    // below for the many existing tests that don't exercise fundamental-skill inference or the
    // experience amount-vs-type distinction - see relatedFixtureWithExperienceType and
    // relatedFixtureWithInferredSkills for tests that do.
    private record MatchFixture(
            String fieldRelationCloseness,
            String matchReason,
            List<String> matchedMandatory,
            List<String> matchedMandatoryInferred,
            List<String> missingMandatory,
            List<String> matchedPreferred,
            List<String> matchedPreferredInferred,
            List<String> missingPreferred,
            String requiredExperienceLevel,
            String requiredExperienceType,
            Boolean candidateHasRequiredExperienceType,
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
        entry.put("matchedMandatorySkillsInferred", fx.matchedMandatoryInferred());
        entry.put("missingMandatorySkills", fx.missingMandatory());
        entry.put("matchedPreferredSkills", fx.matchedPreferred());
        entry.put("matchedPreferredSkillsInferred", fx.matchedPreferredInferred());
        entry.put("missingPreferredSkills", fx.missingPreferred());
        entry.put("requiredExperienceLevel", fx.requiredExperienceLevel());
        entry.put("requiredExperienceType", fx.requiredExperienceType());
        entry.put("candidateHasRequiredExperienceType", fx.candidateHasRequiredExperienceType());
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
                matchedMandatory, List.of(), missingMandatory, List.of(), List.of(), List.of(),
                requiredExperienceLevel, null, null, requiredEducationLevel, requiredCertificationLevel);
    }

    // For tests exercising the experience amount-vs-type distinction (see MatchScoreCalculator#
    // scoreExperience) - requiredExperienceType/candidateHasRequiredExperienceType only, all
    // other fields identical to relatedFixture's defaults.
    private MatchFixture relatedFixtureWithExperienceType(
            String closeness, List<String> matchedMandatory, List<String> missingMandatory,
            String requiredExperienceLevel, String requiredExperienceType, boolean candidateHasRequiredExperienceType) {
        return new MatchFixture(closeness, "You are a strong match for this role.",
                matchedMandatory, List.of(), missingMandatory, List.of(), List.of(), List.of(),
                requiredExperienceLevel, requiredExperienceType, candidateHasRequiredExperienceType, null, null);
    }

    // For tests exercising fundamental-skill inference (matchedMandatorySkillsInferred/
    // matchedPreferredSkillsInferred) - all other fields identical to relatedFixture's defaults.
    private MatchFixture relatedFixtureWithInferredSkills(
            String closeness, List<String> matchedMandatoryInferred, List<String> missingMandatory) {
        return new MatchFixture(closeness, "You are a strong match for this role.",
                List.of(), matchedMandatoryInferred, missingMandatory, List.of(), List.of(), List.of(),
                null, null, null, null, null);
    }

    private MatchFixture unrelatedFixture(String candidateField, String jobField) {
        return new MatchFixture("unrelated",
                "Your background is in " + candidateField + ", and this role is in " + jobField + ", an unrelated field.",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null, null, null, null);
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

    // ---- scenario 2: doctor CV against nurse job -> a DIFFERENT profession, even though both
    // are "healthcare" - the profession-taxonomy gate rejects this deterministically, before any
    // AI call, per the explicit product requirement that sharing a broad field/industry must
    // never by itself justify a real score. (Superseded an earlier version of this same test that
    // asserted the opposite - "same broad field" used to be a real, scored tier; it no longer is.) ----

    @Test
    void doctorCv_vsNurseJob_isIncompatibleProfessionAndNeverCallsAi() {
        when(cvAnalysisRepository.findByUserEmail(EMAIL)).thenReturn(Optional.of(doctorAnalysis()));
        Job nurseJob = job(2L, "Registered Nurse", "Patient care, Nursing license, Medication administration",
                "Requires an active nursing license.");

        JobMatchService.MatchScoresResult result = jobMatchService.getMatchScores(EMAIL, List.of(nurseJob), "en");

        Map<String, Object> match = result.matches().get(0);
        assertThat(match.get("fieldRelated"))
                .as("a doctor and a nurse are different professions - sharing the healthcare field is not enough")
                .isEqualTo(false);
        assertThat(match.get("matchPercent")).isNull();
        verifyNoInteractions(openAICVAnalysisService);
    }

    // ---- scenario 3: doctor CV against software developer job -> genuinely unrelated, and now
    // caught by the deterministic profession-taxonomy gate before any AI call is even made ----

    @Test
    void doctorCv_vsSoftwareDeveloperJob_isUnrelated() {
        when(cvAnalysisRepository.findByUserEmail(EMAIL)).thenReturn(Optional.of(doctorAnalysis()));
        Job devJob = job(3L, "Software Developer", "Java, Spring Boot, SQL", "3+ years of backend development.");

        JobMatchService.MatchScoresResult result = jobMatchService.getMatchScores(EMAIL, List.of(devJob), "en");

        Map<String, Object> match = result.matches().get(0);
        assertThat(match.get("fieldRelated")).isEqualTo(false);
        assertThat(match.get("matchPercent")).isNull();
        verifyNoInteractions(openAICVAnalysisService);
    }

    // ---- scenario 3b: pairs that are genuinely UNRELATED (no curated edge at all) or DIFFERENT
    // LICENSED PROFESSIONS - these may never receive a real score just because they share an
    // industry, and never reach the AI (the taxonomy gate is deterministic and free). Distinct
    // from CLOSELY_RELATED/RELATED pairs (see the next test), which DO get a real, reduced score. ----

    @Test
    void explicitlyNamedIncompatibleProfessionPairs_areAllRejectedWithoutAnyAiCall() {
        CVAnalysis softwareEngineer = professionOnlyAnalysis("Software Engineer");
        CVAnalysis accountant = professionOnlyAnalysis("Accountant");
        CVAnalysis lawyer = professionOnlyAnalysis("Lawyer");
        CVAnalysis teacher = professionOnlyAnalysis("Teacher");
        CVAnalysis mechanicalEngineer = professionOnlyAnalysis("Mechanical Engineer");

        // Genuinely unrelated - no curated relationship exists between these professions at all.
        assertIncompatible(softwareEngineer, job(102L, "Data Analyst", "SQL, Excel, dashboards", "Reporting experience required."));
        assertIncompatible(softwareEngineer, job(103L, "Cybersecurity Engineer", "Penetration testing, SIEM", "Security clearance preferred."));
        assertIncompatible(softwareEngineer, job(104L, "IT Support Specialist", "Help desk, troubleshooting", "Customer-facing role."));
        // Different licensed professions - hard-blocked regardless of any relatedness, per the
        // explicit product requirement. Accountant/Auditor in particular has a curated `related`
        // edge in the data (real-world, many auditors ARE accountants) that the licensing check
        // must still override - this specifically verifies that override, not just the absence
        // of any edge.
        assertIncompatible(accountant, job(105L, "Financial Advisor", "Portfolio management, client advising", "Series 7 license preferred."));
        assertIncompatible(accountant, job(106L, "Auditor", "Internal controls, risk assessment", "CPA preferred."));
        assertIncompatible(lawyer, job(107L, "Police Officer", "Law enforcement, patrol", "Police academy graduate required."));
        assertIncompatible(teacher, job(108L, "Social Worker", "Case management, client advocacy", "MSW preferred."));
        assertIncompatible(mechanicalEngineer, job(109L, "Civil Engineer", "Structural analysis, site planning", "PE license preferred."));

        verifyNoInteractions(openAICVAnalysisService);
    }

    // ---- scenario 3c: CLOSELY_RELATED and RELATED pairs must NOT be rejected outright - they
    // still reach the AI for a full skills/experience breakdown, but the field-relevance
    // component is driven by the taxonomy's own tier (65 for closely related, 40 for related)
    // rather than the AI's free judgment, reflecting real-world career transitions. ----

    @Test
    void explicitlyNamedCloselyRelatedAndRelatedPairs_getReducedButRealScoresAndStillCallAi() {
        CVAnalysis softwareEngineer = professionOnlyAnalysis("Software Engineer");
        CVAnalysis backendDeveloper = professionOnlyAnalysis("Backend Developer");
        CVAnalysis dataAnalyst = professionOnlyAnalysis("Data Analyst");
        CVAnalysis devopsEngineer = professionOnlyAnalysis("DevOps Engineer");

        // Stubbed ONCE, generically, rather than per-call: re-registering a thenAnswer stub on
        // the same mock method mid-test re-invokes the PREVIOUS stub as a side effect of Mockito
        // recording the new one (the same pitfall documented on cvChanged_whileOldScoreCached
        // above) - one generic stub that answers based on whatever job it's actually called with
        // avoids that entirely.
        when(openAICVAnalysisService.computeJobMatches(any(), anyList(), anyMap(), any(), any()))
                .thenAnswer(invocation -> {
                    List<Job> jobs = invocation.getArgument(1);
                    Map<Long, String> fingerprints = invocation.getArgument(2);
                    Job requestedJob = jobs.get(0);
                    // Empty skill claims deliberately - this test is about the taxonomy-driven
                    // fieldRelevancePercent override, not skill-evidence validation, and the
                    // matched/missing skill text would need to differ per job (see
                    // JobMatchServiceTest's other scenarios for that coverage).
                    return buildResponseJson(requestedJob, fingerprints.get(requestedJob.getId()),
                            relatedFixture("same_broad_field", List.of(), List.of(), "mid", null, null));
                });

        assertReducedButReal(softwareEngineer,
                job(200L, "QA Automation Engineer", "Selenium, Playwright, CI/CD", "Automation scripting experience required."),
                65, "closely related");
        assertReducedButReal(backendDeveloper,
                job(201L, "Full Stack Developer", "React, Node.js, PostgreSQL", "Frontend and backend experience required."),
                65, "closely related");
        assertReducedButReal(dataAnalyst,
                job(202L, "Business Intelligence Analyst", "Power BI, SQL, dashboards", "BI tooling experience required."),
                65, "closely related");
        assertReducedButReal(devopsEngineer,
                job(203L, "Cloud Engineer", "AWS, Terraform, cloud architecture", "Cloud certification preferred."),
                65, "closely related");
        assertReducedButReal(softwareEngineer,
                job(204L, "QA Engineer", "Manual testing, test plans, bug tracking", "QA methodology experience required."),
                40, "related");
    }

    private CVAnalysis professionOnlyAnalysis(String professionTitle) {
        CVAnalysis a = new CVAnalysis();
        a.setUserEmail(EMAIL);
        a.setProfessionTitle(professionTitle);
        a.setCandidateField("other");
        a.setExperienceLevel("mid_level");
        // Gives validateMatch something to evidence a matched-skill claim against - the hard-
        // block scenarios never reach validateMatch at all (no AI call), but the CLOSELY_RELATED/
        // RELATED scenarios do, and an empty skills profile would fail validation on ANY matched
        // skill the AI stub claims, which is a validation-logic concern unrelated to what these
        // tests are actually about.
        a.setTechnicalSkills("CI/CD, general technical skills");
        a.setCvTextHash(professionTitle + "-hash");
        return a;
    }

    private void assertIncompatible(CVAnalysis analysis, Job incompatibleJob) {
        when(cvAnalysisRepository.findByUserEmail(EMAIL)).thenReturn(Optional.of(analysis));
        JobMatchService.MatchScoresResult result = jobMatchService.getMatchScores(EMAIL, List.of(incompatibleJob), "en");
        Map<String, Object> match = result.matches().get(0);
        assertThat(match.get("fieldRelated"))
                .as(analysis.getProfessionTitle() + " vs " + incompatibleJob.getTitle() + " must be incompatible")
                .isEqualTo(false);
        assertThat(match.get("matchPercent")).isNull();
    }

    // Stubs the AI to return a real (non-"unrelated") verdict with a genuine skills breakdown -
    // reflecting that the AI still fully participates for CLOSELY_RELATED/RELATED pairs, unlike
    // the hard-blocked tiers above. The taxonomy overrides only the field-relevance component
    // (fieldRelevancePercent), not the skills/experience analysis itself.
    private void assertReducedButReal(CVAnalysis analysis, Job relatedJob, int expectedFieldRelevance, String label) {
        when(cvAnalysisRepository.findByUserEmail(EMAIL)).thenReturn(Optional.of(analysis));

        JobMatchService.MatchScoresResult result = jobMatchService.getMatchScores(EMAIL, List.of(relatedJob), "en");

        Map<String, Object> match = result.matches().get(0);
        assertThat(match.get("fieldRelated"))
                .as(analysis.getProfessionTitle() + " vs " + relatedJob.getTitle() + " (" + label + ") must stay field-related")
                .isEqualTo(true);
        assertThat(match.get("matchPercent"))
                .as(analysis.getProfessionTitle() + " vs " + relatedJob.getTitle() + " (" + label + ") must get a real score")
                .isNotNull();
        assertThat(match.get("fieldRelevancePercent"))
                .as(analysis.getProfessionTitle() + " vs " + relatedJob.getTitle() + " field relevance")
                .isEqualTo(expectedFieldRelevance);
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
        assertThat((Integer) match.get("fieldRelevancePercent")).isEqualTo(25);
        assertThat(match.get("experienceMatchPercent"))
                .as("experience earned in an unrelated field must not count as evidence of fit for a vocational role")
                .isNull();
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

    // ---- scenario 6: junior candidate against a senior job in only a BROAD (not their own
    // specific) field -> related, but the experience component (and so the overall score) should
    // be pulled down further than a plain seniority shortfall, not fieldRelated ----

    @Test
    void juniorCandidate_vsSeniorJobBroadFieldOnly_isRelatedWithFurtherDiscountedExperienceComponent() {
        when(cvAnalysisRepository.findByUserEmail(EMAIL)).thenReturn(Optional.of(infoSystemsGradAnalysis()));
        Job seniorJob = job(6L, "Senior Software Architect", "Java, System design", "10+ years required.");

        stubAi(Map.of(6L, relatedFixture("same_broad_field",
                List.of("Java"), List.of("System design"),
                "senior", null, null)));

        JobMatchService.MatchScoresResult result = jobMatchService.getMatchScores(EMAIL, List.of(seniorJob), "en");

        Map<String, Object> match = result.matches().get(0);
        assertThat(match.get("fieldRelated")).isEqualTo(true);
        // same_broad_field (not the candidate's own specific role) discounts entry_level
        // (rank 1) down to "none" (rank 0) before comparing against required "senior" (rank 3):
        // 100 - 3*40 = -20, clamped to 0. See MatchScoreCalculator#scoreExperience's
        // sameSpecificRole discount - broad-field-only experience isn't credited as if it were
        // directly in this specific role.
        assertThat(match.get("experienceMatchPercent")).isEqualTo(0);
    }

    // ---- experience amount-vs-type: a senior General Practitioner (10 years, same_role) applying
    // to a role that only needs "mid" seniority but names a specific Clinical Research sub-domain
    // the candidate's history doesn't show - must be blended (not full credit, not zeroed as if
    // the candidate had no experience at all), and the persisted requiredExperienceType/
    // candidateHasRequiredExperienceType must reflect the gap so the UI/detail narrative can
    // explain it precisely. See MatchScoreCalculator#scoreExperience's own comment. ----

    @Test
    void seniorDoctorCv_meetsSeniorityButLacksNamedExperienceType_experienceIsBlendedNotZeroed() {
        when(cvAnalysisRepository.findByUserEmail(EMAIL)).thenReturn(Optional.of(doctorAnalysis()));
        Job clinicalResearchJob = job(23L, "Physician",
                "Patient diagnosis, Patient care", "2+ years of Clinical Research experience required.");

        stubAi(Map.of(23L, relatedFixtureWithExperienceType(
                "same_role", List.of("Patient diagnosis", "Patient care"), List.of(),
                "mid", "Clinical Research", false)));

        JobMatchService.MatchScoresResult result = jobMatchService.getMatchScores(EMAIL, List.of(clinicalResearchJob), "en");

        Map<String, Object> match = result.matches().get(0);
        assertThat(match.get("fieldRelated")).isEqualTo(true);
        // Amount alone (senior_level candidate vs required "mid", sameSpecificRole) would be 100 -
        // blended down to 50 for the unevidenced specific type, per
        // MatchScoreCalculator#scoreExperience's amount-vs-type test coverage.
        assertThat(match.get("experienceMatchPercent"))
                .as("right amount of general seniority, but a real gap in the specifically-named "
                        + "experience type - must land strictly between a full match and a zero, never either extreme")
                .isEqualTo(50);

        ArgumentCaptor<JobMatchScore> savedCaptor = ArgumentCaptor.forClass(JobMatchScore.class);
        verify(jobMatchScoreRepository).save(savedCaptor.capture());
        JobMatchScore saved = savedCaptor.getValue();
        assertThat(saved.getRequiredExperienceType()).isEqualTo("Clinical Research");
        assertThat(saved.getCandidateHasRequiredExperienceType()).isFalse();
    }

    @Test
    void seniorDoctorCv_hasEvidenceOfNamedExperienceType_experienceIsFullCredit() {
        when(cvAnalysisRepository.findByUserEmail(EMAIL)).thenReturn(Optional.of(doctorAnalysis()));
        Job clinicalResearchJob = job(24L, "Physician",
                "Patient diagnosis, Patient care", "2+ years of Clinical Research experience required.");

        stubAi(Map.of(24L, relatedFixtureWithExperienceType(
                "same_role", List.of("Patient diagnosis", "Patient care"), List.of(),
                "mid", "Clinical Research", true)));

        JobMatchService.MatchScoresResult result = jobMatchService.getMatchScores(EMAIL, List.of(clinicalResearchJob), "en");

        Map<String, Object> match = result.matches().get(0);
        assertThat(match.get("experienceMatchPercent"))
                .as("candidate has real evidence of the specific type - full credit, no blending")
                .isEqualTo(100);
    }

    // ---- fundamental-skill inference: a licensed doctor gets credit for Pharmacology even though
    // it is never literally written in the CV, because it is a reasonable, direct consequence of
    // being a licensed physician (same_role) - see computeJobMatches' FUNDAMENTAL-SKILL INFERENCE
    // RULE and JobMatchService's NON_INFERABLE_SKILL_TERMS/MAX_INFERRED_SKILLS_PER_JOB guardrails ----

    @Test
    void licensedDoctorCv_inferredFundamentalSkill_countsTowardMatchedSkillsAndScore() {
        when(cvAnalysisRepository.findByUserEmail(EMAIL)).thenReturn(Optional.of(doctorAnalysis()));
        Job pharmacologyJob = job(25L, "Physician", "Pharmacology", "Strong knowledge of Pharmacology required.");

        stubAi(Map.of(25L, relatedFixtureWithInferredSkills("same_role", List.of("Pharmacology"), List.of())));

        JobMatchService.MatchScoresResult result = jobMatchService.getMatchScores(EMAIL, List.of(pharmacologyJob), "en");

        Map<String, Object> match = result.matches().get(0);
        assertThat(match.get("fieldRelated")).isEqualTo(true);
        assertThat(match.get("matchedSkills"))
                .asInstanceOf(list(String.class))
                .as("an inferred fundamental skill still counts as matched for the candidate-facing skill list")
                .contains("Pharmacology");
        // 1 matched (inferred) mandatory skill, 0 missing -> computeSkillsScore(1,0,0,0) = 100.
        assertThat(match.get("skillsMatchPercent")).isEqualTo(100);
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
        // A profession the taxonomy doesn't recognize (deliberately, so this still exercises the
        // AI-judged fallback path and both jobs reach the AI) rather than "Software Developer",
        // which the taxonomy gate would now reject deterministically before any AI call - see
        // doctorCv_vsSoftwareDeveloperJob_isUnrelated for that case specifically.
        Job urbanPlannerJob = job(10L, "Urban Planner", "GIS software, zoning regulations", "5+ years in city planning.");

        stubAi(Map.of(
                9L, relatedFixture("same_role", List.of("Patient diagnosis", "Patient care"), List.of(),
                        "mid", "relevant_degree", "specific_license"),
                10L, unrelatedFixture("medicine", "urban planning")
        ));

        JobMatchService.MatchScoresResult result =
                jobMatchService.getMatchScores(EMAIL, List.of(physicianJob, urbanPlannerJob), "en");

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

    // ---- scenario 11: a job posting with nothing beyond its own title -> "not enough job
    // information", no AI call spent at all ----

    @Test
    void jobWithTitleOnly_isInsufficientDataAndNeverCallsAi() {
        when(cvAnalysisRepository.findByUserEmail(EMAIL)).thenReturn(Optional.of(doctorAnalysis()));
        Job titleOnlyJob = new Job("Doctor", "Acme Co", null, "Tel Aviv", "Full-time", "20000", "", "", "");
        titleOnlyJob.setId(20L);

        JobMatchService.MatchScoresResult result = jobMatchService.getMatchScores(EMAIL, List.of(titleOnlyJob), "en");

        Map<String, Object> match = result.matches().get(0);
        assertThat(match.get("insufficientData")).isEqualTo(true);
        assertThat(match.get("matchPercent")).isNull();
        assertThat(match.get("matchReason")).isEqualTo("Not enough job information to calculate a reliable match.");
        verifyNoInteractions(openAICVAnalysisService);
    }

    // ---- scenario 12: an extremely short, non-descriptive description with no real
    // requirements/skills -> also insufficient data, even with a title beyond one word ----

    @Test
    void jobWithExtremelyShortDescription_isInsufficientDataAndNeverCallsAi() {
        when(cvAnalysisRepository.findByUserEmail(EMAIL)).thenReturn(Optional.of(doctorAnalysis()));
        Job shortDescJob = new Job("Nurse Assistant", "Acme Co", null, "Haifa", "Part-time", "8000",
                "Help out.", "", "");
        shortDescJob.setId(25L);

        JobMatchService.MatchScoresResult result = jobMatchService.getMatchScores(EMAIL, List.of(shortDescJob), "en");

        Map<String, Object> match = result.matches().get(0);
        assertThat(match.get("insufficientData")).isEqualTo(true);
        assertThat(match.get("matchPercent")).isNull();
        verifyNoInteractions(openAICVAnalysisService);
    }

    // ---- scenario 13: reproduces the exact production finding (job id 14: title "doctor",
    // description just "doctor" again, requirements a single line, skills "doctor, medicine,
    // family") that previously received a fabricated 81% match with a full paragraph of invented
    // detail -> must now be insufficient data instead, with zero AI spend ----

    @Test
    void productionThinDoctorJob_isInsufficientDataInsteadOfFabricated81Percent() {
        when(cvAnalysisRepository.findByUserEmail(EMAIL)).thenReturn(Optional.of(doctorAnalysis()));
        Job thinJob = new Job("doctor", "AI", "ai@test.com", "tel aviv", "Full-time", "20000",
                "doctor", "Experience: 2 - 5 years", "doctor, medicine, family");
        thinJob.setId(14L);

        JobMatchService.MatchScoresResult result = jobMatchService.getMatchScores(EMAIL, List.of(thinJob), "en");

        Map<String, Object> match = result.matches().get(0);
        assertThat(match.get("insufficientData")).isEqualTo(true);
        assertThat(match.get("matchPercent")).isNull();
        verifyNoInteractions(openAICVAnalysisService);
    }

    // ---- scenario 14: a senior candidate against a posting stating "2 - 5 years" with no stated
    // maximum -> the experience component is a full 100, never penalized for exceeding the range ----

    @Test
    void seniorCandidate_vsTwoToFiveYearRole_notPenalizedForExceedingRange() {
        when(cvAnalysisRepository.findByUserEmail(EMAIL)).thenReturn(Optional.of(doctorAnalysis()));
        Job midRangeJob = job(21L, "Physician",
                "Patient diagnosis, Patient care, Prescribing medication",
                "- Experience: 2 - 5 years\n- Active medical license required\n- Strong communication skills");

        stubAi(Map.of(21L, relatedFixture("same_role",
                List.of("Patient diagnosis", "Patient care", "Prescribing medication"), List.of(),
                "mid", null, "specific_license")));

        JobMatchService.MatchScoresResult result = jobMatchService.getMatchScores(EMAIL, List.of(midRangeJob), "en");

        Map<String, Object> match = result.matches().get(0);
        assertThat(match.get("experienceMatchPercent"))
                .as("a senior candidate (rank 3) meets/exceeds a 'mid' requirement (rank 2) - never penalized for having more")
                .isEqualTo(100);
    }

    // ---- scenario 15: getMatchDetail's free-text narrative must not treat the job's LOCATION as
    // a prior-work-experience requirement, must not invent ungrounded "leadership"/"public
    // health" concerns the posting never mentioned, and must not frame more-than-required
    // experience as a disadvantage - reproduces the exact fabricated bullets found in production
    // for job id 14 (General Practitioner CV, "doctor" job in Tel Aviv, "Experience: 2-5 years") ----

    @Test
    void matchDetail_filtersLocationAsExperienceAndUngroundedFillerAndOverqualificationClaims() {
        when(cvAnalysisRepository.findByUserEmail(EMAIL)).thenReturn(Optional.of(doctorAnalysis()));
        Job thinButRelatedJob = job(22L, "Physician",
                "Patient diagnosis, Patient care, Prescribing medication",
                "- Experience: 2 - 5 years\n- Active medical license required\n- Strong communication skills");
        thinButRelatedJob.setLocation("Tel Aviv");

        stubAi(Map.of(22L, relatedFixture("same_role",
                List.of("Patient diagnosis", "Patient care", "Prescribing medication"), List.of(),
                "mid", null, "specific_license")));

        when(openAICVAnalysisService.computeJobMatchDetail(any(), any(), any(), anyInt(), anyList(), anyList(),
                        nullable(String.class), nullable(Boolean.class)))
                .thenReturn("""
                        {
                          "languageMatchPercent": 90,
                          "whyGoodMatch": ["You have over 8 years of experience as a General Practitioner, which exceeds the job's requirement of 2-5 years."],
                          "whyNotPerfectMatch": [
                            "There is no explicit mention of experience working in Tel Aviv or familiarity with local healthcare regulations.",
                            "The posting does not specify a need for advanced leadership or public health experience, which are among your strengths.",
                            "The job posting may prefer candidates with experience levels closer to the 2-5 year range, while you are at a senior level."
                          ],
                          "improvementSuggestions": ["Highlight your adaptability and willingness to work in new environments."],
                          "recommendation": "You are a strong candidate for this position.",
                          "shouldApply": true
                        }
                        """);

        JobMatchService.MatchDetailResult result = jobMatchService.getMatchDetail(EMAIL, thinButRelatedJob, "en");

        assertThat(result.whyNotPerfectMatch()).hasSize(1);
        assertThat(result.whyNotPerfectMatch().get(0))
                .as("all three fabricated bullets (location-as-experience, ungrounded leadership/public-health filler, "
                        + "overqualification framing) must be filtered out, leaving only the honest fallback")
                .doesNotContainIgnoringCase("Tel Aviv")
                .doesNotContainIgnoringCase("leadership")
                .doesNotContainIgnoringCase("public health")
                .doesNotContainIgnoringCase("closer to the");
    }

    // ---- scenario 16: synonyms - the AI claims "doctor" as a missing skill for a job titled
    // "Doctor" while ALSO judging fieldRelationCloseness=same_role - self-contradictory (the
    // candidate cannot be missing the very role they were just judged to already hold). Rejected
    // on both attempts, so this falls back to the honest error sentinel rather than persisting a
    // misleading missing-skill claim (doctor/physician/General Practitioner must never be treated
    // as unrelated or missing for a candidate who already holds one of those titles). ----

    @Test
    void selfContradictoryMissingSkill_isRejectedEvenWhenFieldRelationClosenessIsSameRole() {
        when(cvAnalysisRepository.findByUserEmail(EMAIL)).thenReturn(Optional.of(doctorAnalysis()));
        Job doctorTitledJob = job(23L, "Doctor", "doctor, medicine, family, patient care, diagnosis",
                "- Experience: 2 - 5 years\n- Must hold an active medical license\n- Strong communication skills required");

        when(openAICVAnalysisService.computeJobMatches(any(), anyList(), anyMap(), any(), any()))
                .thenAnswer(invocation -> {
                    List<Job> jobs = invocation.getArgument(1);
                    Map<Long, String> fingerprints = invocation.getArgument(2);
                    Job requestedJob = jobs.get(0);
                    return buildResponseJson(requestedJob, fingerprints.get(requestedJob.getId()),
                            relatedFixture("same_role", List.of("medicine", "family"), List.of("doctor"),
                                    "mid", null, null));
                });

        JobMatchService.MatchScoresResult result = jobMatchService.getMatchScores(EMAIL, List.of(doctorTitledJob), "en");

        Map<String, Object> match = result.matches().get(0);
        assertThat(match.get("fieldRelated"))
                .as("the self-contradictory response is rejected on both attempts, falling back to the honest "
                        + "'couldn't compute' sentinel rather than persisting 'doctor' as a missing skill")
                .isNull();
        verify(openAICVAnalysisService, times(2)).computeJobMatches(any(), anyList(), anyMap(), any(), any());
        verify(jobMatchScoreRepository, never()).save(any());
    }

    // ---- internal-consistency guards: the same skill (or requirement) can never be treated as
    // both a positive and a negative at once - see JobMatchService#validateMatch's cross-array
    // overlap check and requiredExperienceType-vs-missing-skill double-counting check ----

    @Test
    void skillListedAsBothMatchedAndMissing_isRejectedOnBothAttempts() {
        when(cvAnalysisRepository.findByUserEmail(EMAIL)).thenReturn(Optional.of(doctorAnalysis()));
        Job physicianJob = job(26L, "Physician", "Patient diagnosis, Patient care",
                "- Experience: 2 - 5 years\n- Strong communication skills required");

        // "Patient diagnosis" is claimed as BOTH matched (mandatory) and missing (mandatory) -
        // the exact contradiction the cross-array check exists to catch, regardless of which two
        // of the six arrays it happens to straddle.
        MatchFixture contradictory = new MatchFixture("same_role", "You are a strong match for this role.",
                List.of("Patient diagnosis"), List.of(), List.of("Patient diagnosis"),
                List.of(), List.of(), List.of(),
                "mid", null, null, null, null);
        when(openAICVAnalysisService.computeJobMatches(any(), anyList(), anyMap(), any(), any()))
                .thenAnswer(invocation -> {
                    List<Job> jobs = invocation.getArgument(1);
                    Map<Long, String> fingerprints = invocation.getArgument(2);
                    Job requestedJob = jobs.get(0);
                    return buildResponseJson(requestedJob, fingerprints.get(requestedJob.getId()), contradictory);
                });

        JobMatchService.MatchScoresResult result = jobMatchService.getMatchScores(EMAIL, List.of(physicianJob), "en");

        Map<String, Object> match = result.matches().get(0);
        assertThat(match.get("fieldRelated"))
                .as("a skill claimed as both matched and missing at once is rejected on both attempts, "
                        + "falling back to the honest 'couldn't compute' sentinel rather than persisting a contradiction")
                .isNull();
        verify(openAICVAnalysisService, times(2)).computeJobMatches(any(), anyList(), anyMap(), any(), any());
        verify(jobMatchScoreRepository, never()).save(any());
    }

    @Test
    void requiredExperienceTypeOverlappingMissingSkill_isRejectedOnBothAttempts() {
        when(cvAnalysisRepository.findByUserEmail(EMAIL)).thenReturn(Optional.of(doctorAnalysis()));
        Job clinicalResearchJob = job(27L, "Physician",
                "Patient diagnosis, Patient care", "2+ years of Clinical Research experience required.");

        // The identical gap ("Clinical Research") is double-classified: once as a missing
        // mandatory skill AND again as requiredExperienceType - would depress both the skills
        // score and the experience score for one real deficiency.
        MatchFixture doubleCounted = new MatchFixture("same_role", "You are a strong match for this role.",
                List.of("Patient diagnosis", "Patient care"), List.of(), List.of("Clinical Research experience"),
                List.of(), List.of(), List.of(),
                "mid", "Clinical Research", false, null, null);
        when(openAICVAnalysisService.computeJobMatches(any(), anyList(), anyMap(), any(), any()))
                .thenAnswer(invocation -> {
                    List<Job> jobs = invocation.getArgument(1);
                    Map<Long, String> fingerprints = invocation.getArgument(2);
                    Job requestedJob = jobs.get(0);
                    return buildResponseJson(requestedJob, fingerprints.get(requestedJob.getId()), doubleCounted);
                });

        JobMatchService.MatchScoresResult result = jobMatchService.getMatchScores(EMAIL, List.of(clinicalResearchJob), "en");

        Map<String, Object> match = result.matches().get(0);
        assertThat(match.get("fieldRelated"))
                .as("the same gap counted as both a missing skill AND a separate experience-type gap is rejected, "
                        + "since it would double-penalize the candidate for one deficiency")
                .isNull();
        verify(openAICVAnalysisService, times(2)).computeJobMatches(any(), anyList(), anyMap(), any(), any());
    }

    // ---- internal-consistency guard: computeJobMatchDetail's free-text bullets must never
    // contradict the core computation's already-decided matched/missing skill lists - a
    // whyGoodMatch bullet praising a skill the core score marked MISSING, or a whyNotPerfectMatch/
    // improvementSuggestions bullet claiming a skill is absent when the core score marked it
    // MATCHED, are both dropped rather than shown. See JobMatchService#validateDetailClaims. ----

    @Test
    void matchDetail_dropsBulletsThatContradictMatchedOrMissingSkillLists() {
        when(cvAnalysisRepository.findByUserEmail(EMAIL)).thenReturn(Optional.of(doctorAnalysis()));
        Job physicianJob = job(28L, "Physician",
                "Patient diagnosis, Patient care, Clinical Research",
                "- Patient diagnosis required\n- Patient care required\n- Clinical Research experience required");

        // Patient diagnosis/Patient care are matched; Clinical Research is missing.
        stubAi(Map.of(28L, relatedFixture("same_role",
                List.of("Patient diagnosis", "Patient care"), List.of("Clinical Research"),
                "mid", null, null)));

        when(openAICVAnalysisService.computeJobMatchDetail(any(), any(), any(), anyInt(), anyList(), anyList(),
                        nullable(String.class), nullable(Boolean.class)))
                .thenReturn("""
                        {
                          "languageMatchPercent": 90,
                          "whyGoodMatch": [
                            "Your documented Patient diagnosis experience is a strong fit for this role.",
                            "Your Clinical Research background makes you an excellent candidate."
                          ],
                          "whyNotPerfectMatch": [
                            "The posting specifically asks for Clinical Research experience not reflected in your CV.",
                            "You are lacking Patient care experience for this role."
                          ],
                          "improvementSuggestions": ["Consider gaining hands-on Clinical Research experience."],
                          "recommendation": "You are a reasonable candidate for this position.",
                          "shouldApply": true
                        }
                        """);

        JobMatchService.MatchDetailResult result = jobMatchService.getMatchDetail(EMAIL, physicianJob, "en");

        assertThat(result.whyGoodMatch())
                .as("the bullet praising 'Clinical Research' (a MISSING skill) as a strength must be dropped, "
                        + "leaving only the bullet about the genuinely matched Patient diagnosis skill")
                .containsExactly("Your documented Patient diagnosis experience is a strong fit for this role.");
        assertThat(result.whyNotPerfectMatch())
                .as("the bullet falsely claiming 'Patient care' (a MATCHED skill) is lacking must be dropped, "
                        + "leaving only the genuinely-grounded Clinical Research gap")
                .containsExactly("The posting specifically asks for Clinical Research experience not reflected in your CV.");
    }

    // ---- regression found via real (non-mocked) end-to-end testing: a bullet that EXPLICITLY
    // DENIES any gap (e.g. "there is no evidence you lack Patient care experience") must survive
    // untouched, even though it names a matched skill alongside an ABSENCE_PHRASES word - the
    // naive "contains an absence phrase + names a matched skill" check was flagging this as a
    // false contradiction before JobMatchService#hasGenuineAbsenceClaim's negation guard. ----

    @Test
    void matchDetail_bulletExplicitlyDenyingGap_isNotWronglyDropped() {
        when(cvAnalysisRepository.findByUserEmail(EMAIL)).thenReturn(Optional.of(doctorAnalysis()));
        Job physicianJob = job(29L, "Physician",
                "Patient diagnosis, Patient care",
                "- Patient diagnosis required\n- Patient care required");

        stubAi(Map.of(29L, relatedFixture("same_role",
                List.of("Patient diagnosis", "Patient care"), List.of(),
                "mid", null, null)));

        when(openAICVAnalysisService.computeJobMatchDetail(any(), any(), any(), anyInt(), anyList(), anyList(),
                        nullable(String.class), nullable(Boolean.class)))
                .thenReturn("""
                        {
                          "languageMatchPercent": 90,
                          "whyGoodMatch": ["Your background aligns well with this role."],
                          "whyNotPerfectMatch": [
                            "There is no evidence you lack Patient care experience - your profile fully covers this role's requirements."
                          ],
                          "improvementSuggestions": [],
                          "recommendation": "You are a strong candidate for this position.",
                          "shouldApply": true
                        }
                        """);

        JobMatchService.MatchDetailResult result = jobMatchService.getMatchDetail(EMAIL, physicianJob, "en");

        assertThat(result.whyNotPerfectMatch())
                .as("a bullet explicitly denying any gap must never be dropped just for naming a matched "
                        + "skill alongside an absence-flavored word used to deny, not claim, an absence")
                .containsExactly("There is no evidence you lack Patient care experience - "
                        + "your profile fully covers this role's requirements.");
    }

    // ---- internal-consistency guard: shouldApply/recommendation can never contradict the core
    // matchPercent - see JobMatchService#resolveShouldApply's deterministic floor/ceiling ----

    @Test
    void resolveShouldApply_lowScoreForcesFalseRegardlessOfAiClaim() {
        assertThat(jobMatchService.resolveShouldApply(20, true))
                .as("a severely poor fit must never be recommended, even if the free-text call said so")
                .isFalse();
    }

    @Test
    void resolveShouldApply_highScoreForcesTrueRegardlessOfAiClaim() {
        assertThat(jobMatchService.resolveShouldApply(85, false))
                .as("a strong fit must never be discouraged, even if the free-text call said so")
                .isTrue();
    }

    @Test
    void resolveShouldApply_middleBandTrustsAiJudgment() {
        assertThat(jobMatchService.resolveShouldApply(50, true)).isTrue();
        assertThat(jobMatchService.resolveShouldApply(50, false)).isFalse();
    }

    @Test
    void resolveShouldApply_nullMatchPercentTrustsAiJudgment() {
        assertThat(jobMatchService.resolveShouldApply(null, true)).isTrue();
        assertThat(jobMatchService.resolveShouldApply(null, false)).isFalse();
    }

    // ---- scenario 17: the CV changes (a brand new CVAnalysis, different cvTextHash) while an
    // old cached score still exists for the same job -> the stale cache computed against the OLD
    // CV must never be reused; a fresh AI call reflects the NEW CV ----

    @Test
    void cvChanged_whileOldScoreCached_triggersFreshComputationNotStaleReuse() {
        Job physicianJob = job(24L, "Physician", "Patient diagnosis, Patient care", "Active medical license required.");

        when(cvAnalysisRepository.findByUserEmail(EMAIL)).thenReturn(Optional.of(doctorAnalysis()));
        stubAi(Map.of(24L, relatedFixture("same_role",
                List.of("Patient diagnosis", "Patient care"), List.of(),
                "mid", "relevant_degree", "specific_license")));

        jobMatchService.getMatchScores(EMAIL, List.of(physicianJob), "en");

        ArgumentCaptor<JobMatchScore> savedCaptor = ArgumentCaptor.forClass(JobMatchScore.class);
        verify(jobMatchScoreRepository).save(savedCaptor.capture());
        JobMatchScore cachedFromOldCv = savedCaptor.getValue();
        when(jobMatchScoreRepository.findByCandidateEmailAndJobIdIn(eq(EMAIL), anyList()))
                .thenReturn(List.of(cachedFromOldCv));

        // Simulates deleting/replacing the CV (a different cvTextHash) - the same flow
        // ResumeManager.tsx's upload/delete/analyze actions trigger. reset() first: re-stubbing
        // computeJobMatches with when() a second time in the same test would otherwise re-invoke
        // the FIRST stub's answer as a side effect of Mockito recording the new stub.
        // Uses a profession the taxonomy doesn't recognize (deliberately) so this keeps
        // exercising the AI-judged fallback path this test is actually about, rather than being
        // short-circuited by the (also correct, but not what this test is testing) profession gate.
        when(cvAnalysisRepository.findByUserEmail(EMAIL)).thenReturn(Optional.of(professionOnlyAnalysis("Urban Planner")));
        reset(openAICVAnalysisService);
        stubAi(Map.of(24L, unrelatedFixture("urban planning", "medicine")));

        JobMatchService.MatchScoresResult result = jobMatchService.getMatchScores(EMAIL, List.of(physicianJob), "en");

        Map<String, Object> match = result.matches().get(0);
        assertThat(match.get("fieldRelated"))
                .as("the NEW CV's own verdict must be used - the row cached against the OLD CV's fingerprint must not be reused")
                .isEqualTo(false);
        // times(1), not 0: reset() above cleared invocation history along with the old stub, so
        // this counts only the call made against the NEW CV - the real assertion is that this
        // call happened at all (a stale-cache reuse would have made zero calls here).
        verify(openAICVAnalysisService, times(1)).computeJobMatches(any(), anyList(), anyMap(), any(), any());
    }

    // ---- scenario 18: computeMatchScoresStreaming (the dashboard/job-list path) no longer
    // computes AI matches inline - a stale job is enqueued onto the persistent queue and awaited,
    // never computed directly on the request thread. This is what "the dashboard does not trigger
    // synchronous analysis of every job" actually means at the code level: the call returns via
    // MatchScoreQueueService, not by JobMatchService running the OpenAI call itself. ----

    @Test
    void computeMatchScoresStreaming_enqueuesStaleJobsInsteadOfComputingInline() {
        when(cvAnalysisRepository.findByUserEmail(EMAIL)).thenReturn(Optional.of(doctorAnalysis()));
        Job physicianJob = job(30L, "Physician", "Patient diagnosis, Patient care", "Active medical license required.");

        JobMatchScore queuedResult = new JobMatchScore();
        queuedResult.setCandidateEmail(EMAIL);
        queuedResult.setJobId(30L);
        queuedResult.setFieldRelated(true);
        queuedResult.setMatchPercent(88);

        when(matchScoreQueueService.awaitResult(eq(EMAIL), eq(30L), eq("internal"), any(), any(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(queuedResult));
        // Internal-job embedding lookup (see ensureInternalJobEmbeddings) - the prefilter itself
        // isn't under test here, so embedBatch failing open (empty list) is fine; only modelKey()
        // is called unconditionally and must not NPE.
        when(embeddingService.modelKey()).thenReturn("test-model@1");
        when(embeddingService.embedBatch(anyList())).thenReturn(List.of());

        AtomicInteger resultCount = new AtomicInteger(0);
        AtomicBoolean completed = new AtomicBoolean(false);
        Map<Long, Map<String, Object>> resultsByJobId = new LinkedHashMap<>();

        jobMatchService.computeMatchScoresStreaming(EMAIL, List.of(physicianJob), "en", Map.of(), "internal",
                (jobId, payload) -> {
                    resultCount.incrementAndGet();
                    resultsByJobId.put(jobId, payload);
                },
                () -> completed.set(true));

        assertThat(completed.get()).isTrue();
        assertThat(resultCount.get()).isEqualTo(1);
        assertThat(resultsByJobId.get(30L).get("matchPercent")).isEqualTo(88);

        // The actual behavioral claim: this job was handed to the QUEUE (enqueueIfNeeded), never
        // computed by calling the AI directly from this method.
        verify(matchScoreQueueService).enqueueIfNeeded(eq(EMAIL), eq(physicianJob), eq("internal"), eq("en"), any(), any());
        verifyNoInteractions(openAICVAnalysisService);
    }

    // ---- scenario 19: computeMatchScoresStreaming with an ALREADY-FRESH cached row (same CV,
    // same job content) must serve it straight from the DB - no queue enqueue, no AI call at all.
    // This is the exact "reopening the Job Matches page re-triggers computation" complaint: if
    // this test fails, the streaming path is not actually reusing a fresh cache. ----

    @Test
    void computeMatchScoresStreaming_freshCachedRow_isServedWithoutEnqueueingOrCallingAi() {
        CVAnalysis analysis = doctorAnalysis();
        when(cvAnalysisRepository.findByUserEmail(EMAIL)).thenReturn(Optional.of(analysis));

        Job physicianJob = job(31L, "Physician", "Patient diagnosis, Patient care", "Active medical license required.");

        JobMatchScore fresh = new JobMatchScore();
        fresh.setCandidateEmail(EMAIL);
        fresh.setJobId(31L);
        fresh.setFieldRelated(true);
        fresh.setMatchPercent(91);
        fresh.setCvFingerprint(jobMatchService.fingerprintCv(analysis));
        fresh.setJobFingerprint(jobMatchService.fingerprintJob(physicianJob));

        when(jobMatchScoreRepository.findByCandidateEmailAndJobIdIn(eq(EMAIL), anyList()))
                .thenReturn(List.of(fresh));

        AtomicInteger resultCount = new AtomicInteger(0);
        AtomicBoolean completed = new AtomicBoolean(false);
        Map<Long, Map<String, Object>> resultsByJobId = new LinkedHashMap<>();

        jobMatchService.computeMatchScoresStreaming(EMAIL, List.of(physicianJob), "en", Map.of(), "internal",
                (jobId, payload) -> {
                    resultCount.incrementAndGet();
                    resultsByJobId.put(jobId, payload);
                },
                () -> completed.set(true));

        assertThat(completed.get()).isTrue();
        assertThat(resultCount.get()).isEqualTo(1);
        assertThat(resultsByJobId.get(31L).get("matchPercent")).isEqualTo(91);

        verifyNoInteractions(matchScoreQueueService);
        verifyNoInteractions(openAICVAnalysisService);
    }
}
