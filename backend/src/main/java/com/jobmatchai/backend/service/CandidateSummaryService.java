package com.jobmatchai.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.jobmatchai.backend.model.CVAnalysis;
import com.jobmatchai.backend.model.CandidateAiSummary;
import com.jobmatchai.backend.model.Job;
import com.jobmatchai.backend.repository.CVAnalysisRepository;
import com.jobmatchai.backend.repository.CandidateAiSummaryRepository;
import com.jobmatchai.backend.util.HashUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CandidateSummaryService {

    private static final Logger log = LoggerFactory.getLogger(CandidateSummaryService.class);

    @Autowired
    private CVAnalysisRepository cvAnalysisRepository;

    @Autowired
    private CandidateAiSummaryRepository candidateAiSummaryRepository;

    @Autowired
    private OpenAICVAnalysisService openAICVAnalysisService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Bump this whenever the AI prompt/response schema for computeCandidateSummary changes,
    // so previously cached rows (missing the new fields) are treated as stale and recomputed.
    // v2: added matchScore/matchLabel to the prompt/schema - rows saved under v1 have those
    // fields null and must be forced to regenerate once, which bumping this version does.
    private static final String SUMMARY_SCHEMA_VERSION = "v2";

    // Per (candidateEmail, jobId) locks. Without this, two overlapping requests for the
    // same candidate+job (e.g. the modal reopened before the first OpenAI call finished)
    // can both see "no cached row yet" and both call OpenAI, producing two different
    // scores and a race on which one gets saved last. Serializing on this key means the
    // second request always waits and then reads back what the first one just saved.
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public record SummaryResult(
            boolean hasAnalysis,
            String professionalBackground,
            List<String> keySkills,
            String yearsOfExperience,
            String strengths,
            String weaknesses,
            String overallSuitability,
            Integer matchScore,
            String matchLabel
    ) {}

    public SummaryResult getCandidateSummary(String candidateEmail, Job job, String language) {
        CVAnalysis analysis = cvAnalysisRepository.findByUserEmail(candidateEmail).orElse(null);

        if (analysis == null) {
            log.info("[AI-SUMMARY] candidate={} jobId={} -> no CVAnalysis on file, cannot generate a summary",
                    candidateEmail, job.getId());
            return new SummaryResult(false, null, List.of(), null, null, null, null, null, null);
        }

        String cvFingerprint = fingerprintCv(analysis);
        String jobFingerprint = fingerprintJob(job);
        String lockKey = candidateEmail + "::" + job.getId();
        Object lock = locks.computeIfAbsent(lockKey, k -> new Object());

        // Everything from the cache read to the cache write happens under this lock, so a
        // second request for the same candidate+job that arrives while the first is still
        // waiting on OpenAI blocks here instead of racing it, then simply reads the row the
        // first request just saved.
        synchronized (lock) {
            CandidateAiSummary cached = candidateAiSummaryRepository
                    .findFirstByCandidateEmailAndJobIdOrderByIdDesc(candidateEmail, job.getId())
                    .orElse(null);

            boolean isStale = cached == null
                    || !cvFingerprint.equals(cached.getCvFingerprint())
                    || !jobFingerprint.equals(cached.getJobFingerprint())
                    || cached.getMatchScore() == null;

            if (cached != null && !isStale) {
                log.info("[AI-SUMMARY] candidate={} jobId={} -> cache HIT (matchScore={}, matchLabel={}); OpenAI NOT called",
                        candidateEmail, job.getId(), cached.getMatchScore(), cached.getMatchLabel());

                return toResult(cached);
            }

            log.info("[AI-SUMMARY] candidate={} jobId={} -> cache MISS ({}); calling OpenAI now",
                    candidateEmail, job.getId(),
                    cached == null ? "no saved summary yet" : "CV or job content changed since last save");

            String result = openAICVAnalysisService.computeCandidateSummary(analysis, job, language);
            JsonNode json = readObject(result);

            CandidateAiSummary summary = cached != null ? cached : new CandidateAiSummary();
            summary.setCandidateEmail(candidateEmail);
            summary.setJobId(job.getId());
            summary.setProfessionalBackground(json.path("professionalBackground").asText(""));
            summary.setKeySkills(joinArray(json.path("keySkills")));
            summary.setYearsOfExperience(json.path("yearsOfExperience").asText(""));
            summary.setStrengths(json.path("strengths").asText(""));
            summary.setWeaknesses(json.path("weaknesses").asText(""));
            summary.setOverallSuitability(json.path("overallSuitability").asText(""));

            int matchScore = clampScore(json.path("matchScore").asInt(0));
            summary.setMatchScore(matchScore);
            summary.setMatchLabel(deriveMatchLabel(matchScore));

            summary.setCvFingerprint(cvFingerprint);
            summary.setJobFingerprint(jobFingerprint);

            CandidateAiSummary resolved = candidateAiSummaryRepository.save(summary);

            log.info("[AI-SUMMARY] candidate={} jobId={} -> generated and saved new matchScore={}, matchLabel={}",
                    candidateEmail, job.getId(), resolved.getMatchScore(), resolved.getMatchLabel());

            return toResult(resolved);
        }
    }

    private SummaryResult toResult(CandidateAiSummary resolved) {
        return new SummaryResult(
                true,
                resolved.getProfessionalBackground(),
                splitToList(resolved.getKeySkills()),
                resolved.getYearsOfExperience(),
                resolved.getStrengths(),
                resolved.getWeaknesses(),
                resolved.getOverallSuitability(),
                resolved.getMatchScore(),
                resolved.getMatchLabel()
        );
    }

    private int clampScore(int score) {
        return Math.max(0, Math.min(score, 100));
    }

    private String deriveMatchLabel(int score) {
        if (score >= 85) return "Excellent Match";
        if (score >= 70) return "Strong Match";
        if (score >= 50) return "Moderate Match";
        if (score >= 30) return "Weak Match";
        return "Poor Match";
    }

    private JsonNode readObject(String result) {
        try {
            return objectMapper.readTree(result);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private String joinArray(JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray()) {
            return "";
        }

        List<String> items = new ArrayList<>();
        for (JsonNode item : arrayNode) {
            String text = item.asText("").trim();
            if (!text.isBlank()) {
                items.add(text);
            }
        }

        return String.join("|", items);
    }

    private List<String> splitToList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }

        return List.of(value.split("\\|"));
    }

    private String fingerprintCv(CVAnalysis analysis) {
        String cvTextHash = analysis.getCvTextHash();

        if (cvTextHash != null && !cvTextHash.isBlank()) {
            return HashUtil.sha256(SUMMARY_SCHEMA_VERSION + "|cvtext|" + cvTextHash);
        }

        return HashUtil.sha256(String.join("|",
                SUMMARY_SCHEMA_VERSION,
                "legacy",
                nullToEmpty(analysis.getCandidateField()),
                nullToEmpty(analysis.getSkills()),
                nullToEmpty(analysis.getSummary()),
                nullToEmpty(analysis.getStrengths()),
                nullToEmpty(analysis.getMissingSkills())
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
