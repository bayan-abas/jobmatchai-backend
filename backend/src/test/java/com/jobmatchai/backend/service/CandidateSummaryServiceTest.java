package com.jobmatchai.backend.service;

import com.jobmatchai.backend.model.CVAnalysis;
import com.jobmatchai.backend.model.Job;
import com.jobmatchai.backend.repository.CVAnalysisRepository;
import com.jobmatchai.backend.repository.CandidateAiSummaryNarrativeRepository;
import com.jobmatchai.backend.repository.CandidateAiSummaryRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CandidateSummaryServiceTest {

    @Mock
    private CVAnalysisRepository cvAnalysisRepository;
    @Mock
    private CandidateAiSummaryRepository candidateAiSummaryRepository;
    @Mock
    private CandidateAiSummaryNarrativeRepository candidateAiSummaryNarrativeRepository;
    @Mock
    private OpenAICVAnalysisService openAICVAnalysisService;

    private CandidateSummaryService candidateSummaryService;

    private static final String EMAIL = "candidate@example.com";

    @BeforeEach
    void setUp() {
        candidateSummaryService = new CandidateSummaryService();
        ReflectionTestUtils.setField(candidateSummaryService, "cvAnalysisRepository", cvAnalysisRepository);
        ReflectionTestUtils.setField(candidateSummaryService, "candidateAiSummaryRepository", candidateAiSummaryRepository);
        ReflectionTestUtils.setField(candidateSummaryService, "candidateAiSummaryNarrativeRepository", candidateAiSummaryNarrativeRepository);
        ReflectionTestUtils.setField(candidateSummaryService, "openAICVAnalysisService", openAICVAnalysisService);

        CVAnalysis analysis = new CVAnalysis();
        analysis.setUserEmail(EMAIL);
        analysis.setCvTextHash("cv-hash");
        when(cvAnalysisRepository.findByUserEmail(EMAIL)).thenReturn(Optional.of(analysis));
        when(candidateAiSummaryRepository.findFirstByCandidateEmailAndJobIdOrderByIdDesc(any(), any()))
                .thenReturn(Optional.empty());
        when(candidateAiSummaryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        when(candidateAiSummaryNarrativeRepository.findByCandidateEmailAndJobIdAndLanguage(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(candidateAiSummaryNarrativeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private Job job() {
        Job job = new Job("Senior Software Engineer", "Acme Co", null, "Tel Aviv", "Full-time",
                "20000", "Backend role", "5+ years", "Java, Spring Boot, REST APIs");
        job.setId(1L);
        return job;
    }

    @Test
    void weaknessesSentence_genuinelyContradictingKeySkill_isDropped() {
        when(openAICVAnalysisService.computeCandidateSummary(any(), any(), any())).thenReturn("""
                {
                  "professionalBackground": "A backend engineer.",
                  "keySkills": ["Java", "Spring Boot"],
                  "yearsOfExperience": "5-7 years",
                  "strengths": "Strong backend fundamentals.",
                  "weaknesses": "The candidate is lacking Java experience. Overall a solid profile.",
                  "overallSuitability": "A reasonable fit.",
                  "matchScore": 70
                }
                """);

        CandidateSummaryService.SummaryResult result = candidateSummaryService.getCandidateSummary(EMAIL, job(), "en");

        assertThat(result.weaknesses())
                .as("a sentence genuinely claiming keySkill 'Java' is lacking must be dropped")
                .doesNotContain("lacking Java")
                .contains("Overall a solid profile.");
    }

    @Test
    void weaknessesSentence_explicitlyDenyingAnyGap_isNotDropped() {
        when(openAICVAnalysisService.computeCandidateSummary(any(), any(), any())).thenReturn("""
                {
                  "professionalBackground": "A backend engineer.",
                  "keySkills": ["Java", "Spring Boot", "REST APIs"],
                  "yearsOfExperience": "5-7 years",
                  "strengths": "Strong backend fundamentals.",
                  "weaknesses": "The candidate's profile does not indicate any missing skills relative to the core requirements of this job. All key technical requirements, including Java, Spring Boot, and REST APIs, are well covered.",
                  "overallSuitability": "A strong fit.",
                  "matchScore": 90
                }
                """);

        CandidateSummaryService.SummaryResult result = candidateSummaryService.getCandidateSummary(EMAIL, job(), "en");

        assertThat(result.weaknesses())
                .as("a sentence explicitly denying any gap must never be stripped just for naming the "
                        + "candidate's own keySkills as what's covered - this is production text found via "
                        + "real (non-mocked) end-to-end testing that was previously being incorrectly gutted")
                .contains("does not indicate any missing skills")
                .contains("Java")
                .contains("Spring Boot")
                .contains("REST APIs");
    }

    @Test
    void recommendation_isDerivedFromMatchScore_neverIndependentlyInvented() {
        when(openAICVAnalysisService.computeCandidateSummary(any(), any(), any())).thenReturn("""
                {
                  "professionalBackground": "A backend engineer.",
                  "keySkills": ["Java"],
                  "yearsOfExperience": "5-7 years",
                  "strengths": "Strong backend fundamentals.",
                  "weaknesses": "",
                  "overallSuitability": "A strong fit.",
                  "matchScore": 85
                }
                """);

        CandidateSummaryService.SummaryResult result = candidateSummaryService.getCandidateSummary(EMAIL, job(), "en");

        assertThat(result.matchScore()).isEqualTo(85);
        assertThat(result.recommendation()).isEqualTo("accept");
        assertThat(result.matchLabel()).isEqualTo("Excellent Match");
    }
}
