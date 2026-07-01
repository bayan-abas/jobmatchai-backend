package com.jobmatchai.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.jobmatchai.backend.model.CVAnalysis;
import com.jobmatchai.backend.model.Job;
import com.jobmatchai.backend.model.JobMatchScore;
import com.jobmatchai.backend.repository.CVAnalysisRepository;
import com.jobmatchai.backend.repository.JobMatchScoreRepository;
import com.jobmatchai.backend.util.HashUtil;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class JobMatchService {

    @Autowired
    private CVAnalysisRepository cvAnalysisRepository;

    @Autowired
    private JobMatchScoreRepository jobMatchScoreRepository;

    @Autowired
    private OpenAICVAnalysisService openAICVAnalysisService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Bump this whenever the AI prompt/response schema for computeJobMatches changes,
    // so previously cached rows (missing the new fields) are treated as stale and recomputed.
    private static final String MATCH_SCHEMA_VERSION = "v2-skills";

    public record MatchScoresResult(boolean hasAnalysis, List<Map<String, Object>> matches) {}

    public MatchScoresResult getMatchScores(String email, List<Job> jobs, String language) {
        CVAnalysis analysis = cvAnalysisRepository.findByUserEmail(email).orElse(null);

        if (analysis == null) {
            return new MatchScoresResult(false, List.of());
        }

        if (jobs == null || jobs.isEmpty()) {
            return new MatchScoresResult(true, List.of());
        }

        String cvFingerprint = fingerprintCv(analysis);

        List<Long> jobIds = jobs.stream().map(job -> job.getId()).toList();
        Map<Long, JobMatchScore> cachedByJobId = new HashMap<>();
        for (JobMatchScore score : jobMatchScoreRepository.findByCandidateEmailAndJobIdIn(email, jobIds)) {
            cachedByJobId.put(score.getJobId(), score);
        }

        Map<Long, String> jobFingerprints = new HashMap<>();
        List<Job> jobsNeedingComputation = new ArrayList<>();

        for (Job job : jobs) {
            String jobFingerprint = fingerprintJob(job);
            jobFingerprints.put(job.getId(), jobFingerprint);

            JobMatchScore cached = cachedByJobId.get(job.getId());
            boolean isStale = cached == null
                    || !cvFingerprint.equals(cached.getCvFingerprint())
                    || !jobFingerprint.equals(cached.getJobFingerprint());

            if (isStale) {
                jobsNeedingComputation.add(job);
            }
        }

        if (!jobsNeedingComputation.isEmpty()) {
            String matchResult = openAICVAnalysisService.computeJobMatches(analysis, jobsNeedingComputation, language);
            JsonNode matchesJson = readMatchesArray(matchResult);

            for (JsonNode match : matchesJson) {
                long jobId = match.path("jobId").asLong();
                int matchPercent = match.path("matchPercent").asInt();
                String matchReason = match.path("matchReason").asText("");
                String matchedSkills = joinSkillsArray(match.path("matchedSkills"));
                String missingSkills = joinSkillsArray(match.path("missingSkills"));

                JobMatchScore score = cachedByJobId.getOrDefault(jobId, new JobMatchScore());
                score.setCandidateEmail(email);
                score.setJobId(jobId);
                score.setMatchPercent(matchPercent);
                score.setMatchReason(matchReason);
                score.setMatchedSkills(matchedSkills);
                score.setMissingSkills(missingSkills);
                score.setCvFingerprint(cvFingerprint);
                score.setJobFingerprint(jobFingerprints.get(jobId));

                score = jobMatchScoreRepository.save(score);
                cachedByJobId.put(jobId, score);
            }
        }

        List<Map<String, Object>> matches = new ArrayList<>();
        for (Job job : jobs) {
            JobMatchScore score = cachedByJobId.get(job.getId());
            if (score == null) {
                continue;
            }

            Map<String, Object> match = new LinkedHashMap<>();
            match.put("jobId", score.getJobId());
            match.put("matchPercent", score.getMatchPercent());
            match.put("matchReason", score.getMatchReason());
            match.put("matchedSkills", splitSkillsString(score.getMatchedSkills()));
            match.put("missingSkills", splitSkillsString(score.getMissingSkills()));
            matches.add(match);
        }

        return new MatchScoresResult(true, matches);
    }

    private String joinSkillsArray(JsonNode skillsNode) {
        if (skillsNode == null || !skillsNode.isArray()) {
            return "";
        }

        List<String> skills = new ArrayList<>();
        for (JsonNode skill : skillsNode) {
            String text = skill.asText("").trim();
            if (!text.isBlank()) {
                skills.add(text);
            }
        }

        return String.join("|", skills);
    }

    private List<String> splitSkillsString(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }

        return List.of(value.split("\\|"));
    }

    private JsonNode readMatchesArray(String matchResult) {
        try {
            return objectMapper.readTree(matchResult).path("matches");
        } catch (Exception e) {
            return objectMapper.createArrayNode();
        }
    }

    private String fingerprintCv(CVAnalysis analysis) {
        String cvTextHash = analysis.getCvTextHash();

        // Preferred: fingerprint the actual uploaded CV text. This stays stable across
        // repeated "Analyze" clicks on the same file, even though the AI-generated
        // summary/strengths/skills wording can vary slightly between analysis runs.
        if (cvTextHash != null && !cvTextHash.isBlank()) {
            return HashUtil.sha256(MATCH_SCHEMA_VERSION + "|cvtext|" + cvTextHash);
        }

        // Fallback for CVAnalysis rows saved before CV-text hashing existed.
        return HashUtil.sha256(String.join("|",
                MATCH_SCHEMA_VERSION,
                "legacy",
                nullToEmpty(analysis.getCandidateField()),
                nullToEmpty(analysis.getSkills()),
                nullToEmpty(analysis.getSummary()),
                nullToEmpty(analysis.getStrengths()),
                nullToEmpty(analysis.getMissingSkills()),
                nullToEmpty(analysis.getRecommendedRoles()),
                nullToEmpty(analysis.getOverallScore())
        ));
    }

    private String fingerprintJob(Job job) {
        return HashUtil.sha256(String.join("|",
                nullToEmpty(job.getTitle()),
                nullToEmpty(job.getType()),
                nullToEmpty(job.getLocation()),
                nullToEmpty(job.getSkills()),
                nullToEmpty(job.getRequirements()),
                nullToEmpty(job.getDescription())
        ));
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
