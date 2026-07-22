package com.jobmatchai.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.jobmatchai.backend.model.CVAnalysis;
import com.jobmatchai.backend.model.Job;
import com.jobmatchai.backend.model.JobMatchScore;
import com.jobmatchai.backend.repository.CVAnalysisRepository;
import com.jobmatchai.backend.repository.JobMatchScoreRepository;
import com.jobmatchai.backend.util.HashUtil;
import com.jobmatchai.backend.util.SkillClaimMatcher;
import com.jobmatchai.backend.util.VocationalRoleClassifier;
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

    // Bump whenever validateDetailClaims' filtering logic (or computeJobMatchDetail's prompt)
    // changes in a way that affects which bullets survive. getMatchDetail compares this against
    // each row's stored detailPromptVersion (see detailStale below) so every existing cached
    // whyGoodMatch/whyNotPerfectMatch narrative - including ones generated before a given filter
    // existed at all - gets regenerated through the CURRENT rules the next time its job is opened,
    // instead of being served forever from whatever guard (or lack of one) happened to be in place
    // when it was first written. Without this, fixing a filtering bug here only changes behavior
    // for brand-new rows; every already-cached explanation stays wrong until its unrelated core
    // score happens to change for some other reason (see applyParsedMatchToScore nulling
    // recommendation) - which is exactly how a bullet a *current* filter would catch can still be
    // shown for a job whose core score hasn't moved since it was generated under an older filter.
    private static final int DETAIL_PROMPT_VERSION = 2;

    @Autowired
    private CVAnalysisRepository cvAnalysisRepository;

    @Autowired
    private JobMatchScoreRepository jobMatchScoreRepository;

    @Autowired
    private com.jobmatchai.backend.repository.JobRepository jobRepository;

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

    @Autowired
    private MatchScoreQueueService matchScoreQueueService;

    @Autowired
    private MatchMetrics matchMetrics;

    // How long computeMatchScoresStreaming waits for the queue/worker to produce a result for one
    // job before giving up and surfacing the honest "couldn't compute, please retry" sentinel.
    // Generous on purpose: multiple jobs await concurrently (each awaitResult call returns
    // immediately with its own future), so this bounds per-JOB latency under a worst-case queue
    // backlog, not the whole stream's wall time.
    @Value("${matching.queue.await-timeout-ms:60000}")
    private long queueAwaitTimeoutMs;

    @Value("${matching.embedding.prefilter.enabled:true}")
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
    // v17: fixed a real production finding - a General Practitioner CV against a job literally
    // titled "doctor" (skills "doctor, medicine, family") got fieldRelationCloseness=same_role
    // (correct) but "doctor" was ALSO listed as a missing mandatory skill (self-contradictory -
    // the candidate's own profession IS "doctor"), and the separate, unvalidated detail-narrative
    // call (computeJobMatchDetail) invented an experience penalty for the candidate having MORE
    // than the stated 2-5 years (no max was stated), described the job's Tel Aviv location as a
    // missing "experience working in Tel Aviv", and criticized missing "leadership" and "public
    // health" experience the posting never asked for. Also added a deterministic, pre-AI
    // insufficient-job-data gate (see isInsufficientJobData) for postings too thin to compare
    // against at all (that same "doctor" job: description was just the word "doctor" again,
    // requirements a single line, three skill words including the title itself) - it had
    // previously still received a confident 81% and a full paragraph of fabricated detail from
    // essentially four words of real content. See validateMatch's self-contradictory-missing-
    // skill check and OpenAICVAnalysisService's computeJobMatchDetail prompt/validateDetailClaims
    // for the rest of this fix.
    // v18: added the profession-taxonomy compatibility gate (see ProfessionTaxonomy and
    // checkProfessionCompatibility) - the highest-priority check in the pipeline, run before
    // fieldRelationCloseness is ever asked about. Previously "same_broad_field" let a candidate
    // score a real, reduced-but-present match against ANY job sharing their broad industry label,
    // even a genuinely different profession (a Software Engineer CV against a QA Engineer
    // posting, a doctor CV against a nurse posting) - both scored real percentages under the old
    // logic. Two professions that both resolve in the taxonomy, to DIFFERENT nodes, are now
    // deterministically "unrelated" (no score) regardless of shared industry/keywords, full stop
    // - profession/role compatibility is checked first and is authoritative, before
    // specialization, licenses, seniority, skills, industry, or anything else. Professions the
    // taxonomy doesn't recognize still fall back to the existing AI-judged closeness, unchanged.
    //
    // v18.1: ProfessionTaxonomy#resolve switched from substring matching to word-set matching -
    // found via live verification that real postings titled "QA Automation Software Engineer"
    // and "QA Backend Test Role" don't contain the exact phrase "qa engineer" or "automation
    // engineer" as one contiguous substring (extra/reordered words in between), so they slipped
    // past the gate entirely and scored a normal 56-58% match against a Senior Software Engineer
    // CV - exactly the failure mode v18 was meant to close. Word-set matching (every word of the
    // alias present somewhere in the title, any order) catches these.
    // v18.2: fixed a tie-break bug in v18.1's word-set matcher - "QA Automation Software Engineer"
    // matched BOTH qa_engineer's "qa engineer" alias AND software_engineer's "software engineer"
    // alias (same 2-word length), and the tie silently went to whichever node happened to be
    // declared first in NODES. Added earliest-word-position tie-breaking (job titles
    // conventionally lead with the defining term) plus a standalone "qa" alias, verified live
    // against the same real postings that exposed v18.1's gap.
    // v19: profession compatibility is now a HIERARCHICAL model (SAME_ROLE / CLOSELY_RELATED /
    // RELATED / DIFFERENT_LICENSED_PROFESSION / UNRELATED - see ProfessionTaxonomy.
    // CompatibilityTier), not the binary compatible/incompatible model v18 introduced. Different
    // LICENSED professions (Doctor vs Nurse/Pharmacist/Dentist, Accountant vs Auditor, etc.) are
    // still hard-blocked exactly as before - but a curated CLOSELY_RELATED or RELATED pair (e.g.
    // Software Engineer vs QA Automation Engineer, Backend vs Full Stack Developer, Data Analyst
    // vs BI Analyst, DevOps vs Cloud Engineer) now gets a real, reduced score instead of being
    // rejected outright, reflecting genuine real-world career adjacency/transferability that a
    // strict binary model was over-blocking.
    // v20: rebalanced MatchScoreCalculator.WEIGHTS (field relevance 25->30%, required skills
    // 25->30%, education 15->10%, certification 10->5%; experience/location unchanged) - the same
    // AI classifications now produce a different weighted percentage, so every previously-cached
    // score is stale under the old formula and must be recomputed, not just newly-scored jobs.
    // v21: two reasoning improvements, per product request. (1) Skills: the AI may now credit a
    // handful of genuinely FUNDAMENTAL skills implied by the candidate's documented profession/
    // education/experience even when not literally written in the CV (e.g. Pharmacology for a
    // licensed doctor), via new matchedMandatorySkillsInferred/matchedPreferredSkillsInferred
    // arrays - but never a specialized/regulated skill (certifications, licenses, named tools/
    // frameworks/regulatory terms), and only for the candidate's own same_role/same_specialization
    // (see NON_INFERABLE_SKILL_TERMS, MAX_INFERRED_SKILLS_PER_JOB). (2) Experience: the AI can now
    // name a distinct experience sub-domain/type a posting asks for beyond general seniority (e.g.
    // "Clinical Research" on a "2+ years" posting) via requiredExperienceType/
    // candidateHasRequiredExperienceType, and MatchScoreCalculator#scoreExperience blends the
    // amount-based score down rather than either ignoring the type gap entirely (the old behavior
    // - a senior General Practitioner scored 100 on "2+ years Clinical Research experience" purely
    // because they cleared the YEARS bar) or scoring it as if the candidate had no experience at
    // all (which would misrepresent a genuinely senior candidate). Every previously-cached score
    // used neither of these signals, so this version bump forces a full recompute.
    private static final String MATCH_SCHEMA_VERSION = "v21-fundamental-skill-inference-and-experience-type";

    // General/entry-level/vocational roles - ones that don't require specialized prior training,
    // a degree, or domain-specific tools to perform (see VocationalRoleClassifier for the actual
    // keyword list, shared with the job-listing payload's category field). Two separate backend
    // overrides key off this classification: (1) never score the education component for these
    // (below), and (2) never let fieldRelationCloseness come back "unrelated" for these (see
    // applyParsedMatchToScore) - almost any reliable adult can work as a cashier or cleaner
    // regardless of their specialized background, so a software engineer or a doctor applying to
    // one of these must still get a real percentage, not "-". Verified live: without override (2),
    // a doctor/software engineer/cleaning supervisor CV against "Office Cleaner"/"Customer Service
    // Representative" postings came back fieldRelated=false, contradicting this exact design
    // intent - the prompt alone didn't reliably carry the exception, so it's enforced here instead
    // of trusted to the model.

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
        if (VocationalRoleClassifier.isGeneralVocationalRole(job.getTitle())) {
            return false;
        }
        return EmbeddingService.cosineSimilarity(profileVector, jobVector) < prefilterThreshold;
    }

    // Internal-jobs counterpart of ExternalJobService's attachEmbeddings/embeddingText - internal
    // Job rows didn't have embedding columns at all until now, which is why the prefilter never
    // actually applied to internal jobs (every internal caller always passed an empty embeddings
    // map). Lazy + fingerprinted exactly like ensureProfileEmbedding: only the jobs that actually
    // need computing (missing, or stale content/model) spend an embeddings call; everything else
    // is a pure cache hit against the job's own persisted vector, reused for every candidate who
    // is ever compared against it - "store job embeddings once and reuse them for all candidates."
    private Map<Long, float[]> ensureInternalJobEmbeddings(List<Job> jobs) {
        Map<Long, float[]> result = new HashMap<>();
        if (jobs.isEmpty()) {
            return result;
        }

        String modelKey = embeddingService.modelKey();
        List<Job> needingEmbedding = new ArrayList<>();

        for (Job job : jobs) {
            String text = internalJobEmbeddingText(job);
            String hash = HashUtil.sha256(text);

            if (hash.equals(job.getContentEmbeddingHash()) && modelKey.equals(job.getContentEmbeddingModel())
                    && job.getContentEmbedding() != null) {
                float[] vector = embeddingService.fromJson(job.getContentEmbedding());
                if (vector != null) {
                    result.put(job.getId(), vector);
                    continue;
                }
            }
            needingEmbedding.add(job);
        }

        if (!needingEmbedding.isEmpty()) {
            List<String> texts = needingEmbedding.stream().map(this::internalJobEmbeddingText).toList();
            List<float[]> vectors = embeddingService.embedBatch(texts);

            if (vectors.size() == needingEmbedding.size()) {
                for (int i = 0; i < needingEmbedding.size(); i++) {
                    Job job = needingEmbedding.get(i);
                    float[] vector = vectors.get(i);
                    job.setContentEmbedding(embeddingService.toJson(vector));
                    job.setContentEmbeddingHash(HashUtil.sha256(texts.get(i)));
                    job.setContentEmbeddingModel(modelKey);
                    result.put(job.getId(), vector);
                }
                jobRepository.saveAll(needingEmbedding);
            }
            // A partial/failed embeddings call (embedBatch fails open, returning List.of()) just
            // means these jobs are missing from `result` this round - shouldSkipAiViaPrefilter
            // already fails open on a missing vector (sends to AI), never guesses.
        }

        return result;
    }

    private String internalJobEmbeddingText(Job job) {
        String description = job.getDescription();
        if (description != null && description.length() > 1500) {
            description = description.substring(0, 1500);
        }
        return nullToEmpty(job.getTitle()) + ". " + nullToEmpty(description);
    }

    // One-time-per-boot catch-up for internal job rows with no embedding yet (created before this
    // column existed, or a prior embeddings call failed) - cheap no-op once nothing is missing, so
    // it's safe to run on every startup. Mirrors ExternalJobService#backfillMissingEmbeddingsOnStartup.
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void backfillMissingInternalJobEmbeddingsOnStartup() {
        List<Job> missing = jobRepository.findByContentEmbeddingIsNull();
        if (missing.isEmpty()) {
            return;
        }
        ensureInternalJobEmbeddings(missing);
    }

    // Deterministic, pre-AI gate - a job posting with essentially nothing beyond its own title
    // (no real description, requirements, or skills) can never support a reliable comparison.
    // Sending it to the AI anyway is exactly what produces confident-sounding but fabricated
    // output: found via live production data - job id 14, title "doctor", description "doctor"
    // (literally just the title again), requirements "Experience: 2 - 5 years", skills "doctor,
    // medicine, family" - the AI still returned an 81% match with a full paragraph of invented
    // detail (fabricated concerns about "experience working in Tel Aviv", unrequested "leadership
    // or public health experience", and "doctor" itself listed as a missing skill for a candidate
    // who IS a doctor) from essentially four words of real content. This check runs BEFORE the
    // embedding prefilter and before any AI call, so a thin posting costs nothing to identify -
    // never an AI judgment call, so it is 100% reproducible.
    // A posting with clearly bulleted/multi-line requirements, or a generous skill list, is never
    // insufficient regardless of total length - either is a strong standalone signal of a real
    // posting (short bullets are still real content). Everything else falls back to a combined
    // total-content-length check across all three fields together, rather than requiring each
    // field to individually clear its own bar - a job can legitimately split modest content
    // across description/requirements/skills (e.g. a one-line description plus a short skills
    // list) without any single field being long, and that must not be flagged as "insufficient"
    // the way a job that is really just its title repeated three ways should be.
    private static final int MIN_REAL_SKILL_TERMS = 3;
    private static final int MIN_TOTAL_CONTENT_CHARS = 65;

    private boolean isInsufficientJobData(Job job) {
        String title = nullToEmpty(job.getTitle()).trim();
        String normalizedTitle = normalizeForTitleComparison(title);
        String description = nullToEmpty(job.getDescription()).trim();
        String requirements = nullToEmpty(job.getRequirements()).trim();
        String skills = nullToEmpty(job.getSkills()).trim();

        // A description that just repeats the title verbatim says nothing a candidate could
        // actually be compared against, so it doesn't count toward the total.
        String descriptionBeyondTitle =
                normalizeForTitleComparison(description).equals(normalizedTitle) ? "" : description;

        // Real, distinct skill terms only - a "skills" field that just repeats the job's own
        // title (e.g. "doctor" on a job titled "doctor") is not a second real skill signal.
        long realSkillTerms = java.util.Arrays.stream(skills.split("[,;\\n]"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .filter(s -> !normalizeForTitleComparison(s).equals(normalizedTitle))
                .distinct()
                .count();

        boolean hasStructuredRequirements = requirements.lines().count() >= 2;
        boolean hasEnoughSkills = realSkillTerms >= MIN_REAL_SKILL_TERMS;
        int totalContentChars = descriptionBeyondTitle.length() + requirements.length() + skills.length();

        return !hasStructuredRequirements && !hasEnoughSkills && totalContentChars < MIN_TOTAL_CONTENT_CHARS;
    }

    // Persisted (cacheable, deterministic - never retried on the next visit) unlike the ephemeral
    // "AI call failed" error sentinel elsewhere in this class, which is intentionally NEVER saved.
    // fieldRelated is left null (no verdict was ever possible), matched via insufficientData=true
    // rather than fieldRelated itself - see JobMatchScore#insufficientData.
    private void applyInsufficientDataVerdict(
            JobMatchScore score, String email, long jobId,
            String cvFingerprint, String jobFingerprint, String jobContentFingerprint) {
        score.setCandidateEmail(email);
        score.setJobId(jobId);
        score.setInsufficientData(true);
        score.setFieldRelated(null);
        score.setMatchReason("Not enough job information to calculate a reliable match.");
        score.setMatchPercent(null);
        score.setMatchedSkills("");
        score.setMissingSkills("");
        score.setMatchedRequiredSkills("");
        score.setMatchedPreferredSkills("");
        score.setMissingRequiredSkills("");
        score.setMissingPreferredSkills("");
        score.setFieldRelevancePercent(null);
        score.setSkillsMatchPercent(null);
        score.setExperienceMatchPercent(null);
        score.setEducationMatchPercent(null);
        score.setCertificationMatchPercent(null);
        score.setLocationMatchPercent(null);
        score.setRequiredExperienceType(null);
        score.setCandidateHasRequiredExperienceType(null);
        score.setCvFingerprint(cvFingerprint);
        score.setJobFingerprint(jobFingerprint);
        score.setJobContentFingerprint(jobContentFingerprint);
        score.setRecommendation(null);
        score.setWhyGoodMatch(null);
        score.setWhyNotPerfectMatch(null);
        score.setImprovementSuggestions(null);
    }

    // The HIGHEST-priority gate in the whole matching pipeline (see ProfessionTaxonomy's own
    // documentation for the full rationale) - checked before the insufficient-data gate's sibling
    // checks, before the embedding prefilter, and before any AI call. A HIERARCHICAL result, not
    // binary: SAME_ROLE/CLOSELY_RELATED/RELATED all still proceed to AI scoring (with the field-
    // relevance component driven by this taxonomy rather than the AI's own judgment for the
    // latter two - see applyParsedMatchToScore); only DIFFERENT_LICENSED_PROFESSION and UNRELATED
    // are hard-blocked here with zero AI spend. UNKNOWN (either side didn't resolve to any
    // taxonomy node) is the honest "no opinion" result - the caller falls back to the existing
    // AI-judged fieldRelationCloseness for that pair rather than guessing; this gate does not need
    // to cover every possible profession to be safe, only to be correct where it does have an
    // opinion.
    //
    // Vocational/general roles (cashier, cleaner, security guard, etc.) are exempted entirely -
    // they're handled by the separate isGeneralVocationalRole override, which already makes them
    // compatible with any candidate's background regardless of profession, and must never be
    // blocked by this gate.
    ProfessionTaxonomy.CompatibilityTier checkProfessionCompatibility(CVAnalysis analysis, Job job) {
        if (VocationalRoleClassifier.isGeneralVocationalRole(job.getTitle())) {
            return ProfessionTaxonomy.CompatibilityTier.UNKNOWN;
        }

        ProfessionTaxonomy.ProfessionNode jobProfession = ProfessionTaxonomy.resolve(job.getTitle());
        if (jobProfession == null) {
            return ProfessionTaxonomy.CompatibilityTier.UNKNOWN;
        }

        // Resolves every profession the candidate might genuinely be qualified in (professionTitle
        // + previous job titles + AI-recommended roles), not just the single primary title - the
        // job is judged against whichever of these fits best, preserving the existing "a candidate
        // can be qualified in more than one field at once" support (career changers, dual-
        // qualified candidates).
        Set<ProfessionTaxonomy.ProfessionNode> candidateProfessions = ProfessionTaxonomy.resolveAll(
                analysis.getProfessionTitle(), analysis.getPreviousJobTitles(), analysis.getRecommendedRoles());
        if (candidateProfessions.isEmpty()) {
            return ProfessionTaxonomy.CompatibilityTier.UNKNOWN;
        }

        return ProfessionTaxonomy.classifyBest(candidateProfessions, jobProfession);
    }

    private static boolean isHardBlockedProfessionTier(ProfessionTaxonomy.CompatibilityTier tier) {
        return tier == ProfessionTaxonomy.CompatibilityTier.DIFFERENT_LICENSED_PROFESSION
                || tier == ProfessionTaxonomy.CompatibilityTier.UNRELATED;
    }

    // Persisted (cacheable, deterministic, zero AI spend) verdict for a taxonomy-confirmed
    // profession mismatch (DIFFERENT_LICENSED_PROFESSION or UNRELATED only - CLOSELY_RELATED/
    // RELATED are never hard-blocked, see applyParsedMatchToScore's override instead). Same
    // synthetic-ParsedMatch/applyParsedMatchToScore mechanism applyPrefilteredUnrelatedVerdict
    // below uses, but names both actual professions (the taxonomy knows what they both are,
    // unlike the embedding prefilter, which only has a bare similarity number to go on) and
    // explains WHY when it's specifically a licensing mismatch, not just an unrelated field.
    private void applyProfessionIncompatibleVerdict(
            JobMatchScore score, Job job, CVAnalysis analysis, String email, long jobId,
            String cvFingerprint, String jobFingerprint, String jobContentFingerprint,
            ProfessionTaxonomy.CompatibilityTier tier) {

        ProfessionTaxonomy.ProfessionNode jobProfession = ProfessionTaxonomy.resolve(job.getTitle());
        String candidateProfessionLabel = analysis.getProfessionTitle() != null && !analysis.getProfessionTitle().isBlank()
                ? analysis.getProfessionTitle()
                : nullToEmpty(analysis.getCandidateField());
        String jobProfessionLabel = jobProfession != null ? jobProfession.displayName() : nullToEmpty(job.getTitle());

        String reason = tier == ProfessionTaxonomy.CompatibilityTier.DIFFERENT_LICENSED_PROFESSION
                ? "Your background is in " + candidateProfessionLabel + ", and this " + jobProfessionLabel
                        + " role requires its own separate professional license or credential - the two are "
                        + "different regulated professions, so experience in one does not carry over to practicing the other."
                : "Your background is in " + candidateProfessionLabel + ", and this " + jobProfessionLabel
                        + " role is a different profession - even though it may share an industry or a few "
                        + "keywords with your field, the core job itself calls for different training and experience.";

        ParsedMatch synthetic = new ParsedMatch(
                jobId, job.getTitle(), jobContentFingerprint, "unrelated", reason,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null, null, null, null);

        applyParsedMatchToScore(score, synthetic, job, analysis, email, jobId,
                cvFingerprint, jobFingerprint, jobContentFingerprint);
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
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null, null, null, null);

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
            List<String> missingPreferredSkills,
            // What was actually required for the experience/education/certification dimensions
            // this job was scored against (e.g. "mid", "relevant_degree") - null when that
            // dimension wasn't applicable, exactly mirroring the corresponding *MatchPercent's
            // null-ness. Lets the UI explain WHY each Fit Breakdown row scored what it did.
            String requiredExperienceLevel,
            // Non-null only when this job named a distinct experience sub-domain/type beyond
            // general seniority (e.g. "Clinical Research") - see JobMatchScore's own comment on
            // these two fields. Lets the UI (and the detail narrative) distinguish "not enough
            // experience" from "right amount, wrong specialty."
            String requiredExperienceType,
            Boolean candidateHasRequiredExperienceType,
            String requiredEducationLevel,
            String requiredCertificationLevel,
            // When this row's score was last (re)computed - see JobMatchScore#updatedAt.
            java.time.LocalDateTime lastAnalyzedAt,
            // matchedSkills split by required/preferred, mirroring missingRequiredSkills/
            // missingPreferredSkills above - lets the UI badge a matched skill as "required" vs
            // "preferred" too, not just a missing one. Appended at the end (rather than next to
            // matchedSkills) so every existing positional constructor call only needs two new
            // trailing arguments, not a renumbering of everything after an inserted middle field.
            List<String> matchedRequiredSkills,
            List<String> matchedPreferredSkills
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
            matches.add(scoreToPayload(score, job));
        }

        return new MatchScoresResult(true, matches);
    }

    // Shared by getMatchScores (the synchronous list) and computeMatchScoresStreaming (progressive
    // per-job SSE payloads) - one place that defines what a "match" looks like over the wire.
    private Map<String, Object> scoreToPayload(JobMatchScore score, Job job) {
        Map<String, Object> match = new LinkedHashMap<>();
        match.put("jobId", score.getJobId());
        // fieldRelated is left as-is (including null) rather than coerced to true - null is
        // the honest "we couldn't compute this, please retry" sentinel, and collapsing it into
        // true would misrepresent a failed computation as a real, AI-decided verdict.
        match.put("fieldRelated", score.getFieldRelated());
        match.put("matchPercent", score.getMatchPercent());
        match.put("matchReason", score.getMatchReason());
        match.put("insufficientData", Boolean.TRUE.equals(score.getInsufficientData()));
        match.put("matchedSkills", splitSkillsString(score.getMatchedSkills()));
        match.put("missingSkills", splitSkillsString(score.getMissingSkills()));
        match.put("matchedRequiredSkills", splitSkillsString(score.getMatchedRequiredSkills()));
        match.put("matchedPreferredSkills", splitSkillsString(score.getMatchedPreferredSkills()));
        match.put("missingRequiredSkills", splitSkillsString(score.getMissingRequiredSkills()));
        match.put("missingPreferredSkills", splitSkillsString(score.getMissingPreferredSkills()));
        match.put("fieldRelevancePercent", score.getFieldRelevancePercent());
        match.put("skillsMatchPercent", score.getSkillsMatchPercent());
        match.put("experienceMatchPercent", score.getExperienceMatchPercent());
        match.put("educationMatchPercent", score.getEducationMatchPercent());
        match.put("certificationMatchPercent", score.getCertificationMatchPercent());
        match.put("locationMatchPercent", score.getLocationMatchPercent());

        // Category for the LISTING (not the score itself): a vocational/general role (see
        // VocationalRoleClassifier) is a title-only property, independent of any candidate - it's
        // always routed to its own "General & Vocational Jobs" section rather than mixed into
        // profession-based results, per product decision (a specialized candidate shouldn't be
        // blocked from applying, but a Cashier/Delivery Driver posting also shouldn't be presented
        // as if it were a meaningful professional match). excludedFromListing is the separate,
        // harder cut: a NON-vocational job the candidate's resolved profession is genuinely
        // unrelated to (taxonomy hard-block or a real AI "unrelated" verdict) is hidden from the
        // listing entirely, not just downweighted - "even with a low match percentage" per the
        // product ask. Only ever true for a CONCRETE fieldRelated=false (never for null, which is
        // the "couldn't compute yet, please retry" sentinel - an uncertain job must never
        // disappear from the list).
        boolean vocational = VocationalRoleClassifier.isGeneralVocationalRole(job.getTitle());
        match.put("generalVocationalRole", vocational);
        match.put("excludedFromListing", !vocational && Boolean.FALSE.equals(score.getFieldRelated()));
        return match;
    }

    private Map<String, Object> errorPayload(long jobId, Job job) {
        Map<String, Object> match = new LinkedHashMap<>();
        match.put("jobId", jobId);
        match.put("fieldRelated", null);
        match.put("matchPercent", null);
        match.put("matchReason", "We couldn't calculate a match for this job right now. Please try again.");
        match.put("insufficientData", false);
        match.put("matchedSkills", List.of());
        match.put("missingSkills", List.of());
        match.put("matchedRequiredSkills", List.of());
        match.put("matchedPreferredSkills", List.of());
        match.put("missingRequiredSkills", List.of());
        match.put("missingPreferredSkills", List.of());
        match.put("fieldRelevancePercent", null);
        match.put("skillsMatchPercent", null);
        match.put("experienceMatchPercent", null);
        match.put("educationMatchPercent", null);
        match.put("certificationMatchPercent", null);
        match.put("locationMatchPercent", null);
        // Never excluded on an error/uncertain result - see scoreToPayload's comment on
        // excludedFromListing. generalVocationalRole is still computed from the title alone (no
        // candidate data needed) so a job doesn't visually jump between listing sections on a
        // transient failure and subsequent retry.
        match.put("generalVocationalRole", VocationalRoleClassifier.isGeneralVocationalRole(job.getTitle()));
        match.put("excludedFromListing", false);
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
            String jobType, BiConsumer<Long, Map<String, Object>> onJobResult, Runnable onComplete) {

        // Measures only the DISPATCH phase (enqueueing every stale job and registering its
        // awaitResult callback) - this method returns before jobs actually finish, so this is
        // "how fast did the request thread get free again," not "how long until every score is
        // ready" (each job's own resolution time is logged separately, see MatchScoreQueueWorker).
        long methodStart = System.nanoTime();
        CVAnalysis analysis = cvAnalysisRepository.findByUserEmail(email).orElse(null);
        if (analysis == null || jobs == null || jobs.isEmpty()) {
            onComplete.run();
            return;
        }

        String cvFingerprint = fingerprintCv(analysis);

        long dbReadStart = System.nanoTime();
        List<Long> jobIds = jobs.stream().map(Job::getId).toList();
        Map<Long, JobMatchScore> cachedByJobId = new HashMap<>();
        for (JobMatchScore score : jobMatchScoreRepository.findByCandidateEmailAndJobIdIn(email, jobIds)) {
            cachedByJobId.put(score.getJobId(), score);
        }
        long dbReadMs = (System.nanoTime() - dbReadStart) / 1_000_000;
        matchMetrics.recordDbQuery("read_scores", dbReadMs);

        Map<Long, String> jobFingerprints = new HashMap<>();
        Map<Long, String> jobContentFingerprints = new HashMap<>();
        List<Job> jobsNeedingComputation = new ArrayList<>();

        for (Job job : jobs) {
            // Per-job isolation: these gates are currently pure/exception-free, but this loop
            // dispatches results for the WHOLE requested batch, and a single uncaught exception
            // here would previously propagate out of this method entirely - aborting every other
            // job in the batch too (caught only by the controller's outer try/catch, which ends
            // the whole SSE stream with an error). One bad job now degrades to that one job
            // getting the honest "couldn't compute" sentinel instead of taking the rest down with it.
            try {
                String jobFingerprint = fingerprintJob(job);
                jobFingerprints.put(job.getId(), jobFingerprint);
                jobContentFingerprints.put(job.getId(), buildJobContentFingerprint(job, jobFingerprint));

                JobMatchScore cached = cachedByJobId.get(job.getId());
                boolean isStale = cached == null
                        || !cvFingerprint.equals(cached.getCvFingerprint())
                        || !jobFingerprint.equals(cached.getJobFingerprint());

                if (!isStale) {
                    matchMetrics.recordCacheHit(jobType);
                    onJobResult.accept(job.getId(), scoreToPayload(cached, job));
                } else if (isInsufficientJobData(job)) {
                    matchMetrics.recordInsufficientData();
                    JobMatchScore score = cachedByJobId.getOrDefault(job.getId(), new JobMatchScore());
                    applyInsufficientDataVerdict(score, email, job.getId(),
                            cvFingerprint, jobFingerprint, jobContentFingerprints.get(job.getId()));
                    score = jobMatchScoreRepositorySafeSave(score, email, job.getId());
                    onJobResult.accept(job.getId(), scoreToPayload(score, job));
                } else {
                    ProfessionTaxonomy.CompatibilityTier tier = checkProfessionCompatibility(analysis, job);
                    if (isHardBlockedProfessionTier(tier)) {
                        matchMetrics.recordProfessionIncompatible();
                        JobMatchScore score = cachedByJobId.getOrDefault(job.getId(), new JobMatchScore());
                        applyProfessionIncompatibleVerdict(score, job, analysis, email, job.getId(),
                                cvFingerprint, jobFingerprint, jobContentFingerprints.get(job.getId()), tier);
                        score = jobMatchScoreRepositorySafeSave(score, email, job.getId());
                        onJobResult.accept(job.getId(), scoreToPayload(score, job));
                    } else {
                        matchMetrics.recordCacheMiss(jobType);
                        jobsNeedingComputation.add(job);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to dispatch match-score gates for candidate={} jobId={} - surfacing the retry sentinel for just this job",
                        email, job.getId(), e);
                onJobResult.accept(job.getId(), errorPayload(job.getId(), job));
            }
        }

        if (jobsNeedingComputation.isEmpty()) {
            onComplete.run();
            return;
        }

        // Internal jobs get their own embeddings computed/cached here (see
        // ensureInternalJobEmbeddings) - external jobs already arrive with theirs via
        // ExternalJobService, which computes them once at import time. Either way, this is what
        // makes the pre-filter actually apply to BOTH job types instead of only ever firing for
        // external jobs (it silently never fired for internal jobs before this - callers always
        // passed an empty embeddings map).
        Map<Long, float[]> effectiveEmbeddings = jobEmbeddings == null
                ? new HashMap<>() : new HashMap<>(jobEmbeddings);
        if ("internal".equals(jobType)) {
            effectiveEmbeddings.putAll(ensureInternalJobEmbeddings(jobsNeedingComputation));
        }

        // Only bother computing/loading the candidate's own profile vector if there's actually
        // at least one job vector to compare it against - avoids a wasted embeddings call
        // otherwise.
        boolean anyJobEmbeddings = !effectiveEmbeddings.isEmpty();
        float[] profileVector = anyJobEmbeddings
                ? embeddingService.ensureProfileEmbedding(analysis, cvAnalysisRepository)
                : null;

        List<Job> jobsSentToAi = new ArrayList<>();
        Map<Long, Float> similarityByJobId = new HashMap<>();

        for (Job job : jobsNeedingComputation) {
            float[] jobVector = effectiveEmbeddings.get(job.getId());
            if (profileVector != null && jobVector != null) {
                similarityByJobId.put(job.getId(), EmbeddingService.cosineSimilarity(profileVector, jobVector));
            }

            if (shouldSkipAiViaPrefilter(job, profileVector, jobVector)) {
                JobMatchScore score = cachedByJobId.getOrDefault(job.getId(), new JobMatchScore());
                applyPrefilteredUnrelatedVerdict(score, job, analysis, email, job.getId(),
                        cvFingerprint, jobFingerprints.get(job.getId()), jobContentFingerprints.get(job.getId()));
                score = jobMatchScoreRepositorySafeSave(score, email, job.getId());
                onJobResult.accept(job.getId(), scoreToPayload(score, job));
            } else {
                jobsSentToAi.add(job);
            }
        }

        if (jobsSentToAi.isEmpty()) {
            onComplete.run();
            return;
        }

        // Handed off to the persistent queue instead of computed inline on a per-request virtual
        // thread - the actual OpenAI call now happens in MatchScoreQueueWorker's own bounded,
        // rate-limited pool, decoupled from this request's lifetime (the candidate can navigate
        // away or close the tab and background pre-computation continues regardless). This
        // request only enqueues (idempotent - a job already queued by another concurrent request
        // is left alone, not duplicated) and awaits a result, which still resolves the moment
        // it's ready so the streaming/progressive UX is unchanged from the candidate's side.
        AtomicInteger remaining = new AtomicInteger(jobsSentToAi.size());

        for (Job job : jobsSentToAi) {
            long jobId = job.getId();
            String jobFingerprint = jobFingerprints.get(jobId);

            matchScoreQueueService.enqueueIfNeeded(email, job, jobType, language, cvFingerprint, jobFingerprint);
            CompletableFuture<JobMatchScore> future = matchScoreQueueService.awaitResult(
                    email, jobId, jobType, cvFingerprint, jobFingerprint, queueAwaitTimeoutMs);

            future.whenComplete((score, ex) -> {
                Float similarity = similarityByJobId.get(jobId);
                if (similarity != null) {
                    // Shadow-calibration signal: logged for every job that actually reached the
                    // AI (whether because the prefilter is disabled, or scored above threshold),
                    // so the threshold in application.properties can be validated against real
                    // (similarity, AI verdict) pairs before - and after - it's trusted to skip
                    // AI calls outright. See matching.embedding.prefilter.* config.
                    Boolean fieldRelated = score != null ? score.getFieldRelated() : null;
                    log.info("prefilter-shadow email={} jobId={} similarity={} fieldRelated={}",
                            email, jobId, similarity, fieldRelated);
                }

                Map<String, Object> payload = (score != null) ? scoreToPayload(score, job) : errorPayload(jobId, job);
                onJobResult.accept(jobId, payload);

                if (remaining.decrementAndGet() == 0) {
                    onComplete.run();
                }
            });
        }
        log.info("match-scores-streaming candidate={} dispatchMs={}", email, (System.nanoTime() - methodStart) / 1_000_000);
    }

    // Singleflight per-job AI computation shared by every caller - the synchronous ensureCoreScores
    // path (getMatchScores/getMatchDetail) and the streaming path (computeMatchScoresStreaming)
    // above both route through this exact method/map, so no matter how many concurrent requests
    // ask about the same candidate+job - same session, different pages, different tabs, sync or
    // streaming - only one OpenAI call is ever made; every other caller joins the SAME future
    // instead of starting its own. This is what makes opening the dashboard and the job matches
    // page around the same time (or two browser tabs) structurally unable to duplicate AI spend.
    private CompletableFuture<JobMatchScore> singleflightComputeJob(
            String email, Job job, CVAnalysis analysis, String language, String cvFingerprint,
            Map<Long, String> jobFingerprints, Map<Long, String> jobContentFingerprints,
            Map<Long, JobMatchScore> cachedByJobId, Semaphore limiter, ExecutorService executor) {

        String inFlightKey = email + "|" + job.getId();

        return inFlightComputations.computeIfAbsent(inFlightKey, k -> {
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
    }

    // Package-private (not private): MatchScoreQueueWorker reuses this exact validated logic
    // rather than duplicating it - see this class's other package-private compute methods below.
    JsonNode firstMatchForJob(JsonNode matches, long jobId) {
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
        long methodStart = System.nanoTime();
        String cvFingerprint = fingerprintCv(analysis);

        long dbReadStart = System.nanoTime();
        List<Long> jobIds = jobs.stream().map(Job::getId).toList();
        Map<Long, JobMatchScore> cachedByJobId = new HashMap<>();
        for (JobMatchScore score : jobMatchScoreRepository.findByCandidateEmailAndJobIdIn(email, jobIds)) {
            cachedByJobId.put(score.getJobId(), score);
        }
        long dbReadMs = (System.nanoTime() - dbReadStart) / 1_000_000;
        matchMetrics.recordDbQuery("read_scores", dbReadMs);

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

            if (isStale && isInsufficientJobData(job)) {
                JobMatchScore score = cachedByJobId.getOrDefault(job.getId(), new JobMatchScore());
                applyInsufficientDataVerdict(score, email, job.getId(),
                        cvFingerprint, jobFingerprint, jobContentFingerprints.get(job.getId()));
                cachedByJobId.put(job.getId(), jobMatchScoreRepositorySafeSave(score, email, job.getId()));
            } else if (isStale && isHardBlockedProfessionTier(checkProfessionCompatibility(analysis, job))) {
                ProfessionTaxonomy.CompatibilityTier tier = checkProfessionCompatibility(analysis, job);
                matchMetrics.recordProfessionIncompatible();
                JobMatchScore score = cachedByJobId.getOrDefault(job.getId(), new JobMatchScore());
                applyProfessionIncompatibleVerdict(score, job, analysis, email, job.getId(),
                        cvFingerprint, jobFingerprint, jobContentFingerprints.get(job.getId()), tier);
                cachedByJobId.put(job.getId(), jobMatchScoreRepositorySafeSave(score, email, job.getId()));
            } else if (isStale) {
                jobsNeedingComputation.add(job);
            }
        }

        log.info("match-scores-timing candidate={} totalJobs={} cacheHits={} needingComputation={} dbReadMs={}",
                email, jobs.size(), jobs.size() - jobsNeedingComputation.size(), jobsNeedingComputation.size(), dbReadMs);

        if (!jobsNeedingComputation.isEmpty()) {
            long aiPhaseStart = System.nanoTime();
            Semaphore limiter = new Semaphore(MAX_CONCURRENT_MATCH_CALLS);

            // Every job's AI call is routed through the same singleflightComputeJob/
            // inFlightComputations guard the streaming endpoint uses - so a concurrent request
            // for the same candidate+job (another tab, the streaming endpoint, or a second
            // synchronous request racing this one) joins this exact computation instead of
            // triggering a second OpenAI call for it.
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Map<Long, CompletableFuture<JobMatchScore>> futuresByJobId = new LinkedHashMap<>();
                for (Job job : jobsNeedingComputation) {
                    futuresByJobId.put(job.getId(), singleflightComputeJob(email, job, analysis, language,
                            cvFingerprint, jobFingerprints, jobContentFingerprints, cachedByJobId, limiter, executor));
                }

                for (Job job : jobsNeedingComputation) {
                    JobMatchScore score;
                    try {
                        score = futuresByJobId.get(job.getId()).join();
                    } catch (Exception e) {
                        // A DB save or notification hiccup on ONE job must not take out the rest
                        // of the batch - only THIS job falls through to the sentinel below.
                        log.error("Failed to compute match score for candidate {} / job {}", email, job.getId(), e);
                        score = null;
                    }

                    if (score != null) {
                        cachedByJobId.put(job.getId(), score);
                    } else {
                        // The AI call failed even after computeChunkWithRetry's validation-guided
                        // retry - surface that honestly instead of caching a guessed verdict. This
                        // is deliberately never saved to the repository, so the very next request
                        // retries the real computation instead of getting stuck on a cached failure.
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

            long aiPhaseMs = (System.nanoTime() - aiPhaseStart) / 1_000_000;
            log.info("match-scores-timing candidate={} aiPhaseMs={} jobsComputed={} avgMsPerJob={}",
                    email, aiPhaseMs, jobsNeedingComputation.size(),
                    jobsNeedingComputation.isEmpty() ? 0 : aiPhaseMs / jobsNeedingComputation.size());
        }

        log.info("match-scores-timing candidate={} totalMs={}", email, (System.nanoTime() - methodStart) / 1_000_000);
        return cachedByJobId;
    }

    void maybeNotifyHighMatch(String email, Job job, JobMatchScore score) {
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
    JobMatchScore jobMatchScoreRepositorySafeSave(JobMatchScore score, String email, long jobId) {
        long start = System.nanoTime();
        try {
            return jobMatchScoreRepository.save(score);
        } catch (DataIntegrityViolationException e) {
            return jobMatchScoreRepository.findByCandidateEmailAndJobId(email, jobId).orElseThrow(() -> e);
        } finally {
            long dbSaveMs = (System.nanoTime() - start) / 1_000_000;
            log.info("match-scores-timing jobId={} dbSaveMs={}", jobId, dbSaveMs);
            matchMetrics.recordDbQuery("save_score", dbSaveMs);
        }
    }

    // This is the ONE place a percentage is ever produced. The AI's job (parsed above) is
    // strictly classification: which of a handful of fixed labels applies to this candidate+job
    // pair. Every one of those labels is turned into a number here, via MatchScoreCalculator's
    // rule tables/formulas - never by trusting a number the AI wrote itself. That is what makes
    // "same CV + same job -> same score" hold even across AI response variance, since the only
    // way the score changes is if the AI's CLASSIFICATION changes, and identical classifications
    // always produce identical numbers.
    void applyParsedMatchToScore(
            JobMatchScore score, ParsedMatch parsed, Job job, CVAnalysis analysis, String email, long jobId,
            String cvFingerprint, String jobFingerprint, String jobContentFingerprint) {

        // A real AI verdict was reached (however it comes out below) - never the deterministic
        // "posting too thin to score" gate (see applyInsufficientDataVerdict), so this is always
        // explicitly false here, never left null/ambiguous in a persisted row.
        score.setInsufficientData(false);

        String effectiveCloseness = parsed.fieldRelationCloseness();
        String overrideMatchReason = null;

        // Backend override: the profession-taxonomy gate already ran before this job was ever
        // sent to the AI (see checkProfessionCompatibility) - only jobs it judged SAME_ROLE,
        // CLOSELY_RELATED, RELATED, or UNKNOWN ever reach here (DIFFERENT_LICENSED_PROFESSION/
        // UNRELATED are hard-blocked earlier, with zero AI spend). CLOSELY_RELATED/RELATED
        // override the AI's own field-relevance judgment with the taxonomy's - it has a curated,
        // deterministic opinion for this exact pair (e.g. Software Engineer <-> QA Automation
        // Engineer) that is more reliable than asking the AI to freely judge closeness itself.
        // SAME_ROLE/UNKNOWN are deliberately NOT overridden here - SAME_ROLE still lets the AI
        // choose between "same_role" and "same_specialization" for that within-profession nuance,
        // and UNKNOWN means the taxonomy has no opinion for this pair at all.
        ProfessionTaxonomy.CompatibilityTier tier = checkProfessionCompatibility(analysis, job);
        if (tier == ProfessionTaxonomy.CompatibilityTier.CLOSELY_RELATED) {
            effectiveCloseness = "closely_related";
            overrideMatchReason = "This is a closely related profession to your background - the core skills "
                    + "transfer well even though it isn't your exact role, so you're still a meaningful candidate for it.";
        } else if (tier == ProfessionTaxonomy.CompatibilityTier.RELATED) {
            effectiveCloseness = "related";
            overrideMatchReason = "This is a related profession to your background - some skills and context "
                    + "transfer, though it's a distinctly different specific role from your own.";
        } else if ("unrelated".equalsIgnoreCase(effectiveCloseness) && VocationalRoleClassifier.isGeneralVocationalRole(job.getTitle())) {
            // A general/vocational role must never come back "unrelated" for the candidate's
            // specialized background - see GENERAL_VOCATIONAL_ROLE_KEYWORDS. Only overrides an
            // "unrelated" verdict; a candidate whose own field genuinely IS this role (e.g. a
            // cleaner CV against a cleaning job) keeps whatever closeness the AI gave them.
            effectiveCloseness = "general_vocational_role";
            overrideMatchReason = "This role doesn't require specialized experience in your field, so your background and"
                    + " work history are a reasonable fit.";
        }

        boolean fieldRelated = !"unrelated".equalsIgnoreCase(effectiveCloseness);

        score.setCandidateEmail(email);
        score.setJobId(jobId);
        score.setFieldRelated(fieldRelated);
        score.setMatchReason(overrideMatchReason != null ? overrideMatchReason : parsed.matchReason());
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
            score.setMatchedRequiredSkills("");
            score.setMatchedPreferredSkills("");
            score.setMissingRequiredSkills("");
            score.setMissingPreferredSkills("");
            score.setFieldRelevancePercent(null);
            score.setSkillsMatchPercent(null);
            score.setExperienceMatchPercent(null);
            score.setEducationMatchPercent(null);
            score.setCertificationMatchPercent(null);
            score.setLocationMatchPercent(null);
            score.setRequiredExperienceLevel(null);
            score.setRequiredExperienceType(null);
            score.setCandidateHasRequiredExperienceType(null);
            score.setRequiredEducationLevel(null);
            score.setRequiredCertificationLevel(null);
            return;
        }

        // Inferred ("fundamental skill") matches are folded into the same matched-skills counts/
        // display as literally-evidenced ones - once validateMatch has confirmed an inferred skill
        // clears its (stricter) bar, the candidate genuinely has it as far as this match is
        // concerned, so it counts the same toward the skills score and the persisted skill list as
        // any other matched skill. See ParsedMatch's own comment for why they're kept in separate
        // AI-response arrays despite being merged again here - the separation is for validation,
        // not for scoring.
        List<String> matchedRequired = concat(parsed.matchedMandatorySkills(), parsed.matchedMandatorySkillsInferred());
        List<String> matchedPreferred = concat(parsed.matchedPreferredSkills(), parsed.matchedPreferredSkillsInferred());
        List<String> allMatched = concat(matchedRequired, matchedPreferred);
        List<String> allMissing = concat(parsed.missingMandatorySkills(), parsed.missingPreferredSkills());

        Integer skillsScore = MatchScoreCalculator.computeSkillsScore(
                parsed.matchedMandatorySkills().size() + parsed.matchedMandatorySkillsInferred().size(),
                parsed.missingMandatorySkills().size(),
                parsed.matchedPreferredSkills().size() + parsed.matchedPreferredSkillsInferred().size(),
                parsed.missingPreferredSkills().size());

        // Backend override: never score education for a general/vocational role even if the AI
        // mistakenly stated a requirement for one - see isGeneralVocationalRole.
        boolean isVocationalRole = VocationalRoleClassifier.isGeneralVocationalRole(job.getTitle());
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
                        analysis.getExperienceLevel(), parsed.requiredExperienceLevel(), sameSpecificRole,
                        parsed.requiredExperienceType() != null,
                        Boolean.TRUE.equals(parsed.candidateHasRequiredExperienceType()));
        Integer educationScore = requiredEducationLevel == null ? null
                : MatchScoreCalculator.scoreEducation(analysis.getEducationEvidence(), requiredEducationLevel);
        Integer certificationScore = parsed.requiredCertificationLevel() == null ? null
                : MatchScoreCalculator.scoreCertification(
                        analysis.getCertificationsEvidence(), analysis.getLicensesEvidence(),
                        parsed.requiredCertificationLevel(), sameSpecificRole);
        Integer locationScore = MatchScoreCalculator.scoreLocation(job.getType(), job.getLocation());

        // Persisted alongside their corresponding *Score above (same null-ness in every case) so
        // the Match Details page can show WHAT was required for each dimension, not just the
        // resulting percentage - see JobMatchScore's own comment on these fields.
        score.setRequiredExperienceLevel(experienceScore == null ? null : parsed.requiredExperienceLevel());
        score.setRequiredExperienceType(experienceScore == null ? null : parsed.requiredExperienceType());
        score.setCandidateHasRequiredExperienceType(experienceScore == null ? null : parsed.candidateHasRequiredExperienceType());
        score.setRequiredEducationLevel(requiredEducationLevel);
        score.setRequiredCertificationLevel(certificationScore == null ? null : parsed.requiredCertificationLevel());

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
        score.setMatchedRequiredSkills(String.join("|", matchedRequired));
        score.setMatchedPreferredSkills(String.join("|", matchedPreferred));
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
                    null, null, null, null, null, null, null, null, null, null, List.of(), List.of(),
                    null, null, null, null, null, null, List.of(), List.of());
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
                    null, null, null, null, null, null, null, null, null, null, List.of(), List.of(),
                    null, null, null, null, null, core != null ? core.getUpdatedAt() : null, List.of(), List.of());
        }

        boolean fieldRelated = core.getFieldRelated();
        List<String> matchedSkills = splitSkillsString(core.getMatchedSkills());
        List<String> missingSkills = splitSkillsString(core.getMissingSkills());
        List<String> matchedRequiredSkills = splitSkillsString(core.getMatchedRequiredSkills());
        List<String> matchedPreferredSkills = splitSkillsString(core.getMatchedPreferredSkills());
        List<String> missingRequiredSkills = splitSkillsString(core.getMissingRequiredSkills());
        List<String> missingPreferredSkills = splitSkillsString(core.getMissingPreferredSkills());

        if (!fieldRelated) {
            // Already decided by the core computation - no LLM call needed, and no risk of a
            // detail-only prompt second-guessing a "not a fit" verdict the list already showed.
            return new MatchDetailResult(true, job.getId(), null, core.getMatchReason(),
                    List.of(), List.of(), List.of(), List.of(), List.of(),
                    "A meaningful evaluation isn't possible for this job given the field mismatch.",
                    false, false, null, null, null, null, null, null, null, List.of(), List.of(),
                    null, null, null, null, null, core.getUpdatedAt(), List.of(), List.of());
        }

        // recommendation is the sentinel for "detail explanation generated for the CURRENT
        // core score": ensureCoreScores clears it whenever the core score changes, so a blank
        // value here means either this job's detail has never been generated, or the score it
        // was generated against is now stale - either way, regenerate against the current score.
        // detailPromptVersion catches the other kind of staleness: a narrative that was generated
        // against the CURRENT core score but under an OLDER version of validateDetailClaims/the
        // detail prompt - see DETAIL_PROMPT_VERSION's comment for why that must also force a
        // regeneration rather than being served indefinitely.
        boolean detailStale = core.getRecommendation() == null || core.getRecommendation().isBlank()
                || core.getDetailPromptVersion() == null || core.getDetailPromptVersion() != DETAIL_PROMPT_VERSION;

        if (detailStale) {
            String result = openAICVAnalysisService.computeJobMatchDetail(
                    analysis, job, language, core.getMatchPercent(), matchedSkills, missingSkills,
                    core.getRequiredExperienceType(), core.getCandidateHasRequiredExperienceType());
            JsonNode json = readDetailObject(result);

            if (json != null) {
                core.setLanguageMatchPercent(json.has("languageMatchPercent") ? json.path("languageMatchPercent").asInt() : null);

                // computeJobMatches' matchedSkills/missingSkills go through validateMatch before
                // ever being trusted - this free-text narrative call has no equivalent fixed-
                // vocabulary classification step, so validateDetailClaims is its evidence check:
                // any bullet this can positively identify as unsupported by the job's own text is
                // dropped rather than shown. See validateDetailClaims for the production examples
                // that made this necessary (location-as-experience, ungrounded filler concerns,
                // framing more-than-required experience as a disadvantage).
                List<String> whyGoodMatch = validateDetailClaims(
                        toStringList(json.path("whyGoodMatch")), job, matchedSkills, missingSkills, true);
                List<String> whyNotPerfectMatch = validateDetailClaims(
                        toStringList(json.path("whyNotPerfectMatch")), job, matchedSkills, missingSkills, false);
                List<String> improvementSuggestions = validateDetailClaims(
                        toStringList(json.path("improvementSuggestions")), job, matchedSkills, missingSkills, false);

                core.setWhyGoodMatch(String.join("|", whyGoodMatch));
                core.setWhyNotPerfectMatch(whyNotPerfectMatch.isEmpty()
                        ? "No specific concerns beyond your background - this looks like a strong overall match."
                        : String.join("|", whyNotPerfectMatch));
                core.setImprovementSuggestions(improvementSuggestions.isEmpty()
                        ? "No specific suggestions - your profile already aligns well with this role's stated requirements."
                        : String.join("|", improvementSuggestions));
                // recommendation must end up non-blank on success so the check above can tell a
                // completed computation apart from one that never ran / previously failed.
                String recommendation = json.path("recommendation").asText("");
                core.setRecommendation(recommendation.isBlank() ? "No specific recommendation available." : recommendation);
                core.setShouldApply(resolveShouldApply(core.getMatchPercent(), json.path("shouldApply").asBoolean(true)));
                core.setDetailPromptVersion(DETAIL_PROMPT_VERSION);

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
                missingPreferredSkills,
                core.getRequiredExperienceLevel(),
                core.getRequiredExperienceType(),
                core.getCandidateHasRequiredExperienceType(),
                core.getRequiredEducationLevel(),
                core.getRequiredCertificationLevel(),
                core.getUpdatedAt(),
                matchedRequiredSkills,
                matchedPreferredSkills
        );
    }

    // Deterministic floor/ceiling so shouldApply (and therefore the recommendation text built
    // around it) can never contradict the score it's supposed to be advising on - purely an AI
    // judgment call today would let the free-text call recommend applying to a job the core score
    // already decided is a poor fit, or discourage applying to one it decided is a strong fit.
    // Below the floor the mismatch is severe enough that encouraging an application would be
    // actively misleading regardless of what the narrative call said; at or above the ceiling the
    // fit is strong enough that discouraging one would be equally wrong. Only the middle band is
    // left to the AI's own judgment (per computeJobMatchDetail's prompt instruction), where a real
    // "apply despite minor gaps" vs. "skip this one" call benefits from nuance a fixed threshold
    // alone can't capture.
    private static final int SHOULD_APPLY_FLOOR = 35;
    private static final int SHOULD_APPLY_CEILING = 70;

    // Package-private (not private) for direct unit testing, matching parseMatch/
    // applyParsedMatchToScore's existing pattern in this class.
    boolean resolveShouldApply(Integer matchPercent, boolean aiShouldApply) {
        if (matchPercent == null) {
            return aiShouldApply;
        }
        if (matchPercent < SHOULD_APPLY_FLOOR) {
            return false;
        }
        if (matchPercent >= SHOULD_APPLY_CEILING) {
            return true;
        }
        return aiShouldApply;
    }

    private JsonNode readDetailObject(String result) {
        try {
            JsonNode node = objectMapper.readTree(result);
            return node != null && node.isObject() && node.size() > 0 ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    // Fixed set of commonly-hallucinated "concern" topics found via production testing (a
    // General Practitioner CV against a job titled "doctor" got criticized for missing
    // "leadership or public health experience" the posting never mentioned) - a bullet may only
    // raise one of these if the job's OWN text actually contains it; otherwise there is nothing
    // real for the candidate to be "missing" relative to.
    private static final List<String> UNGROUNDED_FILLER_TERMS = List.of(
            "leadership", "public health", "certification", "certifications",
            "language requirement", "language skills", "local experience", "local regulations"
    );

    // Phrasings that frame MORE experience/seniority than required as a concern - never valid
    // unless the posting itself states an explicit maximum or a junior/entry-only restriction
    // (see EXPLICIT_EXPERIENCE_CAP below). MatchScoreCalculator#scoreExperience already never
    // penalizes exceeding the required level numerically; this is purely about the free-text
    // narrative independently inventing the same non-existent penalty in prose.
    private static final List<String> OVERQUALIFICATION_PHRASES = List.of(
            "overqualified", "over-qualified", "exceeds the job's requirement", "exceeds the stated",
            "above the stated", "above the required", "closer to the", "may prefer candidates with experience levels",
            "your seniority may be", "targeted at less experienced"
    );

    private static final java.util.regex.Pattern EXPLICIT_EXPERIENCE_CAP = java.util.regex.Pattern.compile(
            "(maximum|no more than|up to \\d+\\s*years|junior[- ]only|entry[- ]level only)",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    // Filters computeJobMatchDetail's whyGoodMatch/whyNotPerfectMatch/improvementSuggestions
    // bullets against the job's own text. Unlike computeJobMatches (see validateMatch), this
    // free-text narrative call has no fixed-vocabulary classification to check the AI's output
    // against - this is its evidence check. These are advisory bullet points, so an unsupported
    // one is simply dropped rather than triggering a whole-call retry (see the fallback text at
    // this method's only call site for what happens if every bullet in a list is dropped).
    //
    // Found in production: a General Practitioner CV against a job titled "doctor" in Tel Aviv
    // (posting text: only "Experience: 2 - 5 years") produced bullets claiming "no explicit
    // mention of experience working in Tel Aviv" (the job's LOCATION field is where the job IS,
    // never a prior-work-experience requirement the candidate must independently have),
    // criticizing missing "leadership or public health experience" (the posting never asked for
    // either), and framing the candidate's 8+ years as a concern against a posting that stated no
    // maximum at all.
    //
    // matchedSkills/missingSkills/isPositiveBulletList: this free-text call is given the CORE
    // computation's already-decided matched/missing skill lists (see computeJobMatchDetail's
    // givenScoreBlock) specifically so it stays consistent with them - but nothing enforced that
    // consistency until now. isPositiveBulletList=true (whyGoodMatch) rejects any bullet that
    // cites something the core score already decided is MISSING - praising an admittedly-absent
    // skill as a reason this is a good match is always a contradiction, regardless of phrasing.
    // isPositiveBulletList=false (whyNotPerfectMatch/improvementSuggestions) rejects a bullet only
    // when it BOTH names something the core score already decided is MATCHED and pairs it with an
    // absence phrase (see SkillClaimMatcher.hasGenuineAbsenceClaim) - narrower than the
    // positive-list check because a matched skill can legitimately be mentioned as context in an
    // honest gap explanation without claiming it's missing.
    private List<String> validateDetailClaims(
            List<String> bullets, Job job, List<String> matchedSkills, List<String> missingSkills,
            boolean isPositiveBulletList) {
        if (bullets == null || bullets.isEmpty()) {
            return List.of();
        }

        String jobBlob = jobRequirementsBlob(job);
        String jobLocation = nullToEmpty(job.getLocation()).trim().toLowerCase(Locale.ROOT);
        boolean locationDiscussedInRequirements = !jobLocation.isBlank() && jobBlob.contains(jobLocation);
        boolean hasExplicitExperienceCap = EXPLICIT_EXPERIENCE_CAP
                .matcher(nullToEmpty(job.getRequirements()) + " " + nullToEmpty(job.getDescription()))
                .find();

        List<String> filtered = new ArrayList<>();
        for (String bullet : bullets) {
            if (bullet == null || bullet.isBlank()) {
                continue;
            }
            String lower = bullet.toLowerCase(Locale.ROOT);

            if (!jobLocation.isBlank() && lower.contains(jobLocation) && !locationDiscussedInRequirements
                    && (lower.contains("experience") || lower.contains("familiar") || lower.contains("background"))) {
                log.info("match-detail-filtered jobId={} reason=location-as-experience", job.getId());
                continue;
            }

            boolean hasUngroundedFiller = UNGROUNDED_FILLER_TERMS.stream()
                    .anyMatch(term -> lower.contains(term) && !jobBlob.contains(term));
            if (hasUngroundedFiller) {
                log.info("match-detail-filtered jobId={} reason=ungrounded-filler", job.getId());
                continue;
            }

            if (!hasExplicitExperienceCap && OVERQUALIFICATION_PHRASES.stream().anyMatch(lower::contains)) {
                log.info("match-detail-filtered jobId={} reason=overqualification-claim", job.getId());
                continue;
            }

            if (isPositiveBulletList) {
                boolean citesMissingSkillAsPositive = missingSkills.stream()
                        .anyMatch(skill -> SkillClaimMatcher.mentionsSkill(lower, skill));
                if (citesMissingSkillAsPositive) {
                    log.info("match-detail-filtered jobId={} reason=praises-missing-skill", job.getId());
                    continue;
                }
            } else {
                boolean citesMatchedSkillAsAbsent = SkillClaimMatcher.hasGenuineAbsenceClaim(lower)
                        && matchedSkills.stream().anyMatch(skill -> SkillClaimMatcher.mentionsSkill(lower, skill));
                if (citesMatchedSkillAsAbsent) {
                    log.info("match-detail-filtered jobId={} reason=flags-matched-skill-as-gap", job.getId());
                    continue;
                }
            }

            filtered.add(bullet);
        }
        return filtered;
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

    // Every job is scored with its own OpenAI call (never batched into one prompt covering
    // several jobs): asking the model to track multiple numeric jobIds and keep each one's
    // verdict correctly attached in one response is exactly the kind of bookkeeping large batches
    // make LLMs more likely to fumble - a real, observed failure was one job in a mixed batch
    // getting a different job's field-mismatch verdict. One job per request makes that entire
    // failure mode structurally impossible, not just less likely; the jobFingerprint/jobTitle
    // cross-check in validateMatch is the remaining defense against the model still garbling its
    // own single answer. Scoring runs concurrently across jobs on virtual threads (I/O-bound -
    // just waiting on HTTP responses), capped by MAX_CONCURRENT_MATCH_CALLS below, so wall-clock
    // time for a whole stale batch scales with how many calls run at once, not the batch size.
    //
    // Firing every job's call at once made concurrent OpenAI calls far more likely to trip a
    // rate limit or transient error than the old single-call-per-request flow ever was - a failed
    // call silently showed "no score" to the candidate with no retry. Cap how many calls are in
    // flight at once, and retry once - with the specific validation errors from the first attempt
    // - before giving up on a job for this request.
    private static final int MAX_CONCURRENT_MATCH_CALLS = 5;

    record ValidatedBatch(List<JsonNode> validMatches, boolean allValid, Map<Long, List<String>> errorsByJobId) {}

    JsonNode computeChunkWithRetry(
            CVAnalysis analysis, List<Job> chunk, Map<Long, String> jobFingerprints,
            String language, Semaphore concurrencyLimiter) {
        try {
            concurrencyLimiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return objectMapper.createArrayNode();
        }

        long jobId = chunk.isEmpty() ? -1 : chunk.get(0).getId();
        long waitStart = System.nanoTime();
        try {
            Map<Long, Job> jobById = chunk.stream().collect(Collectors.toMap(Job::getId, job -> job));

            long callStart = System.nanoTime();
            JsonNode firstAttempt = readMatchesArray(
                    openAICVAnalysisService.computeJobMatches(analysis, chunk, jobFingerprints, language, null));
            ValidatedBatch firstResult = validateBatch(firstAttempt, jobById, jobFingerprints, analysis);
            log.info("match-scores-timing jobId={} waitedForPermitMs={} firstCallMs={} firstValid={}",
                    jobId, (callStart - waitStart) / 1_000_000, (System.nanoTime() - callStart) / 1_000_000,
                    firstResult.allValid());

            if (firstResult.allValid()) {
                return toArrayNode(firstResult.validMatches());
            }

            String feedback = buildRetryFeedback(chunk, firstResult);
            long retryStart = System.nanoTime();
            JsonNode retryAttempt = readMatchesArray(
                    openAICVAnalysisService.computeJobMatches(analysis, chunk, jobFingerprints, language, feedback));
            ValidatedBatch retryResult = validateBatch(retryAttempt, jobById, jobFingerprints, analysis);
            log.info("match-scores-timing jobId={} retryCallMs={} retryValid={}",
                    jobId, (System.nanoTime() - retryStart) / 1_000_000, retryResult.allValid());

            // Keep whichever attempt validly covers more jobs - a fully-valid retry always wins,
            // and a still-partial retry is only worth using if it's a strict improvement over
            // the first attempt. Anything still uncovered falls through to ensureCoreScores'
            // honest "couldn't compute, please retry" sentinel rather than a guessed verdict.
            List<JsonNode> best = retryResult.validMatches().size() >= firstResult.validMatches().size()
                    ? retryResult.validMatches() : firstResult.validMatches();

            // A job still uncovered after both attempts falls through to the honest "couldn't
            // compute" sentinel (see ensureCoreScores) - but until now, WHY validation rejected
            // the AI's response was never logged anywhere, leaving zero visibility into a job
            // that's persistently failing to score. Logs the retry attempt's errors (the more
            // informed of the two, since it already reflects the first attempt's feedback) for
            // every job this chunk never managed to validate.
            ValidatedBatch finalResult = retryResult.validMatches().size() >= firstResult.validMatches().size()
                    ? retryResult : firstResult;
            for (Job job : chunk) {
                List<String> finalErrors = finalResult.errorsByJobId().get(job.getId());
                if (finalErrors != null && !finalErrors.isEmpty()) {
                    log.warn("match-validation-failed jobId={} title='{}' errors={}",
                            job.getId(), job.getTitle(), finalErrors);
                }
            }

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
    record ParsedMatch(
            long jobId,
            String jobTitle,
            String jobFingerprint,
            String fieldRelationCloseness,
            String matchReason,
            List<String> matchedMandatorySkills,
            // Fundamental skills the AI inferred from the candidate's documented profession/
            // education/experience rather than found literally in the CV text (e.g. Pharmacology
            // for a licensed doctor) - kept separate from matchedMandatorySkills/
            // matchedPreferredSkills so validateMatch can apply the stricter, distinct rules
            // inference requires (see NON_INFERABLE_SKILL_TERMS and MAX_INFERRED_SKILLS_PER_JOB)
            // instead of the plain literal-evidence check those two arrays get.
            List<String> matchedMandatorySkillsInferred,
            List<String> missingMandatorySkills,
            List<String> matchedPreferredSkills,
            List<String> matchedPreferredSkillsInferred,
            List<String> missingPreferredSkills,
            String requiredExperienceLevel,
            // Non-null only when the posting names a distinct experience sub-domain/type beyond
            // general seniority (e.g. "Clinical Research", "people management") - see
            // computeJobMatches' prompt. candidateHasRequiredExperienceType is null exactly when
            // this is null, otherwise true/false for whether the candidate's history shows real
            // evidence of THAT specific type, independent of whether they clear the seniority bar.
            String requiredExperienceType,
            Boolean candidateHasRequiredExperienceType,
            String requiredEducationLevel,
            String requiredCertificationLevel
    ) {}

    private static final Set<String> VALID_CLOSENESS =
            Set.of("same_role", "same_specialization", "same_broad_field", "unrelated");
    private static final Set<String> VALID_EXPERIENCE_LEVELS = Set.of("entry", "mid", "senior");
    private static final Set<String> VALID_EDUCATION_LEVELS = Set.of("any_degree", "relevant_degree");
    private static final Set<String> VALID_CERTIFICATION_LEVELS = Set.of("general_cert", "specific_license");

    // Skills that must NEVER be credited via fundamental-skill inference (matchedMandatorySkillsInferred/
    // matchedPreferredSkillsInferred) - only ever matched when the CV text itself evidences them.
    // Two categories: (1) named regulated/compliance/credentialing terms - a real license or
    // regulatory qualification is never a safe assumption just because someone works in an
    // adjacent field, and (2) generic certification/license phrasing - "certified"/"licensed"/
    // "accredited" always name a specific credential that either was earned and documented, or
    // wasn't; there is no "reasonably assumed" middle ground for a credential. Deliberately a
    // denylist of ENUMERABLE regulatory/credentialing terms, not an attempt to enumerate every
    // possible specific tool/framework/product (impossible to list exhaustively) - the prompt's
    // own "concept vs. named product" instruction carries that half of the guardrail instead.
    private static final List<String> NON_INFERABLE_SKILL_TERMS = List.of(
            "gmp", "good manufacturing practice", "regulatory affairs",
            "sterilization protocol", "hipaa", "iso 27001", "iso 9001", "iso 13485",
            "pci dss", "soc 2", "gdpr", "fda approv", "ce mark",
            "certified", "certification", "certificate",
            "license", "licensed", "licensing", "accredit", "board certified", "bar admission"
    );

    // Deliberately small - fundamental-skill inference is meant for a handful of genuinely
    // foundational basics (see the prompt's FUNDAMENTAL-SKILL INFERENCE RULE), never a way to
    // manufacture a passing skills score for a candidate the CV doesn't actually evidence.
    private static final int MAX_INFERRED_SKILLS_PER_JOB = 3;

    ParsedMatch parseMatch(JsonNode match) {
        // An unrecognized/missing closeness value defaults to "unrelated" (the safe, honest
        // failure mode) rather than silently treating it as related - validateMatch below still
        // rejects anything outside VALID_CLOSENESS outright, so this default only matters for
        // how a malformed response reads before validation catches it.
        String closeness = match.path("fieldRelationCloseness").asText("unrelated").trim().toLowerCase(Locale.ROOT);
        boolean fieldRelated = !"unrelated".equals(closeness);

        String requiredExperienceType = fieldRelated ? normalizeExperienceType(match.path("requiredExperienceType")) : null;
        JsonNode hasTypeNode = match.path("candidateHasRequiredExperienceType");
        Boolean candidateHasRequiredExperienceType = (requiredExperienceType == null || hasTypeNode.isNull() || hasTypeNode.isMissingNode())
                ? null
                : hasTypeNode.asBoolean(false);

        return new ParsedMatch(
                match.path("jobId").asLong(),
                match.path("jobTitle").asText(""),
                match.path("jobFingerprint").asText(""),
                closeness,
                match.path("matchReason").asText(""),
                fieldRelated ? toStringList(match.path("matchedMandatorySkills")) : List.of(),
                fieldRelated ? toStringList(match.path("matchedMandatorySkillsInferred")) : List.of(),
                fieldRelated ? toStringList(match.path("missingMandatorySkills")) : List.of(),
                fieldRelated ? toStringList(match.path("matchedPreferredSkills")) : List.of(),
                fieldRelated ? toStringList(match.path("matchedPreferredSkillsInferred")) : List.of(),
                fieldRelated ? toStringList(match.path("missingPreferredSkills")) : List.of(),
                normalizeEnum(match.path("requiredExperienceLevel")),
                requiredExperienceType,
                candidateHasRequiredExperienceType,
                normalizeEnum(match.path("requiredEducationLevel")),
                normalizeEnum(match.path("requiredCertificationLevel"))
        );
    }

    private String normalizeExperienceType(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        String text = node.asText("").trim();
        return text.isBlank() || "null".equalsIgnoreCase(text) ? null : text;
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

            // Inferred ("fundamental skill") matches get their OWN, stricter rules instead of the
            // literal-evidence check above - by definition they are NOT expected to appear in the
            // CV text. Three guardrails instead: (1) only trusted when this is the candidate's own
            // specific role/specialization, never a looser broad-field relation; (2) never a
            // regulated/credentialed term, which always needs real proof; (3) capped, so this can
            // never become the primary way a job scores well.
            boolean sameSpecificRoleForInference = "same_role".equals(parsed.fieldRelationCloseness())
                    || "same_specialization".equals(parsed.fieldRelationCloseness());
            List<String> allInferred = concat(parsed.matchedMandatorySkillsInferred(), parsed.matchedPreferredSkillsInferred());
            for (String skill : allInferred) {
                if (!sameSpecificRoleForInference) {
                    errors.add("inferred skill '" + skill + "' is only allowed when fieldRelationCloseness is "
                            + "same_role/same_specialization (got '" + parsed.fieldRelationCloseness() + "') - move it to "
                            + "a missing* array instead, or drop it, for a same_broad_field job.");
                    continue;
                }
                String normalizedSkill = skill.toLowerCase(Locale.ROOT);
                boolean isRegulatedTerm = NON_INFERABLE_SKILL_TERMS.stream().anyMatch(normalizedSkill::contains);
                if (isRegulatedTerm) {
                    errors.add("inferred skill '" + skill + "' names a specific certification/license/regulatory "
                            + "qualification, which can never be inferred without explicit CV evidence - move it to "
                            + "missingMandatorySkills/missingPreferredSkills unless the CV text actually shows it, in "
                            + "which case it belongs in the plain matchedMandatorySkills/matchedPreferredSkills array instead.");
                }
            }
            if (allInferred.size() > MAX_INFERRED_SKILLS_PER_JOB) {
                errors.add("too many inferred skills (" + allInferred.size() + ") - infer at most "
                        + MAX_INFERRED_SKILLS_PER_JOB + " combined, keeping only the ones you are most confident are "
                        + "a direct, fundamental consequence of the candidate's documented profession/education.");
            }

            // Cross-array consistency: the exact contradiction this whole validation step exists
            // to catch - the same skill treated as both a positive (matched) and a negative
            // (missing) at once, or double-listed across two "matched" arrays. Every one of the
            // six arrays above is checked individually for its OWN internal correctness, but
            // nothing before this compared them against EACH OTHER - a skill could independently
            // pass the "matched skill is evidenced in the CV" check AND the "missing skill is
            // evidenced in the job posting" check while appearing in both arrays, since those two
            // checks never look at each other's array. Normalizes case/whitespace only (not a
            // fuzzy/synonym match) - the AI is asked to reuse the EXACT posting wording for a given
            // requirement (see the prompt's "Use the EXACT wording" rule), so the same requirement
            // should produce character-for-character identical text between arrays, not just a
            // paraphrase; a near-miss here would be a sign the AI is inventing new wording, which
            // is its own problem, not something this specific check needs to also catch.
            Map<String, String> skillFirstSeenInArray = new LinkedHashMap<>();
            List<Map.Entry<String, List<String>>> allSkillArrays = List.of(
                    Map.entry("matchedMandatorySkills", parsed.matchedMandatorySkills()),
                    Map.entry("matchedMandatorySkillsInferred", parsed.matchedMandatorySkillsInferred()),
                    Map.entry("missingMandatorySkills", parsed.missingMandatorySkills()),
                    Map.entry("matchedPreferredSkills", parsed.matchedPreferredSkills()),
                    Map.entry("matchedPreferredSkillsInferred", parsed.matchedPreferredSkillsInferred()),
                    Map.entry("missingPreferredSkills", parsed.missingPreferredSkills())
            );
            for (Map.Entry<String, List<String>> arrayEntry : allSkillArrays) {
                for (String skill : arrayEntry.getValue()) {
                    String normalized = skill.toLowerCase(Locale.ROOT).trim();
                    if (normalized.isBlank()) {
                        continue;
                    }
                    String firstArray = skillFirstSeenInArray.get(normalized);
                    if (firstArray != null && !firstArray.equals(arrayEntry.getKey())) {
                        errors.add("skill '" + skill + "' appears in both " + firstArray + " and " + arrayEntry.getKey()
                                + " - the same skill can never be both matched and missing (or matched in two "
                                + "different ways) at once; keep it in exactly one array.");
                    } else {
                        skillFirstSeenInArray.put(normalized, arrayEntry.getKey());
                    }
                }
            }

            String jobBlob = jobRequirementsBlob(job);
            // Self-contradictory claim - found in production: a General Practitioner CV against a
            // job titled "doctor" was judged fieldRelationCloseness=same_role (correct) but then
            // ALSO listed "doctor" itself as a missing mandatory skill. If the AI has already
            // judged this job to BE the candidate's own specific role/specialization, the job's
            // own defining title cannot simultaneously be something the candidate is "missing" -
            // checked against the JOB's title, not the candidate's, since that's the profession
            // sameSpecificRole just asserted the candidate already holds.
            boolean sameSpecificRole = "same_role".equals(parsed.fieldRelationCloseness())
                    || "same_specialization".equals(parsed.fieldRelationCloseness());
            String normalizedJobTitle = normalizeForTitleComparison(nullToEmpty(job.getTitle()));

            for (String skill : concat(parsed.missingMandatorySkills(), parsed.missingPreferredSkills())) {
                if (!evidencedIn(skill, jobBlob)) {
                    errors.add("missing skill '" + skill + "' does not appear anywhere in this job's posting text.");
                    continue;
                }
                if (sameSpecificRole) {
                    String normalizedSkill = normalizeForTitleComparison(skill);
                    if (!normalizedJobTitle.isBlank() && !normalizedSkill.isBlank()
                            && (normalizedJobTitle.contains(normalizedSkill) || normalizedSkill.contains(normalizedJobTitle))) {
                        errors.add("missing skill '" + skill + "' is this job's own title/profession, which is "
                                + "self-contradictory given fieldRelationCloseness='" + parsed.fieldRelationCloseness()
                                + "' - the candidate cannot be missing the very role they were just judged to already hold.");
                    }
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

            // requiredExperienceType/candidateHasRequiredExperienceType must be null together or
            // set together (see the prompt) - and when set, both halves need real grounding: the
            // type itself must actually be named in the posting (never invented), and a claimed
            // "true" (candidate has this specific type) must be traceable to the candidate's own
            // profile text, exactly like a matched skill would be.
            if (parsed.requiredExperienceType() == null && parsed.candidateHasRequiredExperienceType() != null) {
                errors.add("candidateHasRequiredExperienceType must be null when requiredExperienceType is null.");
            } else if (parsed.requiredExperienceType() != null) {
                if (parsed.candidateHasRequiredExperienceType() == null) {
                    errors.add("candidateHasRequiredExperienceType must be true or false (not null) when "
                            + "requiredExperienceType is set to '" + parsed.requiredExperienceType() + "'.");
                }
                if (!evidencedIn(parsed.requiredExperienceType(), jobRequirementsBlob(job))) {
                    errors.add("requiredExperienceType '" + parsed.requiredExperienceType()
                            + "' does not appear anywhere in this job's posting text.");
                }
                if (Boolean.TRUE.equals(parsed.candidateHasRequiredExperienceType())
                        && !evidencedIn(parsed.requiredExperienceType(), candidateSkillsBlob(analysis))) {
                    errors.add("candidateHasRequiredExperienceType was true for '" + parsed.requiredExperienceType()
                            + "', but that specific experience type is not evidenced anywhere in the candidate's profile.");
                }

                // Double-counting guard: the same underlying gap must not depress BOTH the skills
                // score (via a missing-skill entry) AND the experience score (via the type-gap
                // blend in MatchScoreCalculator#scoreExperience) for what is really one
                // deficiency. Deliberately a strict FULL-PHRASE substring check in either
                // direction (e.g. "Clinical Research" as the type vs. "Clinical Research
                // experience" as the missing-skill string, a duplicate wording of the identical
                // requirement) - NOT evidencedIn's single-significant-word fallback, which real-
                // world testing showed was too aggressive here: a job requiring both "Clinical
                // Research experience" (the type) and a distinct skill like "Clinical trial
                // management" shares only the single word "clinical" between two genuinely
                // different requirements (general research experience vs. a specific operational
                // skill), and evidencedIn's word-overlap fallback flagged that shared domain word
                // as if the two were duplicates - rejecting an otherwise-valid response twice in a
                // row in production testing and leaving the candidate with no score at all for
                // that job. A missed soft-worded duplicate (a false negative here) only means one
                // gap is weighed in two components instead of one - a minor scoring imprecision,
                // and a strictly smaller harm than a validation failure that blocks the whole
                // computation and shows no score whatsoever.
                for (String missing : concat(parsed.missingMandatorySkills(), parsed.missingPreferredSkills())) {
                    String normalizedType = parsed.requiredExperienceType().toLowerCase(Locale.ROOT).trim();
                    String normalizedMissing = missing.toLowerCase(Locale.ROOT).trim();
                    if (!normalizedType.isBlank() && !normalizedMissing.isBlank()
                            && (normalizedMissing.contains(normalizedType) || normalizedType.contains(normalizedMissing))) {
                        errors.add("requiredExperienceType '" + parsed.requiredExperienceType() + "' overlaps with "
                                + "missing skill '" + missing + "' - the same gap must not be counted as both a "
                                + "missing skill AND a separate required-experience-type gap; keep it as the "
                                + "experience-type classification only and drop it from the missing skill arrays.");
                    }
                }
            }
        } else {
            if (parsed.matchReason() == null || parsed.matchReason().isBlank()) {
                errors.add("matchReason must explain the field mismatch (naming both the candidate's field and the job's field) when fieldRelationCloseness is 'unrelated'.");
            }
            if (!parsed.matchedMandatorySkills().isEmpty() || !parsed.matchedPreferredSkills().isEmpty()
                    || !parsed.matchedMandatorySkillsInferred().isEmpty() || !parsed.matchedPreferredSkillsInferred().isEmpty()
                    || !parsed.missingMandatorySkills().isEmpty() || !parsed.missingPreferredSkills().isEmpty()) {
                errors.add("skill arrays must be empty when fieldRelationCloseness is 'unrelated'.");
            }
            if (parsed.requiredExperienceType() != null) {
                errors.add("requiredExperienceType must be null when fieldRelationCloseness is 'unrelated'.");
            }
        }

        return errors;
    }

    // Also includes profession/education/previous-role context (not just the skills fields) -
    // needed so a legitimate matched-skill or experience-type claim grounded in "the candidate IS
    // a licensed doctor" or "previously worked as a Clinical Research Coordinator" can actually be
    // recognized as evidenced, not just claims grounded in the literal skills/summary text.
    private String candidateSkillsBlob(CVAnalysis analysis) {
        return String.join(" | ",
                nullToEmpty(analysis.getTechnicalSkills()),
                nullToEmpty(analysis.getSoftSkills()),
                nullToEmpty(analysis.getSkills()),
                nullToEmpty(analysis.getSummary()),
                nullToEmpty(analysis.getStrengths()),
                nullToEmpty(analysis.getProfessionTitle()),
                nullToEmpty(analysis.getCandidateField()),
                nullToEmpty(analysis.getPreviousJobTitles()),
                nullToEmpty(analysis.getEducationEvidence()),
                nullToEmpty(analysis.getCertificationsEvidence()),
                nullToEmpty(analysis.getLicensesEvidence())
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

    String fingerprintCv(CVAnalysis analysis) {
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
    String fingerprintJob(Job job) {
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
    String buildJobContentFingerprint(Job job, String jobContentHash) {
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
