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
            String matchLabel,
            // Fixed-vocabulary hiring-decision category ("accept"/"consider"/"reject"), derived
            // from matchScore by MatchLabelUtil - the single authoritative source for this
            // judgment (see MatchLabelUtil#recommendationFromScore). The frontend only maps this
            // category to a localized label/description; it never decides the category itself.
            String recommendation
    ) {}

    public SummaryResult getCandidateSummary(String candidateEmail, Job job, String language) {
        String jobFingerprint = fingerprintJob(job);
        String lockKey = candidateEmail + "::" + job.getId();
        Object lock = locks.computeIfAbsent(lockKey, k -> new Object());

        // Everything from the CVAnalysis read to the cache write happens under this lock, so a
        // second request for the same candidate+job that arrives while the first is still
        // waiting on OpenAI blocks here instead of racing it, then simply reads the row the
        // first request just saved. `analysis`/`cvFingerprint` are deliberately read INSIDE the
        // lock (not before it) - reading them before blocking would risk this request being
        // granted the lock only after a CONCURRENT CV replace on the same account has already run
        // (which deletes this candidate's rows via CVController.discardStaleMatchScores), in which
        // case a pre-lock snapshot would silently resurrect a summary generated against the CV
        // that request just invalidated.
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

            // Self-consistency guard: keySkills and weaknesses are two independent fields the SAME
            // AI call can disagree with itself on - nothing previously stopped a skill from being
            // listed in keySkills (a positive claim) while a weaknesses sentence separately claims
            // the candidate lacks that exact skill (a negative claim about the identical evidence).
            // Rather than silently trusting whichever field a reader happens to look at first, drop
            // the specific contradicting SENTENCE from weaknesses (never the keySkills entry itself
            // - keySkills is the more specific, structured claim) - same "drop the unsupported
            // claim rather than the validated data" precedent JobMatchService.validateDetailClaims
            // already uses for whyGoodMatch/whyNotPerfectMatch vs. matchedSkills/missingSkills.
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

    // Resolves the narrative text (professionalBackground/strengths/weaknesses/overallSuitability)
    // for `language`, independently of `resolved`'s own matchScore/keySkills/yearsOfExperience
    // (which are ALWAYS taken from `resolved` regardless of language - never recomputed, never
    // duplicated). `freshlyGeneratedInLanguage` is true only when `resolved` was JUST generated (or
    // regenerated) by computeCandidateSummary using `language` as its target language - in that
    // case its own text fields ARE already the correct-language text, so this seeds the narrative
    // cache directly from them instead of paying for a redundant self-translate call.
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

    // Mirrors JobMatchService.ABSENCE_PHRASES - phrases pairing a skill mention with claimed
    // absence. Kept as its own small local copy rather than a shared constant: this method
    // operates on whole SENTENCES (not the short, atomic bullets validateDetailClaims checks), so
    // it deliberately requires the cue phrase and the skill name to co-occur within the same
    // sentence - a much smaller, less ambiguous unit than a whole weaknesses paragraph, which
    // keeps the false-positive rate low without needing to share validation logic across classes
    // built around different-shaped input (bullet lists vs. free-text prose).
    private static final List<String> ABSENCE_PHRASES = List.of(
            "lack", "lacking", "missing", "don't have", "doesn't have", "not reflected",
            "not evident", "no experience with", "need to develop", "gap in", "not shown", "absent"
    );

    // Words that, appearing shortly before an ABSENCE_PHRASES match, flip its meaning from a real
    // gap claim into an explicit denial of one - e.g. "does not indicate any missing skills...
    // all key requirements, including Java, Spring Boot, REST APIs, are well covered" CONFIRMS
    // there is no gap, even though it contains "missing". Found via real end-to-end testing: a
    // genuinely gap-free candidate's weaknesses text was having every one of its own keySkills
    // wrongly stripped out as "contradicting" itself, because the bare substring check never
    // accounted for the sentence explicitly negating its own absence phrase.
    private static final List<String> NEGATION_WORDS = List.of("no ", "not ", "n't", "never ", "without ", "none ");

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

    // Drops any sentence in `weaknesses` that both (a) names a skill already listed in keySkills
    // and (b) uses an absence phrase - i.e. a sentence claiming the candidate lacks a skill the
    // SAME AI response's keySkills field just said they have. An empty or short result is left as
    // whatever remains (including empty) rather than backfilled with invented text - consistent
    // with this codebase's "an honest empty list beats a fabricated one" rule (see
    // JobMatchService.validateDetailClaims's docstring for the same principle applied to bullets).
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
