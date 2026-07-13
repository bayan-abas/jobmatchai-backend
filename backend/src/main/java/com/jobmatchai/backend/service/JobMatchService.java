package com.jobmatchai.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.jobmatchai.backend.model.CVAnalysis;
import com.jobmatchai.backend.model.Job;
import com.jobmatchai.backend.model.JobMatchScore;
import com.jobmatchai.backend.repository.CVAnalysisRepository;
import com.jobmatchai.backend.repository.JobMatchScoreRepository;
import com.jobmatchai.backend.util.HashUtil;
import com.jobmatchai.backend.util.MatchScoreCalculator;
import com.jobmatchai.backend.util.MatchScoreCalculator.Component;
import com.jobmatchai.backend.util.MatchScoreCalculator.ComponentKey;
import com.jobmatchai.backend.util.MatchScoreCalculator.WeightedResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

@Service
public class JobMatchService {

    private static final Logger log = LoggerFactory.getLogger(JobMatchService.class);

    @Autowired
    private CVAnalysisRepository cvAnalysisRepository;

    @Autowired
    private JobMatchScoreRepository jobMatchScoreRepository;

    @Autowired
    private NotificationService notificationService;

    // Candidates get a "high match" notification the first time a job scores at or above
    // this, so it's worth wiring even though the frontend has had UI for this type for a
    // while with nothing on the backend ever actually creating it.
    private static final int HIGH_MATCH_NOTIFICATION_THRESHOLD = 80;

    @Autowired
    private OpenAICVAnalysisService openAICVAnalysisService;

    @Autowired
    private EmbeddingService embeddingService;

    @Value("${matching.embedding.prefilter.enabled:false}")
    private boolean prefilterEnabled;

    @Value("${matching.embedding.prefilter.threshold:0.15}")
    private float prefilterThreshold;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Guards against two overlapping requests for the same candidate+job both hitting a cache
    // miss and both firing an AI call - a real risk once scoring moves off one self-serializing
    // blocking HTTP request (see computeMatchScoresStreaming): a second request for a key
    // already being computed joins the SAME CompletableFuture instead of duplicating work. Only
    // protects within this one JVM - jobMatchScoreRepositorySafeSave is the cross-process
    // backstop for the same race.
    private final ConcurrentHashMap<String, CompletableFuture<JobMatchScore>> inFlightComputations =
            new ConcurrentHashMap<>();

    // Bump this whenever the AI prompt/response schema for computeJobMatches changes, OR
    // whenever a fix could change what the AI decides for an already-cached candidate+job pair
    // - the fingerprint cache below only keys off CV text + job text + this version string, so
    // an unchanged CV/job pair is NEVER recomputed unless this version changes, no matter how
    // the scoring logic around it improves.
    // v10: full rework - the AI no longer invents one overall matchPercent; it judges separate
    // components (field relevance, mandatory/preferred skills, experience, education,
    // certification/license, location) which the backend combines with fixed weights via
    // MatchScoreCalculator. fieldRelated now means "same professional field", never "perfect
    // match" - a doctor vs. a nurse job is fieldRelated=true with a lower certification
    // component, not fieldRelated=false. Every AI response is validated against the candidate's
    // persisted structured evidence and the job's own text before being trusted, with one
    // feedback-guided retry before falling back to the honest "couldn't compute" sentinel.
    // v11: fixed a live-testing regression from v10 - a doctor/software engineer/cleaning
    // supervisor CV against "Office Cleaner"/"Customer Service Representative" postings was
    // coming back fieldRelated=false ("-"), because the fieldRelationCloseness rewrite dropped
    // the general-vocational-role exception the old prompt had. Now backend-enforced instead of
    // prompt-only (see GENERAL_VOCATIONAL_ROLE_KEYWORDS).
    // v12: fixed another live-testing finding - a doctor (licensesEvidence="licensed", for
    // MEDICINE) against a Registered Nurse posting requiring a "specific_license" was scoring
    // certificationMatchPercent=100, since licensesEvidence doesn't record WHICH profession the
    // license is for. Now scoreCertification discounts a "specific_license" match heavily unless
    // fieldRelationCloseness is same_role/same_specialization (see sameSpecificRole).
    //
    // v13: fixed a live-data finding - two real internal jobs titled/described "doctor" (skills
    // field left as stale "React, TypeScript" copy-pasted from a different posting template)
    // were coming back fieldRelationCloseness="unrelated" against a doctor CV, because the
    // computeJobMatches prompt let the job's own (wrong) skills field outweigh its own
    // unambiguous title/description. The prompt now explicitly treats a job's Title/Description
    // as authoritative over a contradictory skills field for this judgment.
    //
    // v14: fixed a live-data finding - external postings "Director, Delivery (EMEA)" (a senior
    // service-delivery leadership role) and "...Delivery Station Customer Support" were both
    // force-boosted to 85% for a doctor CV, because isGeneralVocationalRole matched the bare
    // substring "delivery" - a keyword meant for actual delivery-driver jobs, not "service
    // delivery" as a business term. Tightened several keywords that were ambiguous as bare
    // substrings ("delivery" -> "delivery driver"/"delivery associate", "driver" removed
    // entirely since "driver" alone also false-matches unrelated titles like "Device Driver
    // Engineer", "warehouse"/"stock" narrowed to specific role phrases) and added a seniority
    // guard: a title carrying a clear leadership/strategic-scope word (director, manager, head
    // of, VP, chief, principal, executive, president) is never treated as a general/vocational
    // role even if it also contains one of these keywords, since "anyone can do this job
    // regardless of background" is definitionally false for a director-level role.
    // v15: experience credit is now discounted one rank whenever fieldRelationCloseness is only
    // same_broad_field (not the candidate's own specific role/specialization) - the candidate's
    // experienceLevel is a single blanket seniority bucket that doesn't record which field it
    // was earned in, so e.g. senior-level Customer Service experience was getting counted at
    // full seniority credit against a QA Engineer posting just because some broad-field
    // relation existed. See MatchScoreCalculator#scoreExperience's sameSpecificRole parameter -
    // same discount pattern scoreCertification already used for licenses.
    // v16: fixed a live-testing finding - a doctor's CV was scoring 83-85% overall against a
    // Cashier posting. The general-vocational-role override (see GENERAL_VOCATIONAL_ROLE_KEYWORDS
    // below) was scoring fieldRelevance at 85 - higher than even same_specialization's 80 - and
    // still fully scoring experience, so a senior candidate's unrelated-field seniority trivially
    // cleared the entry-level requirement. Field relevance for this case is now 25 (see
    // MatchScoreCalculator#scoreFieldRelevance), and experience is excluded entirely for
    // vocational roles, same as education already was.
    private static final String MATCH_SCHEMA_VERSION = "v16-vocational-role-score-capped";

    // General/entry-level/vocational roles - ones that don't require specialized prior training,
    // a degree, or domain-specific tools to perform. Two separate backend overrides key off this
    // same list: (1) never score the education component for these (below), and (2) never let
    // fieldRelationCloseness come back "unrelated" for these (see applyParsedMatchToScore) -
    // almost any reliable adult can work as a cashier or cleaner regardless of their specialized
    // background, so a software engineer or a doctor applying to one of these must still get a
    // real percentage, not "-". Verified live: without override (2), a doctor/software engineer/
    // cleaning supervisor CV against "Office Cleaner"/"Customer Service Representative" postings
    // came back fieldRelated=false, contradicting this exact design intent - the prompt alone
    // didn't reliably carry the exception, so it's enforced here instead of trusted to the model.
    //
    // Deliberately specific phrases rather than single ambiguous words (see v14 above) - a bare
    // "delivery", "driver", "warehouse", or "stock" is a substring of plenty of skilled/senior
    // titles ("Director, Delivery", "Device Driver Engineer", "Warehouse Operations Manager",
    // "Senior Stock Analyst") that have nothing to do with an entry-level vocational role.
    private static final List<String> GENERAL_VOCATIONAL_ROLE_KEYWORDS = List.of(
            "cashier", "sales assistant", "sales associate", "cleaner", "cleaning",
            "warehouse associate", "warehouse worker", "delivery driver", "delivery associate",
            "food delivery", "waiter", "waitress", "barista",
            "security guard", "courier", "stock associate", "stock clerk", "retail assistant",
            "housekeeping", "kitchen porter", "dishwasher", "receptionist",
            "customer service representative"
    );

    // A leadership/strategic-scope title is never "anyone can do this regardless of background" -
    // it overrides a keyword match above (see isGeneralVocationalRole), catching cases the
    // narrower phrases in that list alone wouldn't (e.g. "Customer Service Manager" still
    // contains "customer service representative"? no - but a future keyword addition might
    // reintroduce this exact false-positive shape, so this guard is a general-purpose backstop,
    // not just a fix for the one "Director, Delivery" case that surfaced it).
    private static final List<String> SENIORITY_EXCLUSION_KEYWORDS = List.of(
            "director", "manager", "head of", " vp ", "vp,", "vice president", "chief",
            "principal", "executive", "president"
    );

    private static boolean isGeneralVocationalRole(String jobTitle) {
        if (jobTitle == null) {
            return false;
        }

        String lowerTitle = " " + jobTitle.toLowerCase(Locale.ROOT) + " ";

        if (SENIORITY_EXCLUSION_KEYWORDS.stream().anyMatch(lowerTitle::contains)) {
            return false;
        }

        return GENERAL_VOCATIONAL_ROLE_KEYWORDS.stream().anyMatch(lowerTitle::contains);
    }

    // The deterministic, keyword-free pre-filter gate (see EmbeddingService): decides whether a
    // job is even worth an AI classification call, using ONLY cosine similarity between two
    // already-computed embedding vectors - never a job title/skills substring. isGeneralVocationalRole
    // is consulted here strictly as a one-way backstop that FORCES AI-eligibility (a vocational
    // role must never be silently skipped by a semantic-distance heuristic); it can never be the
    // reason a job gets excluded, which is what keeps the exclusion decision itself keyword-free.
    // Fails open on every axis: disabled flag, missing profile vector, or missing job vector all
    // return false (send to AI) rather than guessing.
    private boolean shouldSkipAiViaPrefilter(Job job, float[] profileVector, float[] jobVector) {
        if (!prefilterEnabled || profileVector == null || jobVector == null) {
            return false;
        }
        if (isGeneralVocationalRole(job.getTitle())) {
            return false;
        }
        return EmbeddingService.cosineSimilarity(profileVector, jobVector) < prefilterThreshold;
    }

    // Persists a pre-filter skip through the exact same code path an AI "unrelated" verdict
    // already uses (applyParsedMatchToScore) via a synthetic ParsedMatch, instead of duplicating
    // persistence logic - the resulting JobMatchScore row (fieldRelated=false, matchPercent=null)
    // is indistinguishable from, and just as cache-valid as, one the AI itself produced.
    private void applyPrefilteredUnrelatedVerdict(
            JobMatchScore score, Job job, CVAnalysis analysis, String email, long jobId,
            String cvFingerprint, String jobFingerprint, String jobContentFingerprint) {

        ParsedMatch synthetic = new ParsedMatch(
                jobId, job.getTitle(), jobContentFingerprint, "unrelated",
                "Based on your profile, this role appears to be in a different field.",
                List.of(), List.of(), List.of(), List.of(), null, null, null);

        applyParsedMatchToScore(score, synthetic, job, analysis, email, jobId,
                cvFingerprint, jobFingerprint, jobContentFingerprint);
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
            Integer languageMatchPercent,
            Integer fieldRelevancePercent,
            Integer certificationMatchPercent,
            Integer locationMatchPercent,
            List<String> missingRequiredSkills,
            List<String> missingPreferredSkills
    ) {}

    public MatchScoresResult getMatchScores(String email, List<Job> jobs, String language) {
        CVAnalysis analysis = cvAnalysisRepository.findByUserEmail(email).orElse(null);

        if (analysis == null) {
            return new MatchScoresResult(false, List.of());
        }

        if (jobs == null || jobs.isEmpty()) {
            return new MatchScoresResult(true, List.of());
        }

        Map<Long, JobMatchScore> cachedByJobId = ensureCoreScores(email, jobs, language, analysis);

        List<Map<String, Object>> matches = new ArrayList<>();
        for (Job job : jobs) {
            JobMatchScore score = cachedByJobId.get(job.getId());
            if (score == null) {
                continue;
            }
            matches.add(scoreToPayload(score));
        }

        return new MatchScoresResult(true, matches);
    }

    // Shared by getMatchScores (the synchronous list) and computeMatchScoresStreaming (progressive
    // per-job SSE payloads) - one place that defines what a "match" looks like over the wire.
    private Map<String, Object> scoreToPayload(JobMatchScore score) {
        Map<String, Object> match = new LinkedHashMap<>();
        match.put("jobId", score.getJobId());
        // fieldRelated is left as-is (including null) rather than coerced to true - null is
        // the honest "we couldn't compute this, please retry" sentinel, and collapsing it into
        // true would misrepresent a failed computation as a real, AI-decided verdict.
        match.put("fieldRelated", score.getFieldRelated());
        match.put("matchPercent", score.getMatchPercent());
        match.put("matchReason", score.getMatchReason());
        match.put("matchedSkills", splitSkillsString(score.getMatchedSkills()));
        match.put("missingSkills", splitSkillsString(score.getMissingSkills()));
        match.put("missingRequiredSkills", splitSkillsString(score.getMissingRequiredSkills()));
        match.put("missingPreferredSkills", splitSkillsString(score.getMissingPreferredSkills()));
        match.put("fieldRelevancePercent", score.getFieldRelevancePercent());
        match.put("skillsMatchPercent", score.getSkillsMatchPercent());
        match.put("experienceMatchPercent", score.getExperienceMatchPercent());
        match.put("educationMatchPercent", score.getEducationMatchPercent());
        match.put("certificationMatchPercent", score.getCertificationMatchPercent());
        match.put("locationMatchPercent", score.getLocationMatchPercent());
        return match;
    }

    private Map<String, Object> errorPayload(long jobId) {
        Map<String, Object> match = new LinkedHashMap<>();
        match.put("jobId", jobId);
        match.put("fieldRelated", null);
        match.put("matchPercent", null);
        match.put("matchReason", "We couldn't calculate a match for this job right now. Please try again.");
        match.put("matchedSkills", List.of());
        match.put("missingSkills", List.of());
        match.put("missingRequiredSkills", List.of());
        match.put("missingPreferredSkills", List.of());
        match.put("fieldRelevancePercent", null);
        match.put("skillsMatchPercent", null);
        match.put("experienceMatchPercent", null);
        match.put("educationMatchPercent", null);
        match.put("certificationMatchPercent", null);
        match.put("locationMatchPercent", null);
        return match;
    }

    // True as soon as a candidate has ANY persisted CVAnalysis - lets a streaming caller (see
    // ExternalJobController) emit its "no-analysis" event up front without needing to duplicate
    // the lookup ensureCoreScores/computeMatchScoresStreaming already do internally.
    public boolean hasAnalysis(String email) {
        return cvAnalysisRepository.findByUserEmail(email).isPresent();
    }

    // Transport-agnostic progressive scoring entry point (nothing SSE/HTTP-specific in this
    // signature - a BiConsumer/Runnable pair), built generically so it's reusable for internal
    // jobs later even though only the external-jobs streaming endpoint calls it today. Emits
    // exactly one terminal onJobResult call per requested job - instantly for cache hits and
    // pre-filter skips, asynchronously as each AI-scored job's own call finishes - so a caller
    // (see ExternalJobController's SSE endpoint) can never be left waiting on a job that silently
    // never resolves; onComplete fires once every job has been accounted for. jobEmbeddings may
    // be empty (internal jobs, or an unbackfilled external job) - the pre-filter simply never
    // fires for those, exactly like today's AI-only behavior.
    public void computeMatchScoresStreaming(
            String email, List<Job> jobs, String language, Map<Long, float[]> jobEmbeddings,
            BiConsumer<Long, Map<String, Object>> onJobResult, Runnable onComplete) {

        CVAnalysis analysis = cvAnalysisRepository.findByUserEmail(email).orElse(null);
        if (analysis == null || jobs == null || jobs.isEmpty()) {
            onComplete.run();
            return;
        }

        String cvFingerprint = fingerprintCv(analysis);

        List<Long> jobIds = jobs.stream().map(Job::getId).toList();
        Map<Long, JobMatchScore> cachedByJobId = new HashMap<>();
        for (JobMatchScore score : jobMatchScoreRepository.findByCandidateEmailAndJobIdIn(email, jobIds)) {
            cachedByJobId.put(score.getJobId(), score);
        }

        Map<Long, String> jobFingerprints = new HashMap<>();
        Map<Long, String> jobContentFingerprints = new HashMap<>();
        List<Job> jobsNeedingComputation = new ArrayList<>();

        for (Job job : jobs) {
            String jobFingerprint = fingerprintJob(job);
            jobFingerprints.put(job.getId(), jobFingerprint);
            jobContentFingerprints.put(job.getId(), buildJobContentFingerprint(job, jobFingerprint));

            JobMatchScore cached = cachedByJobId.get(job.getId());
            boolean isStale = cached == null
                    || !cvFingerprint.equals(cached.getCvFingerprint())
                    || !jobFingerprint.equals(cached.getJobFingerprint());

            if (isStale) {
                jobsNeedingComputation.add(job);
            } else {
                onJobResult.accept(job.getId(), scoreToPayload(cached));
            }
        }

        if (jobsNeedingComputation.isEmpty()) {
            onComplete.run();
            return;
        }

        // Only bother computing/loading the candidate's own profile vector if the caller
        // actually supplied any job vectors to compare it against - avoids a wasted embeddings
        // call for a caller that never wires up jobEmbeddings at all (e.g. internal jobs today).
        boolean anyJobEmbeddings = jobEmbeddings != null && !jobEmbeddings.isEmpty();
        float[] profileVector = anyJobEmbeddings
                ? embeddingService.ensureProfileEmbedding(analysis, cvAnalysisRepository)
                : null;

        List<Job> jobsSentToAi = new ArrayList<>();
        Map<Long, Float> similarityByJobId = new HashMap<>();

        for (Job job : jobsNeedingComputation) {
            float[] jobVector = jobEmbeddings == null ? null : jobEmbeddings.get(job.getId());
            if (profileVector != null && jobVector != null) {
                similarityByJobId.put(job.getId(), EmbeddingService.cosineSimilarity(profileVector, jobVector));
            }

            if (shouldSkipAiViaPrefilter(job, profileVector, jobVector)) {
                JobMatchScore score = cachedByJobId.getOrDefault(job.getId(), new JobMatchScore());
                applyPrefilteredUnrelatedVerdict(score, job, analysis, email, job.getId(),
                        cvFingerprint, jobFingerprints.get(job.getId()), jobContentFingerprints.get(job.getId()));
                score = jobMatchScoreRepositorySafeSave(score, email, job.getId());
                onJobResult.accept(job.getId(), scoreToPayload(score));
            } else {
                jobsSentToAi.add(job);
            }
        }

        if (jobsSentToAi.isEmpty()) {
            onComplete.run();
            return;
        }

        Semaphore limiter = new Semaphore(MAX_CONCURRENT_MATCH_CALLS);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        AtomicInteger remaining = new AtomicInteger(jobsSentToAi.size());

        for (Job job : jobsSentToAi) {
            String inFlightKey = email + "|" + job.getId();

            CompletableFuture<JobMatchScore> future = inFlightComputations.computeIfAbsent(inFlightKey, k -> {
                CompletableFuture<JobMatchScore> f = CompletableFuture.supplyAsync(() -> {
                    JsonNode matches = computeChunkWithRetry(analysis, List.of(job), jobFingerprints, language, limiter);
                    JsonNode match = firstMatchForJob(matches, job.getId());
                    if (match == null) {
                        return null;
                    }

                    ParsedMatch parsed = parseMatch(match);
                    JobMatchScore score = cachedByJobId.getOrDefault(job.getId(), new JobMatchScore());
                    applyParsedMatchToScore(score, parsed, job, analysis, email, job.getId(),
                            cvFingerprint, jobFingerprints.get(job.getId()), jobContentFingerprints.get(job.getId()));
                    score = jobMatchScoreRepositorySafeSave(score, email, job.getId());
                    maybeNotifyHighMatch(email, job, score);
                    return score;
                }, executor);

                f.whenComplete((result, ex) -> inFlightComputations.remove(k, f));
                return f;
            });

            future.whenComplete((score, ex) -> {
                Float similarity = similarityByJobId.get(job.getId());
                if (similarity != null) {
                    // Shadow-calibration signal: logged for every job that actually reached the
                    // AI (whether because the prefilter is disabled, or scored above threshold),
                    // so the threshold in application.properties can be validated against real
                    // (similarity, AI verdict) pairs before - and after - it's trusted to skip
                    // AI calls outright. See matching.embedding.prefilter.* config.
                    Boolean fieldRelated = score != null ? score.getFieldRelated() : null;
                    log.info("prefilter-shadow email={} jobId={} similarity={} fieldRelated={}",
                            email, job.getId(), similarity, fieldRelated);
                }

                Map<String, Object> payload = (score != null) ? scoreToPayload(score) : errorPayload(job.getId());
                onJobResult.accept(job.getId(), payload);

                if (remaining.decrementAndGet() == 0) {
                    executor.shutdown();
                    onComplete.run();
                }
            });
        }
    }

    private JsonNode firstMatchForJob(JsonNode matches, long jobId) {
        if (matches == null) {
            return null;
        }
        for (JsonNode match : matches) {
            if (match.hasNonNull("jobId") && match.path("jobId").asLong() == jobId) {
                return match;
            }
        }
        return null;
    }

    // The single source of truth for matchPercent/fieldRelated/matchReason/matchedSkills/
    // missingSkills (and every weighted component) for a candidate+job, shared by both
    // getMatchScores (the job list/card view) and getMatchDetail (the job details page).
    private Map<Long, JobMatchScore> ensureCoreScores(String email, List<Job> jobs, String language, CVAnalysis analysis) {
        String cvFingerprint = fingerprintCv(analysis);

        List<Long> jobIds = jobs.stream().map(Job::getId).toList();
        Map<Long, JobMatchScore> cachedByJobId = new HashMap<>();
        for (JobMatchScore score : jobMatchScoreRepository.findByCandidateEmailAndJobIdIn(email, jobIds)) {
            cachedByJobId.put(score.getJobId(), score);
        }

        Map<Long, Job> jobById = new HashMap<>();
        for (Job job : jobs) {
            jobById.put(job.getId(), job);
        }

        Map<Long, String> jobFingerprints = new HashMap<>();
        Map<Long, String> jobContentFingerprints = new HashMap<>();
        List<Job> jobsNeedingComputation = new ArrayList<>();

        for (Job job : jobs) {
            String jobFingerprint = fingerprintJob(job);
            jobFingerprints.put(job.getId(), jobFingerprint);
            jobContentFingerprints.put(job.getId(), buildJobContentFingerprint(job, jobFingerprint));

            JobMatchScore cached = cachedByJobId.get(job.getId());
            boolean isStale = cached == null
                    || !cvFingerprint.equals(cached.getCvFingerprint())
                    || !jobFingerprint.equals(cached.getJobFingerprint());

            if (isStale) {
                jobsNeedingComputation.add(job);
            }
        }

        if (!jobsNeedingComputation.isEmpty()) {
            List<JsonNode> matchesJson = computeJobMatchesInParallel(
                    analysis, jobsNeedingComputation, language, jobContentFingerprints);
            Set<Long> succeededJobIds = new HashSet<>();

            for (JsonNode match : matchesJson) {
                if (!match.hasNonNull("jobId")) {
                    continue;
                }

                long jobId = match.path("jobId").asLong();
                Job job = jobById.get(jobId);
                if (job == null) {
                    continue;
                }

                ParsedMatch parsed = parseMatch(match);

                try {
                    JobMatchScore score = cachedByJobId.getOrDefault(jobId, new JobMatchScore());
                    applyParsedMatchToScore(score, parsed, job, analysis, email, jobId,
                            cvFingerprint, jobFingerprints.get(jobId), jobContentFingerprints.get(jobId));

                    score = jobMatchScoreRepositorySafeSave(score, email, jobId);
                    cachedByJobId.put(jobId, score);
                    succeededJobIds.add(jobId);

                    maybeNotifyHighMatch(email, job, score);
                } catch (Exception e) {
                    // A DB save or notification hiccup on ONE job must not take out the rest of
                    // the batch - only THIS job falls through to the sentinel below.
                    log.error("Failed to save match score for candidate {} / job {}", email, jobId, e);
                }
            }

            // Any job we asked the AI to score but got no validly-accepted entry back for - even
            // after the validation-guided retry in computeChunkWithRetry - genuinely failed to
            // compute. Surface that honestly instead of caching a guessed or malformed verdict.
            // This is deliberately never saved to the repository, so the very next request
            // retries the real computation instead of getting stuck on a cached failure.
            for (Job job : jobsNeedingComputation) {
                if (!succeededJobIds.contains(job.getId())) {
                    JobMatchScore errorScore = new JobMatchScore();
                    errorScore.setCandidateEmail(email);
                    errorScore.setJobId(job.getId());
                    errorScore.setFieldRelated(null);
                    errorScore.setMatchPercent(null);
                    errorScore.setMatchReason("We couldn't calculate a match for this job right now. Please try again.");
                    errorScore.setMatchedSkills("");
                    errorScore.setMissingSkills("");
                    cachedByJobId.put(job.getId(), errorScore);
                }
            }
        }

        return cachedByJobId;
    }

    private void maybeNotifyHighMatch(String email, Job job, JobMatchScore score) {
        Integer matchPercent = score.getMatchPercent();
        if (matchPercent != null && matchPercent >= HIGH_MATCH_NOTIFICATION_THRESHOLD) {
            notificationService.createNotificationOnce(
                    email,
                    "Great Job Match Found",
                    "You're a " + matchPercent + "% match for " + job.getTitle() + ".",
                    "JOB_MATCH_HIGH",
                    job.getId()
            );
        }
    }

    // Two overlapping requests for the same candidate+job can both hit a cache MISS and both
    // attempt to INSERT a new JobMatchScore row for the same (candidateEmail, jobId) unique
    // constraint - the in-memory inFlightComputations guard prevents this within one JVM, but
    // this is the cross-process backstop (also relevant even today: two browser tabs hitting the
    // existing synchronous endpoint at once). Someone else's concurrently-saved row for the exact
    // same CV+job+schema is equally valid - there's no "which one is right" question - so just
    // re-read and use theirs instead of surfacing an error.
    private JobMatchScore jobMatchScoreRepositorySafeSave(JobMatchScore score, String email, long jobId) {
        try {
            return jobMatchScoreRepository.save(score);
        } catch (DataIntegrityViolationException e) {
            return jobMatchScoreRepository.findByCandidateEmailAndJobId(email, jobId).orElseThrow(() -> e);
        }
    }

    // This is the ONE place a percentage is ever produced. The AI's job (parsed above) is
    // strictly classification: which of a handful of fixed labels applies to this candidate+job
    // pair. Every one of those labels is turned into a number here, via MatchScoreCalculator's
    // rule tables/formulas - never by trusting a number the AI wrote itself. That is what makes
    // "same CV + same job -> same score" hold even across AI response variance, since the only
    // way the score changes is if the AI's CLASSIFICATION changes, and identical classifications
    // always produce identical numbers.
    private void applyParsedMatchToScore(
            JobMatchScore score, ParsedMatch parsed, Job job, CVAnalysis analysis, String email, long jobId,
            String cvFingerprint, String jobFingerprint, String jobContentFingerprint) {

        // Backend override: a general/vocational role must never come back "unrelated" for the
        // candidate's specialized background - see GENERAL_VOCATIONAL_ROLE_KEYWORDS. Only
        // overrides an "unrelated" verdict; a candidate whose own field genuinely IS this role
        // (e.g. a cleaner CV against a cleaning job) keeps whatever closeness the AI gave them.
        String effectiveCloseness = parsed.fieldRelationCloseness();
        if ("unrelated".equalsIgnoreCase(effectiveCloseness) && isGeneralVocationalRole(job.getTitle())) {
            effectiveCloseness = "general_vocational_role";
        }

        boolean fieldRelated = !"unrelated".equalsIgnoreCase(effectiveCloseness);

        score.setCandidateEmail(email);
        score.setJobId(jobId);
        score.setFieldRelated(fieldRelated);
        score.setMatchReason(effectiveCloseness.equals(parsed.fieldRelationCloseness())
                ? parsed.matchReason()
                : "This role doesn't require specialized experience in your field, so your background and"
                        + " work history are a reasonable fit.");
        score.setCvFingerprint(cvFingerprint);
        score.setJobFingerprint(jobFingerprint);
        score.setJobContentFingerprint(jobContentFingerprint);
        // The core score just changed, so any previously-generated detail explanation
        // (whyGoodMatch/recommendation/etc.) was written for the OLD result and is now stale -
        // clearing recommendation is what getMatchDetail checks to know it needs to regenerate.
        score.setRecommendation(null);

        if (!fieldRelated) {
            score.setMatchPercent(null);
            score.setMatchedSkills("");
            score.setMissingSkills("");
            score.setMissingRequiredSkills("");
            score.setMissingPreferredSkills("");
            score.setFieldRelevancePercent(null);
            score.setSkillsMatchPercent(null);
            score.setExperienceMatchPercent(null);
            score.setEducationMatchPercent(null);
            score.setCertificationMatchPercent(null);
            score.setLocationMatchPercent(null);
            return;
        }

        List<String> allMatched = concat(parsed.matchedMandatorySkills(), parsed.matchedPreferredSkills());
        List<String> allMissing = concat(parsed.missingMandatorySkills(), parsed.missingPreferredSkills());

        Integer skillsScore = MatchScoreCalculator.computeSkillsScore(
                parsed.matchedMandatorySkills().size(), parsed.missingMandatorySkills().size(),
                parsed.matchedPreferredSkills().size(), parsed.missingPreferredSkills().size());

        // Backend override: never score education for a general/vocational role even if the AI
        // mistakenly stated a requirement for one - see isGeneralVocationalRole.
        boolean isVocationalRole = isGeneralVocationalRole(job.getTitle());
        String requiredEducationLevel = isVocationalRole ? null : parsed.requiredEducationLevel();

        boolean sameSpecificRole = "same_role".equals(effectiveCloseness) || "same_specialization".equals(effectiveCloseness);

        Integer fieldRelevanceScore = MatchScoreCalculator.scoreFieldRelevance(effectiveCloseness);
        // Backend override: never score experience for a general/vocational role either - the
        // candidate's seniority was earned in a field this job doesn't require, so it isn't real
        // evidence of fit for a role "almost any reliable adult can do." Without this, a senior
        // candidate's blanket experience level trivially clears any entry-level requirement,
        // which combined with the old fieldRelevance=85 was how a doctor's CV scored 83-85%
        // against a Cashier posting.
        Integer experienceScore = (isVocationalRole || parsed.requiredExperienceLevel() == null) ? null
                : MatchScoreCalculator.scoreExperience(
                        analysis.getExperienceLevel(), parsed.requiredExperienceLevel(), sameSpecificRole);
        Integer educationScore = requiredEducationLevel == null ? null
                : MatchScoreCalculator.scoreEducation(analysis.getEducationEvidence(), requiredEducationLevel);
        Integer certificationScore = parsed.requiredCertificationLevel() == null ? null
                : MatchScoreCalculator.scoreCertification(
                        analysis.getCertificationsEvidence(), analysis.getLicensesEvidence(),
                        parsed.requiredCertificationLevel(), sameSpecificRole);
        Integer locationScore = MatchScoreCalculator.scoreLocation(job.getType(), job.getLocation());

        WeightedResult weighted = MatchScoreCalculator.compute(List.of(
                new Component(ComponentKey.FIELD_RELEVANCE, fieldRelevanceScore),
                new Component(ComponentKey.REQUIRED_SKILLS, skillsScore),
                new Component(ComponentKey.EXPERIENCE, experienceScore),
                new Component(ComponentKey.EDUCATION, educationScore),
                new Component(ComponentKey.CERTIFICATION, certificationScore),
                new Component(ComponentKey.LOCATION, locationScore)
        ));

        score.setMatchPercent(weighted.overallPercent());
        score.setMatchedSkills(String.join("|", allMatched));
        score.setMissingSkills(String.join("|", allMissing));
        score.setMissingRequiredSkills(String.join("|", parsed.missingMandatorySkills()));
        score.setMissingPreferredSkills(String.join("|", parsed.missingPreferredSkills()));
        score.setFieldRelevancePercent(weighted.componentPercents().get(ComponentKey.FIELD_RELEVANCE));
        score.setSkillsMatchPercent(weighted.componentPercents().get(ComponentKey.REQUIRED_SKILLS));
        score.setExperienceMatchPercent(weighted.componentPercents().get(ComponentKey.EXPERIENCE));
        score.setEducationMatchPercent(weighted.componentPercents().get(ComponentKey.EDUCATION));
        score.setCertificationMatchPercent(weighted.componentPercents().get(ComponentKey.CERTIFICATION));
        score.setLocationMatchPercent(weighted.componentPercents().get(ComponentKey.LOCATION));
    }

    public MatchDetailResult getMatchDetail(String email, Job job, String language) {
        CVAnalysis analysis = cvAnalysisRepository.findByUserEmail(email).orElse(null);

        if (analysis == null) {
            return new MatchDetailResult(false, job.getId(), null, null, List.of(), List.of(), List.of(), List.of(), List.of(),
                    null, null, null, null, null, null, null, null, null, null, List.of(), List.of());
        }

        // Establishes (or reuses) the exact same core score the job card/list already shows -
        // routed through the identical batch-scoring path getMatchScores uses, so this is
        // never a second, independently-decided number for the same job.
        JobMatchScore core = ensureCoreScores(email, List.of(job), language, analysis).get(job.getId());

        if (core == null || core.getFieldRelated() == null) {
            // Core computation failed for this job even after its own validation-guided retry -
            // fieldRelated=null is ensureCoreScores' honest sentinel, never a real AI verdict.
            String reason = core != null ? core.getMatchReason() : null;
            return new MatchDetailResult(true, job.getId(), null,
                    reason != null && !reason.isBlank() ? reason
                            : "We couldn't compute your match for this job right now. Please try again shortly.",
                    List.of(), List.of(), List.of(), List.of(), List.of(),
                    null, null, null, null, null, null, null, null, null, null, List.of(), List.of());
        }

        boolean fieldRelated = core.getFieldRelated();
        List<String> matchedSkills = splitSkillsString(core.getMatchedSkills());
        List<String> missingSkills = splitSkillsString(core.getMissingSkills());
        List<String> missingRequiredSkills = splitSkillsString(core.getMissingRequiredSkills());
        List<String> missingPreferredSkills = splitSkillsString(core.getMissingPreferredSkills());

        if (!fieldRelated) {
            // Already decided by the core computation - no LLM call needed, and no risk of a
            // detail-only prompt second-guessing a "not a fit" verdict the list already showed.
            return new MatchDetailResult(true, job.getId(), null, core.getMatchReason(),
                    List.of(), List.of(), List.of(), List.of(), List.of(),
                    "A meaningful evaluation isn't possible for this job given the field mismatch.",
                    false, false, null, null, null, null, null, null, null, List.of(), List.of());
        }

        // recommendation is the sentinel for "detail explanation generated for the CURRENT
        // core score": ensureCoreScores clears it whenever the core score changes, so a blank
        // value here means either this job's detail has never been generated, or the score it
        // was generated against is now stale - either way, regenerate against the current score.
        boolean detailStale = core.getRecommendation() == null || core.getRecommendation().isBlank();

        if (detailStale) {
            String result = openAICVAnalysisService.computeJobMatchDetail(
                    analysis, job, language, core.getMatchPercent(), matchedSkills, missingSkills);
            JsonNode json = readDetailObject(result);

            if (json != null) {
                core.setLanguageMatchPercent(json.has("languageMatchPercent") ? json.path("languageMatchPercent").asInt() : null);
                core.setWhyGoodMatch(joinSkillsArray(json.path("whyGoodMatch")));
                core.setWhyNotPerfectMatch(joinSkillsArray(json.path("whyNotPerfectMatch")));
                core.setImprovementSuggestions(joinSkillsArray(json.path("improvementSuggestions")));
                // recommendation must end up non-blank on success so the check above can tell a
                // completed computation apart from one that never ran / previously failed.
                String recommendation = json.path("recommendation").asText("");
                core.setRecommendation(recommendation.isBlank() ? "No specific recommendation available." : recommendation);
                core.setShouldApply(json.path("shouldApply").asBoolean(true));

                core = jobMatchScoreRepository.save(core);
            }
            // A null/unparsable response leaves core's detail fields as whatever they were
            // (blank on first attempt, or the last successful generation if this was a retry) -
            // the core score itself is still shown correctly either way.
        }

        return new MatchDetailResult(
                true,
                core.getJobId(),
                core.getMatchPercent(),
                core.getMatchReason(),
                matchedSkills,
                missingSkills,
                splitSkillsString(core.getWhyGoodMatch()),
                splitSkillsString(core.getWhyNotPerfectMatch()),
                splitSkillsString(core.getImprovementSuggestions()),
                core.getRecommendation(),
                core.getShouldApply(),
                fieldRelated,
                core.getSkillsMatchPercent(),
                core.getExperienceMatchPercent(),
                core.getEducationMatchPercent(),
                core.getLanguageMatchPercent(),
                core.getFieldRelevancePercent(),
                core.getCertificationMatchPercent(),
                core.getLocationMatchPercent(),
                missingRequiredSkills,
                missingPreferredSkills
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

    // A single OpenAI call scoring a large stale batch is one long blocking round trip, since
    // latency grows with how many jobs are packed into the prompt/completion. Splitting into
    // chunks and scoring them concurrently on virtual threads (I/O-bound - just waiting on HTTP
    // responses) cuts wall-clock time roughly in proportion to how many chunks run at once,
    // instead of paying for the whole batch serially.
    //
    // Deliberately 1 (was 10, then 5): asking the model to track multiple numeric jobIds and
    // keep each one's verdict correctly attached in one response is exactly the kind of
    // bookkeeping large batches make LLMs more likely to fumble - a real, observed failure was
    // one job in a mixed batch getting a different job's field-mismatch verdict. One job per
    // request makes that entire failure mode structurally impossible, not just less likely; the
    // jobFingerprint/jobTitle cross-check in validateMatch is the remaining defense against the
    // model still garbling its own single answer.
    private static final int MATCH_CHUNK_SIZE = 1;

    // Firing every chunk at once made concurrent OpenAI calls far more likely to trip a
    // rate limit or transient error than the old single-call-per-request flow ever was -
    // a failed chunk silently dropped every job in it (each one showing "no score" to the
    // candidate) with no retry. Cap how many chunk calls are in flight at once, and retry
    // a chunk once - with the specific validation errors from the first attempt - before
    // giving up on it for this request.
    private static final int MAX_CONCURRENT_MATCH_CALLS = 5;

    private List<JsonNode> computeJobMatchesInParallel(
            CVAnalysis analysis, List<Job> jobs, String language, Map<Long, String> jobFingerprints) {
        List<List<Job>> chunks = new ArrayList<>();
        for (int i = 0; i < jobs.size(); i += MATCH_CHUNK_SIZE) {
            chunks.add(jobs.subList(i, Math.min(i + MATCH_CHUNK_SIZE, jobs.size())));
        }

        Semaphore concurrencyLimiter = new Semaphore(MAX_CONCURRENT_MATCH_CALLS);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<JsonNode>> futures = chunks.stream()
                    .map(chunk -> CompletableFuture.supplyAsync(
                            () -> computeChunkWithRetry(analysis, chunk, jobFingerprints, language, concurrencyLimiter),
                            executor))
                    .toList();

            List<JsonNode> allMatches = new ArrayList<>();
            for (CompletableFuture<JsonNode> future : futures) {
                future.join().forEach(allMatches::add);
            }
            return allMatches;
        }
    }

    private record ValidatedBatch(List<JsonNode> validMatches, boolean allValid, Map<Long, List<String>> errorsByJobId) {}

    private JsonNode computeChunkWithRetry(
            CVAnalysis analysis, List<Job> chunk, Map<Long, String> jobFingerprints,
            String language, Semaphore concurrencyLimiter) {
        try {
            concurrencyLimiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return objectMapper.createArrayNode();
        }

        try {
            Map<Long, Job> jobById = chunk.stream().collect(Collectors.toMap(Job::getId, job -> job));

            JsonNode firstAttempt = readMatchesArray(
                    openAICVAnalysisService.computeJobMatches(analysis, chunk, jobFingerprints, language, null));
            ValidatedBatch firstResult = validateBatch(firstAttempt, jobById, jobFingerprints, analysis);

            if (firstResult.allValid()) {
                return toArrayNode(firstResult.validMatches());
            }

            String feedback = buildRetryFeedback(chunk, firstResult);
            JsonNode retryAttempt = readMatchesArray(
                    openAICVAnalysisService.computeJobMatches(analysis, chunk, jobFingerprints, language, feedback));
            ValidatedBatch retryResult = validateBatch(retryAttempt, jobById, jobFingerprints, analysis);

            // Keep whichever attempt validly covers more jobs - a fully-valid retry always wins,
            // and a still-partial retry is only worth using if it's a strict improvement over
            // the first attempt. Anything still uncovered falls through to ensureCoreScores'
            // honest "couldn't compute, please retry" sentinel rather than a guessed verdict.
            List<JsonNode> best = retryResult.validMatches().size() >= firstResult.validMatches().size()
                    ? retryResult.validMatches() : firstResult.validMatches();
            return toArrayNode(best);
        } finally {
            concurrencyLimiter.release();
        }
    }

    private JsonNode toArrayNode(List<JsonNode> nodes) {
        var array = objectMapper.createArrayNode();
        nodes.forEach(array::add);
        return array;
    }

    private ValidatedBatch validateBatch(
            JsonNode matches, Map<Long, Job> jobById, Map<Long, String> jobFingerprints, CVAnalysis analysis) {
        List<JsonNode> valid = new ArrayList<>();
        Map<Long, List<String>> errorsByJobId = new LinkedHashMap<>();
        Set<Long> covered = new HashSet<>();

        for (JsonNode match : matches) {
            if (!match.hasNonNull("jobId")) {
                continue;
            }

            long jobId = match.path("jobId").asLong();
            Job job = jobById.get(jobId);
            if (job == null) {
                // Not one of the jobs we asked about this round - ignore rather than trust it.
                continue;
            }

            ParsedMatch parsed = parseMatch(match);
            List<String> errors = validateMatch(parsed, job, jobFingerprints.get(jobId), analysis);

            if (errors.isEmpty()) {
                valid.add(match);
                covered.add(jobId);
            } else {
                errorsByJobId.put(jobId, errors);
            }
        }

        boolean allValid = covered.containsAll(jobById.keySet());
        return new ValidatedBatch(valid, allValid, errorsByJobId);
    }

    private String buildRetryFeedback(List<Job> chunk, ValidatedBatch result) {
        StringBuilder sb = new StringBuilder();
        for (Job job : chunk) {
            List<String> errors = result.errorsByJobId().get(job.getId());
            boolean covered = result.validMatches().stream()
                    .anyMatch(m -> m.path("jobId").asLong() == job.getId());

            if (errors != null && !errors.isEmpty()) {
                sb.append("- jobId ").append(job.getId()).append(" ('").append(nullToEmpty(job.getTitle()))
                        .append("'): ").append(String.join("; ", errors)).append("\n");
            } else if (!covered) {
                sb.append("- jobId ").append(job.getId()).append(" ('").append(nullToEmpty(job.getTitle()))
                        .append("'): missing from your response - you must include exactly one entry per job listed.\n");
            }
        }
        return sb.toString();
    }

    // ---- Response parsing ----

    // AI output is strictly classification - a handful of fixed-vocabulary labels - never a raw
    // score. MatchScoreCalculator (see applyParsedMatchToScore) is the only thing that turns
    // these labels into numbers.
    private record ParsedMatch(
            long jobId,
            String jobTitle,
            String jobFingerprint,
            String fieldRelationCloseness,
            String matchReason,
            List<String> matchedMandatorySkills,
            List<String> missingMandatorySkills,
            List<String> matchedPreferredSkills,
            List<String> missingPreferredSkills,
            String requiredExperienceLevel,
            String requiredEducationLevel,
            String requiredCertificationLevel
    ) {}

    private static final Set<String> VALID_CLOSENESS =
            Set.of("same_role", "same_specialization", "same_broad_field", "unrelated");
    private static final Set<String> VALID_EXPERIENCE_LEVELS = Set.of("entry", "mid", "senior");
    private static final Set<String> VALID_EDUCATION_LEVELS = Set.of("any_degree", "relevant_degree");
    private static final Set<String> VALID_CERTIFICATION_LEVELS = Set.of("general_cert", "specific_license");

    private ParsedMatch parseMatch(JsonNode match) {
        // An unrecognized/missing closeness value defaults to "unrelated" (the safe, honest
        // failure mode) rather than silently treating it as related - validateMatch below still
        // rejects anything outside VALID_CLOSENESS outright, so this default only matters for
        // how a malformed response reads before validation catches it.
        String closeness = match.path("fieldRelationCloseness").asText("unrelated").trim().toLowerCase(Locale.ROOT);
        boolean fieldRelated = !"unrelated".equals(closeness);

        return new ParsedMatch(
                match.path("jobId").asLong(),
                match.path("jobTitle").asText(""),
                match.path("jobFingerprint").asText(""),
                closeness,
                match.path("matchReason").asText(""),
                fieldRelated ? toStringList(match.path("matchedMandatorySkills")) : List.of(),
                fieldRelated ? toStringList(match.path("missingMandatorySkills")) : List.of(),
                fieldRelated ? toStringList(match.path("matchedPreferredSkills")) : List.of(),
                fieldRelated ? toStringList(match.path("missingPreferredSkills")) : List.of(),
                normalizeEnum(match.path("requiredExperienceLevel")),
                normalizeEnum(match.path("requiredEducationLevel")),
                normalizeEnum(match.path("requiredCertificationLevel"))
        );
    }

    private String normalizeEnum(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        String text = node.asText("").trim().toLowerCase(Locale.ROOT);
        return text.isBlank() || "null".equals(text) ? null : text;
    }

    private List<String> toStringList(JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode node : arrayNode) {
            String text = node.asText("").trim();
            if (!text.isBlank()) {
                values.add(text);
            }
        }
        return values;
    }

    private List<String> concat(List<String> a, List<String> b) {
        List<String> combined = new ArrayList<>(a);
        combined.addAll(b);
        return combined;
    }

    // ---- Response validation (requirement: verify the AI's claims against source data before
    // trusting them, retry once with feedback, and never save an unvalidated result) ----

    private List<String> validateMatch(ParsedMatch parsed, Job job, String expectedFingerprint, CVAnalysis analysis) {
        List<String> errors = new ArrayList<>();

        if (expectedFingerprint != null && !expectedFingerprint.isBlank()
                && !expectedFingerprint.equals(parsed.jobFingerprint())) {
            errors.add("jobFingerprint was not echoed back unchanged for jobId " + parsed.jobId()
                    + " - this looks like a verdict meant for a different job.");
        }

        if (!titlesReasonablyMatch(job.getTitle(), parsed.jobTitle())) {
            errors.add("jobTitle ('" + parsed.jobTitle() + "') does not reasonably match the requested job's title ('"
                    + job.getTitle() + "').");
        }

        if (!VALID_CLOSENESS.contains(parsed.fieldRelationCloseness())) {
            errors.add("fieldRelationCloseness must be one of same_role/same_specialization/same_broad_field/unrelated, got '"
                    + parsed.fieldRelationCloseness() + "'.");
        }

        boolean fieldRelated = !"unrelated".equals(parsed.fieldRelationCloseness());

        if (!fieldRelated && professionMatchesJobTitle(analysis, job.getTitle())) {
            errors.add("fieldRelationCloseness was 'unrelated', but the candidate's profession title closely "
                    + "matches this job's title - re-check whether this is genuinely a different professional "
                    + "field, not just an imperfect fit.");
        }

        if (fieldRelated) {
            // No numeric score to sanity-check here anymore - matchedSkills/missingSkills and
            // the required*Level classifications are the only claims the AI makes, and every one
            // of them is checked against real source text/a fixed vocabulary below. The actual
            // percentages are computed afterwards by MatchScoreCalculator from whatever survives
            // validation, so there is no "hallucinated high score" failure mode left to catch.
            String candidateBlob = candidateSkillsBlob(analysis);
            for (String skill : concat(parsed.matchedMandatorySkills(), parsed.matchedPreferredSkills())) {
                if (!evidencedIn(skill, candidateBlob)) {
                    errors.add("matched skill '" + skill + "' is not evidenced anywhere in the candidate's profile.");
                }
            }

            String jobBlob = jobRequirementsBlob(job);
            for (String skill : concat(parsed.missingMandatorySkills(), parsed.missingPreferredSkills())) {
                if (!evidencedIn(skill, jobBlob)) {
                    errors.add("missing skill '" + skill + "' does not appear anywhere in this job's posting text.");
                }
            }

            if (parsed.requiredExperienceLevel() != null && !VALID_EXPERIENCE_LEVELS.contains(parsed.requiredExperienceLevel())) {
                errors.add("requiredExperienceLevel must be one of entry/mid/senior or null, got '"
                        + parsed.requiredExperienceLevel() + "'.");
            }
            if (parsed.requiredEducationLevel() != null && !VALID_EDUCATION_LEVELS.contains(parsed.requiredEducationLevel())) {
                errors.add("requiredEducationLevel must be one of any_degree/relevant_degree or null, got '"
                        + parsed.requiredEducationLevel() + "'.");
            }
            if (parsed.requiredCertificationLevel() != null && !VALID_CERTIFICATION_LEVELS.contains(parsed.requiredCertificationLevel())) {
                errors.add("requiredCertificationLevel must be one of general_cert/specific_license or null, got '"
                        + parsed.requiredCertificationLevel() + "'.");
            }
        } else {
            if (parsed.matchReason() == null || parsed.matchReason().isBlank()) {
                errors.add("matchReason must explain the field mismatch (naming both the candidate's field and the job's field) when fieldRelationCloseness is 'unrelated'.");
            }
            if (!parsed.matchedMandatorySkills().isEmpty() || !parsed.matchedPreferredSkills().isEmpty()
                    || !parsed.missingMandatorySkills().isEmpty() || !parsed.missingPreferredSkills().isEmpty()) {
                errors.add("skill arrays must be empty when fieldRelationCloseness is 'unrelated'.");
            }
        }

        return errors;
    }

    private String candidateSkillsBlob(CVAnalysis analysis) {
        return String.join(" | ",
                nullToEmpty(analysis.getTechnicalSkills()),
                nullToEmpty(analysis.getSoftSkills()),
                nullToEmpty(analysis.getSkills()),
                nullToEmpty(analysis.getSummary()),
                nullToEmpty(analysis.getStrengths())
        ).toLowerCase(Locale.ROOT);
    }

    private String jobRequirementsBlob(Job job) {
        return String.join(" | ",
                nullToEmpty(job.getSkills()),
                nullToEmpty(job.getRequirements()),
                nullToEmpty(job.getDescription())
        ).toLowerCase(Locale.ROOT);
    }

    // Lenient on purpose: checks the whole phrase first, then falls back to any single
    // significant word overlapping - this is a sanity check that the claim has SOME basis in
    // the source text, not a strict phrase-equality check (which would reject legitimate
    // equivalences like the AI matching "JS" against a job's "JavaScript").
    private boolean evidencedIn(String needle, String haystackBlob) {
        if (needle == null || needle.isBlank()) {
            return true;
        }
        String normalizedNeedle = needle.toLowerCase(Locale.ROOT).trim();
        if (haystackBlob.contains(normalizedNeedle)) {
            return true;
        }
        for (String word : normalizedNeedle.split("[^a-z0-9\\u0590-\\u05FF\\u0600-\\u06FF]+")) {
            if (word.length() >= 3 && haystackBlob.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private boolean professionMatchesJobTitle(CVAnalysis analysis, String jobTitle) {
        String professionTitle = analysis.getProfessionTitle();
        if (professionTitle == null || professionTitle.isBlank() || jobTitle == null || jobTitle.isBlank()) {
            return false;
        }
        String normalizedProfession = normalizeForTitleComparison(professionTitle);
        String normalizedJobTitle = normalizeForTitleComparison(jobTitle);
        if (normalizedProfession.isBlank() || normalizedJobTitle.isBlank()) {
            return false;
        }
        return normalizedProfession.contains(normalizedJobTitle) || normalizedJobTitle.contains(normalizedProfession);
    }

    // Lenient on purpose (substring either direction, after stripping punctuation/casing) - this
    // is a sanity check that the model attached this verdict to the right job, not a strict
    // equality check, so minor wording differences must not cause a false rejection. A missing
    // expected title (job has no title of its own) skips the check entirely rather than
    // rejecting an entry a check with nothing to compare against can't meaningfully evaluate.
    private boolean titlesReasonablyMatch(String expectedTitle, String returnedTitle) {
        if (expectedTitle == null || expectedTitle.isBlank()) {
            return true;
        }
        if (returnedTitle == null || returnedTitle.isBlank()) {
            return false;
        }

        String normalizedExpected = normalizeForTitleComparison(expectedTitle);
        String normalizedReturned = normalizeForTitleComparison(returnedTitle);

        if (normalizedExpected.isBlank() || normalizedReturned.isBlank()) {
            return true;
        }

        return normalizedExpected.contains(normalizedReturned) || normalizedReturned.contains(normalizedExpected);
    }

    private String normalizeForTitleComparison(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\u0590-\\u05FF\\u0600-\\u06FF]+", " ").trim();
    }

    // ---- Fingerprinting (requirement: same unchanged CV + job always produces the same cached
    // score, and is only recomputed when either actually changes) ----

    private String fingerprintCv(CVAnalysis analysis) {
        String cvTextHash = analysis.getCvTextHash();

        // Preferred: fingerprint the actual uploaded CV text. This stays stable across
        // repeated "Analyze" clicks on the same file, even though the AI-generated
        // summary/strengths wording can vary slightly between analysis runs.
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

    // Content hash used for cache staleness (unchanged CV+job never recomputes).
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

    // The fingerprint sent TO the AI and required to be echoed back unchanged: jobId + title +
    // company + normalized title + content hash, per the anti-batch-mixing requirement. Distinct
    // from fingerprintJob() above (which is content-only and used for cache staleness) because
    // this one also needs to catch a verdict attached to the right CONTENT but wrong jobId/title.
    private String buildJobContentFingerprint(Job job, String jobContentHash) {
        return HashUtil.sha256(String.join("|",
                String.valueOf(job.getId()),
                nullToEmpty(job.getTitle()),
                nullToEmpty(job.getCompanyName()),
                normalizeForTitleComparison(nullToEmpty(job.getTitle())),
                jobContentHash
        ));
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String nullToEmpty(Integer value) {
        return value == null ? "" : String.valueOf(value);
    }
}
