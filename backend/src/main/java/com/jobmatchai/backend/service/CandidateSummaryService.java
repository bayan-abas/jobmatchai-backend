package com.jobmatchai.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.jobmatchai.backend.model.CVAnalysis;
import com.jobmatchai.backend.model.CandidateAiSummary;
import com.jobmatchai.backend.model.CandidateAiSummaryNarrative;
import com.jobmatchai.backend.model.Job;
import com.jobmatchai.backend.repository.CVAnalysisRepository;
import com.jobmatchai.backend.repository.CandidateAiSummaryNarrativeRepository;
import com.jobmatchai.backend.repository.CandidateAiSummaryRepository;
import com.jobmatchai.backend.util.HashUtil;
import com.jobmatchai.backend.util.MatchLabelUtil;

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
    private CandidateAiSummaryNarrativeRepository candidateAiSummaryNarrativeRepository;

    @Autowired
    private OpenAICVAnalysisService openAICVAnalysisService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String SUMMARY_SCHEMA_VERSION = "v2";

    // מונע שני requests בו-זמנית לאותו candidate+job יריצו קריאת AI כפולה
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
            String matchLabel,

            String recommendation
    ) {}

    // מחזיר תקציר AI למועמד מול משרה - משתמש במטמון אם קורות החיים והמשרה לא השתנו, ומריץ קריאת AI חדשה רק אם הם השתנו
    public SummaryResult getCandidateSummary(String candidateEmail, Job job, String language) {
        String jobFingerprint = fingerprintJob(job);
        String lockKey = candidateEmail + "::" + job.getId();
        Object lock = locks.computeIfAbsent(lockKey, k -> new Object());

        synchronized (lock) {
            CVAnalysis analysis = cvAnalysisRepository.findByUserEmail(candidateEmail).orElse(null);

            if (analysis == null) {
                log.info("[AI-SUMMARY] candidate={} jobId={} -> no CVAnalysis on file, cannot generate a summary",
                        candidateEmail, job.getId());
                return new SummaryResult(false, null, List.of(), null, null, null, null, null, null, null);
            }

            String cvFingerprint = fingerprintCv(analysis);

            CandidateAiSummary cached = candidateAiSummaryRepository
                    .findFirstByCandidateEmailAndJobIdOrderByIdDesc(candidateEmail, job.getId())
                    .orElse(null);

            boolean isStale = cached == null
                    || !cvFingerprint.equals(cached.getCvFingerprint())
                    || !jobFingerprint.equals(cached.getJobFingerprint())
                    || cached.getMatchScore() == null;

            if (cached != null && !isStale) {
                log.info("[AI-SUMMARY] candidate={} jobId={} -> cache HIT (matchScore={}, matchLabel={}); OpenAI NOT called",
                        candidateEmail, job.getId(), cached.getMatchScore(), MatchLabelUtil.fromScore(cached.getMatchScore()));

                return resolveWithNarrative(cached, language, false);
            }

            log.info("[AI-SUMMARY] candidate={} jobId={} -> cache MISS ({}); calling OpenAI now",
                    candidateEmail, job.getId(),
                    cached == null ? "no saved summary yet" : "CV or job content changed since last save");

            String result = openAICVAnalysisService.computeCandidateSummary(analysis, job, language);
            JsonNode json = readObject(result);

            List<String> keySkillsList = extractStringList(json.path("keySkills"));
            String weaknessesText = removeSentencesContradictingKeySkills(json.path("weaknesses").asText(""), keySkillsList);

            CandidateAiSummary summary = cached != null ? cached : new CandidateAiSummary();
            summary.setCandidateEmail(candidateEmail);
            summary.setJobId(job.getId());
            summary.setProfessionalBackground(json.path("professionalBackground").asText(""));
            summary.setKeySkills(String.join("|", keySkillsList));
            summary.setYearsOfExperience(json.path("yearsOfExperience").asText(""));
            summary.setStrengths(json.path("strengths").asText(""));
            summary.setWeaknesses(weaknessesText);
            summary.setOverallSuitability(json.path("overallSuitability").asText(""));

            int matchScore = clampScore(json.path("matchScore").asInt(0));
            summary.setMatchScore(matchScore);

            summary.setCvFingerprint(cvFingerprint);
            summary.setJobFingerprint(jobFingerprint);

            CandidateAiSummary resolved = candidateAiSummaryRepository.save(summary);

            log.info("[AI-SUMMARY] candidate={} jobId={} -> generated and saved new matchScore={}, matchLabel={}",
                    candidateEmail, job.getId(), resolved.getMatchScore(), MatchLabelUtil.fromScore(resolved.getMatchScore()));

            return resolveWithNarrative(resolved, language, true);
        }
    }

    // מחזיר את הטקסט של התקציר בשפה המבוקשת - אם הוא נוצר עכשיו בשפה הזו שומר אותו כמו שהוא, אחרת מביא/מייצר תרגום ממטמון נפרד לפי שפה
    private SummaryResult resolveWithNarrative(CandidateAiSummary resolved, String language, boolean freshlyGeneratedInLanguage) {
        String candidateEmail = resolved.getCandidateEmail();
        Long jobId = resolved.getJobId();

        if (freshlyGeneratedInLanguage) {
            CandidateAiSummaryNarrative narrative = candidateAiSummaryNarrativeRepository
                    .findByCandidateEmailAndJobIdAndLanguage(candidateEmail, jobId, language)
                    .orElse(new CandidateAiSummaryNarrative());
            narrative.setCandidateEmail(candidateEmail);
            narrative.setJobId(jobId);
            narrative.setLanguage(language);
            narrative.setProfessionalBackground(resolved.getProfessionalBackground());
            narrative.setStrengths(resolved.getStrengths());
            narrative.setWeaknesses(resolved.getWeaknesses());
            narrative.setOverallSuitability(resolved.getOverallSuitability());
            narrative.setCvFingerprint(resolved.getCvFingerprint());
            narrative.setJobFingerprint(resolved.getJobFingerprint());
            candidateAiSummaryNarrativeRepository.save(narrative);

            log.info("[AI-SUMMARY-NARRATIVE] candidate={} jobId={} language={} -> native generation, seeded narrative cache",
                    candidateEmail, jobId, language);

            return toResult(resolved, resolved.getProfessionalBackground(), resolved.getStrengths(),
                    resolved.getWeaknesses(), resolved.getOverallSuitability());
        }

        CandidateAiSummaryNarrative cachedNarrative = candidateAiSummaryNarrativeRepository
                .findByCandidateEmailAndJobIdAndLanguage(candidateEmail, jobId, language)
                .orElse(null);

        boolean narrativeStale = cachedNarrative == null
                || !resolved.getCvFingerprint().equals(cachedNarrative.getCvFingerprint())
                || !resolved.getJobFingerprint().equals(cachedNarrative.getJobFingerprint());

        if (!narrativeStale) {
            log.info("[AI-SUMMARY-NARRATIVE] candidate={} jobId={} language={} -> cache HIT; OpenAI NOT called",
                    candidateEmail, jobId, language);
            return toResult(resolved, cachedNarrative.getProfessionalBackground(), cachedNarrative.getStrengths(),
                    cachedNarrative.getWeaknesses(), cachedNarrative.getOverallSuitability());
        }

        log.info("[AI-SUMMARY-NARRATIVE] candidate={} jobId={} language={} -> cache MISS ({}); translating now",
                candidateEmail, jobId, language,
                cachedNarrative == null ? "no translation saved yet" : "CV or job content changed since last translation");

        String translated = openAICVAnalysisService.translateCandidateSummaryNarrative(
                resolved.getProfessionalBackground(), resolved.getStrengths(), resolved.getWeaknesses(),
                resolved.getOverallSuitability(), language);
        JsonNode json = readObject(translated);

        String professionalBackground = json.path("professionalBackground").asText(resolved.getProfessionalBackground());
        String strengths = json.path("strengths").asText(resolved.getStrengths());
        String weaknesses = json.path("weaknesses").asText(resolved.getWeaknesses());
        String overallSuitability = json.path("overallSuitability").asText(resolved.getOverallSuitability());

        CandidateAiSummaryNarrative narrative = cachedNarrative != null ? cachedNarrative : new CandidateAiSummaryNarrative();
        narrative.setCandidateEmail(candidateEmail);
        narrative.setJobId(jobId);
        narrative.setLanguage(language);
        narrative.setProfessionalBackground(professionalBackground);
        narrative.setStrengths(strengths);
        narrative.setWeaknesses(weaknesses);
        narrative.setOverallSuitability(overallSuitability);
        narrative.setCvFingerprint(resolved.getCvFingerprint());
        narrative.setJobFingerprint(resolved.getJobFingerprint());
        candidateAiSummaryNarrativeRepository.save(narrative);

        log.info("[AI-SUMMARY-NARRATIVE] candidate={} jobId={} language={} -> translated and saved",
                candidateEmail, jobId, language);

        return toResult(resolved, professionalBackground, strengths, weaknesses, overallSuitability);
    }

    private SummaryResult toResult(CandidateAiSummary resolved, String professionalBackground, String strengths,
            String weaknesses, String overallSuitability) {
        return new SummaryResult(
                true,
                professionalBackground,
                splitToList(resolved.getKeySkills()),
                resolved.getYearsOfExperience(),
                strengths,
                weaknesses,
                overallSuitability,
                resolved.getMatchScore(),
                MatchLabelUtil.fromScore(resolved.getMatchScore()),
                MatchLabelUtil.recommendationFromScore(resolved.getMatchScore())
        );
    }

    private int clampScore(int score) {
        return Math.max(0, Math.min(score, 100));
    }

    private JsonNode readObject(String result) {
        try {
            return objectMapper.readTree(result);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private List<String> extractStringList(JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray()) {
            return List.of();
        }

        List<String> items = new ArrayList<>();
        for (JsonNode item : arrayNode) {
            String text = item.asText("").trim();
            if (!text.isBlank()) {
                items.add(text);
            }
        }

        return items;
    }

    private static final List<String> ABSENCE_PHRASES = List.of(
            "lack", "lacking", "missing", "don't have", "doesn't have", "not reflected",
            "not evident", "no experience with", "need to develop", "gap in", "not shown", "absent"
    );

    private static final List<String> NEGATION_WORDS = List.of("no ", "not ", "n't", "never ", "without ", "none ");

    // בודק אם המשפט באמת טוען שמשהו חסר (ולא, למשל, "לא חסר לו ניסיון" - שלילה כפולה שהופכת את המשמעות)
    private boolean hasGenuineAbsenceClaim(String textLower) {
        for (String phrase : ABSENCE_PHRASES) {
            int idx = textLower.indexOf(phrase);
            while (idx >= 0) {
                String preceding = textLower.substring(Math.max(0, idx - 25), idx);
                if (NEGATION_WORDS.stream().noneMatch(preceding::contains)) {
                    return true;
                }
                idx = textLower.indexOf(phrase, idx + 1);
            }
        }
        return false;
    }

    // מסנן מתוך "חולשות" משפטים שסותרים כישור שכבר מופיע ברשימת ה-keySkills, כדי שה-AI לא יגיד גם "יש לו" וגם "חסר לו" אותו דבר
    private String removeSentencesContradictingKeySkills(String weaknesses, List<String> keySkills) {
        if (weaknesses == null || weaknesses.isBlank() || keySkills.isEmpty()) {
            return weaknesses == null ? "" : weaknesses;
        }

        String[] sentences = weaknesses.split("(?<=[.!?])\\s+");
        List<String> kept = new ArrayList<>();

        for (String sentence : sentences) {
            if (sentence.isBlank()) {
                continue;
            }
            String lower = sentence.toLowerCase();
            boolean hasAbsencePhrase = hasGenuineAbsenceClaim(lower);
            boolean namesKeySkill = hasAbsencePhrase && keySkills.stream()
                    .anyMatch(skill -> skill.length() >= 3 && lower.contains(skill.toLowerCase()));

            if (namesKeySkill) {
                log.info("[AI-SUMMARY] dropped weaknesses sentence contradicting keySkills: \"{}\"", sentence.trim());
                continue;
            }
            kept.add(sentence);
        }

        return String.join(" ", kept).trim();
    }

    private List<String> splitToList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }

        return List.of(value.split("\\|"));
    }

    // מייצר טביעת אצבע לקורות החיים הנוכחיים כדי לדעת אם הם השתנו מאז התקציר השמור ולהחליט אם צריך לרענן אותו
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

    // מייצר טביעת אצבע למשרה כדי לדעת אם פרטיה השתנו מאז התקציר השמור ולהחליט אם צריך לרענן אותו
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
