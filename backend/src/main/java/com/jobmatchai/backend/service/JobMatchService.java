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
import java.util.Objects;

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
    private static final String MATCH_SCHEMA_VERSION = "v4-education-guardrail";

    // Roles that typically have no formal education requirement - the AI is instructed not to
    // penalize education for these, but this is a defensive backend clamp in case it still does.
    private static final List<String> LOW_EDUCATION_REQUIREMENT_KEYWORDS = List.of(
            "cashier", "sales assistant", "sales associate", "cleaner", "cleaning",
            "warehouse", "driver", "delivery", "waiter", "waitress", "barista",
            "security guard", "courier", "stock", "retail assistant", "housekeeping",
            "kitchen porter", "dishwasher", "receptionist", "customer service representative"
    );

    private static boolean isLowEducationRequirementRole(String jobTitle) {
        if (jobTitle == null) {
            return false;
        }

        String lowerTitle = jobTitle.toLowerCase();
        return LOW_EDUCATION_REQUIREMENT_KEYWORDS.stream().anyMatch(lowerTitle::contains);
    }

    private static Integer applyLowEducationRequirementGuardrail(
            Integer educationMatchPercent, Integer skillsMatchPercent, Integer experienceMatchPercent) {
        if (educationMatchPercent == null || educationMatchPercent >= 50) {
            return educationMatchPercent;
        }

        List<Integer> otherScores = new ArrayList<>();
        if (skillsMatchPercent != null) otherScores.add(skillsMatchPercent);
        if (experienceMatchPercent != null) otherScores.add(experienceMatchPercent);

        if (otherScores.isEmpty()) {
            return 80;
        }

        int average = otherScores.stream().mapToInt((Integer score) -> score).sum() / otherScores.size();
        return Math.max(educationMatchPercent, Math.max(average, 80));
    }

    public record MatchScoresResult(boolean hasAnalysis, List<Map<String, Object>> matches) {}

    public record MatchDetailResult(
            boolean hasAnalysis,
            Long jobId,
            Integer matchPercent,
            String matchReason,
            List<String> matchedSkills,
            List<String> missingSkills,
            List<String> whyGoodMatch,
            List<String> whyNotPerfectMatch,
            List<String> improvementSuggestions,
            String recommendation,
            Boolean shouldApply,
            Boolean fieldRelated,
            Integer skillsMatchPercent,
            Integer experienceMatchPercent,
            Integer educationMatchPercent,
            Integer languageMatchPercent
    ) {}

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
                // A malformed entry (missing matchPercent when the job is field-related) means the AI
                // response for this job was incomplete - skip it rather than caching a fake 0%, which
                // would silently overwrite a previously good score for this job.
                boolean fieldRelated = match.path("fieldRelated").asBoolean(true);
                if (fieldRelated && !match.has("matchPercent")) {
                    continue;
                }

                long jobId = match.path("jobId").asLong();
                Integer matchPercent = fieldRelated ? match.path("matchPercent").asInt(0) : null;
                String matchReason = match.path("matchReason").asText("");
                String matchedSkills = joinSkillsArray(match.path("matchedSkills"));
                String missingSkills = joinSkillsArray(match.path("missingSkills"));

                JobMatchScore score = cachedByJobId.getOrDefault(jobId, new JobMatchScore());
                score.setCandidateEmail(email);
                score.setJobId(jobId);
                score.setFieldRelated(fieldRelated);
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
            match.put("fieldRelated", score.getFieldRelated() == null ? true : score.getFieldRelated());
            match.put("matchPercent", score.getMatchPercent());
            match.put("matchReason", score.getMatchReason());
            match.put("matchedSkills", splitSkillsString(score.getMatchedSkills()));
            match.put("missingSkills", splitSkillsString(score.getMissingSkills()));
            matches.add(match);
        }

        return new MatchScoresResult(true, matches);
    }

    public MatchDetailResult getMatchDetail(String email, Job job, String language) {
        CVAnalysis analysis = cvAnalysisRepository.findByUserEmail(email).orElse(null);

        if (analysis == null) {
            return new MatchDetailResult(false, job.getId(), null, null, List.of(), List.of(), List.of(), List.of(), List.of(), null, null, null, null, null, null, null);
        }

        String cvFingerprint = fingerprintCv(analysis);
        String jobFingerprint = fingerprintJob(job);

        JobMatchScore cached = jobMatchScoreRepository.findByCandidateEmailAndJobId(email, job.getId()).orElse(null);

        // A row is only considered "fully computed" once this detail method has successfully
        // populated recommendation (a non-blank value can only come from a successful run here -
        // getMatchScores never sets it). A blank/null recommendation means either no detail
        // computation has happened yet, or the last attempt failed - either way, retry.
        boolean isStale = cached == null
                || !cvFingerprint.equals(cached.getCvFingerprint())
                || !jobFingerprint.equals(cached.getJobFingerprint())
                || cached.getRecommendation() == null
                || cached.getRecommendation().isBlank();

        JobMatchScore resolved;

        if (isStale) {
            String result = openAICVAnalysisService.computeJobMatchDetail(analysis, job, language);
            JsonNode json = readDetailObject(result);

            // The AI call failed, returned unparsable JSON, or returned an incomplete object
            // (missing matchPercent for a field-related job). Never cache that as a fake 0% -
            // it would silently overwrite a previously good score and desynchronize the overall
            // percent from its breakdown. Serve the last known-good result instead, or - if
            // there isn't one yet - report "not yet available" rather than zeros.
            boolean fieldRelated = json != null && json.path("fieldRelated").asBoolean(true);
            boolean incomplete = json == null || (fieldRelated && !json.has("matchPercent"));

            if (incomplete) {
                if (cached != null) {
                    resolved = cached;
                } else {
                    return new MatchDetailResult(true, job.getId(), null,
                            "We couldn't compute your match for this job right now. Please try again shortly.",
                            List.of(), List.of(), List.of(), List.of(), List.of(),
                            null, null, true, null, null, null, null);
                }
            } else {
                Integer skillsMatchPercent = fieldRelated && json.has("skillsMatchPercent") ? json.path("skillsMatchPercent").asInt() : null;
                Integer experienceMatchPercent = fieldRelated && json.has("experienceMatchPercent") ? json.path("experienceMatchPercent").asInt() : null;
                Integer educationMatchPercent = fieldRelated && json.has("educationMatchPercent") ? json.path("educationMatchPercent").asInt() : null;

                if (fieldRelated && isLowEducationRequirementRole(job.getTitle())) {
                    educationMatchPercent = applyLowEducationRequirementGuardrail(
                            educationMatchPercent, skillsMatchPercent, experienceMatchPercent);
                }

                JobMatchScore score = cached != null ? cached : new JobMatchScore();
                score.setCandidateEmail(email);
                score.setJobId(job.getId());
                score.setFieldRelated(fieldRelated);
                score.setMatchPercent(fieldRelated ? json.path("matchPercent").asInt(0) : null);
                score.setSkillsMatchPercent(skillsMatchPercent);
                score.setExperienceMatchPercent(experienceMatchPercent);
                score.setEducationMatchPercent(educationMatchPercent);
                score.setLanguageMatchPercent(fieldRelated && json.has("languageMatchPercent") ? json.path("languageMatchPercent").asInt() : null);
                score.setMatchReason(json.path("matchReason").asText(""));
                score.setMatchedSkills(joinSkillsArray(json.path("matchedSkills")));
                score.setMissingSkills(joinSkillsArray(json.path("missingSkills")));
                score.setWhyGoodMatch(joinSkillsArray(json.path("whyGoodMatch")));
                score.setWhyNotPerfectMatch(joinSkillsArray(json.path("whyNotPerfectMatch")));
                score.setImprovementSuggestions(joinSkillsArray(json.path("improvementSuggestions")));
                // recommendation must end up non-blank on success so isStale can tell a completed
                // computation apart from one that never ran / previously failed.
                String recommendation = json.path("recommendation").asText("");
                score.setRecommendation(recommendation.isBlank() ? "No specific recommendation available." : recommendation);
                score.setShouldApply(json.path("shouldApply").asBoolean(true));
                score.setCvFingerprint(cvFingerprint);
                score.setJobFingerprint(jobFingerprint);

                resolved = jobMatchScoreRepository.save(score);
            }
        } else {
            // isStale is false only when cached is non-null (see isStale's definition above).
            resolved = Objects.requireNonNull(cached, "cached JobMatchScore must be present when not stale");
        }

        return new MatchDetailResult(
                true,
                resolved.getJobId(),
                resolved.getMatchPercent(),
                resolved.getMatchReason(),
                splitSkillsString(resolved.getMatchedSkills()),
                splitSkillsString(resolved.getMissingSkills()),
                splitSkillsString(resolved.getWhyGoodMatch()),
                splitSkillsString(resolved.getWhyNotPerfectMatch()),
                splitSkillsString(resolved.getImprovementSuggestions()),
                resolved.getRecommendation(),
                resolved.getShouldApply(),
                resolved.getFieldRelated() == null ? true : resolved.getFieldRelated(),
                resolved.getSkillsMatchPercent(),
                resolved.getExperienceMatchPercent(),
                resolved.getEducationMatchPercent(),
                resolved.getLanguageMatchPercent()
        );
    }

    private JsonNode readDetailObject(String result) {
        try {
            JsonNode node = objectMapper.readTree(result);
            return node != null && node.isObject() && node.size() > 0 ? node : null;
        } catch (Exception e) {
            return null;
        }
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
