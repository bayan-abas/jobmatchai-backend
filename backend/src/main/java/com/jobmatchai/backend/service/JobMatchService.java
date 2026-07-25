package com.jobmatchai.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.jobmatchai.backend.model.CVAnalysis;
import com.jobmatchai.backend.model.Job;
import com.jobmatchai.backend.model.JobMatchNarrative;
import com.jobmatchai.backend.model.JobMatchScore;
import com.jobmatchai.backend.repository.CVAnalysisRepository;
import com.jobmatchai.backend.repository.JobMatchNarrativeRepository;
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

    // להעלות מספר כדי לאלץ רענון של whyGoodMatch/whyNotPerfectMatch/recommendation גם כשה-CV והמשרה לא השתנו
    private static final int DETAIL_PROMPT_VERSION = 2;

    @Autowired
    private CVAnalysisRepository cvAnalysisRepository;

    @Autowired
    private JobMatchScoreRepository jobMatchScoreRepository;

    @Autowired
    private JobMatchNarrativeRepository jobMatchNarrativeRepository;

    @Autowired
    private com.jobmatchai.backend.repository.JobRepository jobRepository;

    @Autowired
    private NotificationService notificationService;

    private static final int HIGH_MATCH_NOTIFICATION_THRESHOLD = 80;

    @Autowired
    private OpenAICVAnalysisService openAICVAnalysisService;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private MatchScoreQueueService matchScoreQueueService;

    @Autowired
    private MatchMetrics matchMetrics;

    @Value("${matching.queue.await-timeout-ms:60000}")
    private long queueAwaitTimeoutMs;

    @Value("${matching.embedding.prefilter.enabled:true}")
    private boolean prefilterEnabled;

    @Value("${matching.embedding.prefilter.threshold:0.15}")
    private float prefilterThreshold;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // singleflight - אם שני requests מבקשים את אותו candidate+job בו-זמנית, שלא יריצו פעמיים קריאת AI
    private final ConcurrentHashMap<String, CompletableFuture<JobMatchScore>> inFlightComputations =
            new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Object> narrativeLocks = new ConcurrentHashMap<>();

    // לשנות את המחרוזת הזו בכל פעם שהלוגיקה של הניקוד משתנה - זה מה שמפיל את כל הקאש הישן
    private static final String MATCH_SCHEMA_VERSION = "v25-stricter-gibberish-detection";

    // בודק אם אפשר לחסוך קריאה ל-AI כי ה-embeddings כבר מראים שהמשרה רחוקה מדי מהפרופיל
    private boolean shouldSkipAiViaPrefilter(Job job, float[] profileVector, float[] jobVector) {
        if (!prefilterEnabled || profileVector == null || jobVector == null) {
            return false;
        }
        if (VocationalRoleClassifier.isGeneralVocationalRole(job.getTitle())) {
            return false;
        }
        return EmbeddingService.cosineSimilarity(profileVector, jobVector) < prefilterThreshold;
    }

    // דואג שלכל משרה פנימית יהיה embedding מחושב ושמור בקאש, ומחשב מחדש רק למשרות שהטקסט שלהן השתנה
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

        }

        return result;
    }

    // בונה את הטקסט שממנו מחשבים embedding למשרה (כותרת + תחילת התיאור)
    private String internalJobEmbeddingText(Job job) {
        String description = job.getDescription();
        if (description != null && description.length() > 1500) {
            description = description.substring(0, 1500);
        }
        return nullToEmpty(job.getTitle()) + ". " + nullToEmpty(description);
    }

    // בעליית השרת, משלים embeddings למשרות ישנות שעדיין אין להן אחד
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void backfillMissingInternalJobEmbeddingsOnStartup() {
        List<Job> missing = jobRepository.findByContentEmbeddingIsNull();
        if (missing.isEmpty()) {
            return;
        }
        ensureInternalJobEmbeddings(missing);
    }

    // ספי סף שנקבעו ידנית - מתחתיהם המשרה נחשבת "קצרה מדי" לניקוד אמין, אז לא שולחים אותה ל-AI בכלל
    private static final int MIN_REAL_SKILL_TERMS = 3;
    private static final int MIN_TOTAL_CONTENT_CHARS = 65;

    // בודק אם למשרה יש בכלל מספיק תוכן כדי לתת ציון התאמה אמין, לפני שמבזבזים עליה קריאת AI
    private boolean isInsufficientJobData(Job job) {
        String title = nullToEmpty(job.getTitle()).trim();
        String normalizedTitle = normalizeForTitleComparison(title);
        String description = nullToEmpty(job.getDescription()).trim();
        String requirements = nullToEmpty(job.getRequirements()).trim();
        String skills = nullToEmpty(job.getSkills()).trim();

        String descriptionBeyondTitle =
                normalizeForTitleComparison(description).equals(normalizedTitle) ? "" : description;

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

    private static final List<String> SUPPORTED_NARRATIVE_LANGUAGES = List.of("en", "ar", "he");

    private static String pickByLanguage(String language, String en, String ar, String he) {
        if ("ar".equalsIgnoreCase(language)) {
            return ar;
        }
        if ("he".equalsIgnoreCase(language)) {
            return he;
        }
        return en;
    }

    // שומר את אותה סיבת-התאמה (מתורגמת לשלוש השפות) בלי לקרוא ל-AI, למקרים שבהם התשובה נקבעת דטרמיניסטית
    private void seedDeterministicNarrative(String email, long jobId, String cvFingerprint, String jobFingerprint,
            java.util.function.Function<String, String> reasonForLanguage) {
        for (String lang : SUPPORTED_NARRATIVE_LANGUAGES) {
            JobMatchNarrative narrative = jobMatchNarrativeRepository
                    .findByCandidateEmailAndJobIdAndLanguage(email, jobId, lang)
                    .orElse(new JobMatchNarrative());
            narrative.setCandidateEmail(email);
            narrative.setJobId(jobId);
            narrative.setLanguage(lang);
            narrative.setMatchReason(reasonForLanguage.apply(lang));
            narrative.setCvFingerprint(cvFingerprint);
            narrative.setJobFingerprint(jobFingerprint);
            jobMatchNarrativeRepository.save(narrative);
        }
    }

    // ממלא את שורת הציון במצב "אין מספיק מידע" ומאפס את כל שדות הניקוד
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

        seedDeterministicNarrative(email, jobId, cvFingerprint, jobFingerprint, lang -> pickByLanguage(lang,
                "Not enough job information to calculate a reliable match.",
                "لا تتوفر معلومات كافية عن الوظيفة لحساب نسبة تطابق موثوقة.",
                "אין מספיק מידע על המשרה כדי לחשב התאמה אמינה."));
    }

    // בודק אם המקצוע של המועמד תואם למקצוע הנדרש במשרה, לפי טקסונומיית המקצועות
    ProfessionTaxonomy.CompatibilityTier checkProfessionCompatibility(CVAnalysis analysis, Job job) {
        if (VocationalRoleClassifier.isGeneralVocationalRole(job.getTitle())) {
            return ProfessionTaxonomy.CompatibilityTier.UNKNOWN;
        }

        ProfessionTaxonomy.ProfessionNode jobProfession = ProfessionTaxonomy.resolve(job.getTitle());
        if (jobProfession == null) {
            return ProfessionTaxonomy.CompatibilityTier.UNKNOWN;
        }

        Set<ProfessionTaxonomy.ProfessionNode> candidateProfessions = ProfessionTaxonomy.resolveAll(
                analysis.getProfessionTitle(), analysis.getPreviousJobTitles(), analysis.getRecommendedRoles());
        if (candidateProfessions.isEmpty()) {
            return ProfessionTaxonomy.CompatibilityTier.UNKNOWN;
        }

        return ProfessionTaxonomy.classifyBest(candidateProfessions, jobProfession);
    }

    // קובע אילו רמות אי-התאמה מקצועית חוסמות לגמרי את ההתאמה (רישוי שונה, או תחום לא קשור בכלל)
    private static boolean isHardBlockedProfessionTier(ProfessionTaxonomy.CompatibilityTier tier) {
        return tier == ProfessionTaxonomy.CompatibilityTier.DIFFERENT_LICENSED_PROFESSION
                || tier == ProfessionTaxonomy.CompatibilityTier.UNRELATED;
    }

    // בונה תוצאה סינתטית של "לא מתאים" כשהמקצועות לא תואמים, כולל הסבר בשלוש השפות בלי לקרוא ל-AI
    private void applyProfessionIncompatibleVerdict(
            JobMatchScore score, Job job, CVAnalysis analysis, String email, long jobId,
            String cvFingerprint, String jobFingerprint, String jobContentFingerprint,
            ProfessionTaxonomy.CompatibilityTier tier) {

        ProfessionTaxonomy.ProfessionNode jobProfession = ProfessionTaxonomy.resolve(job.getTitle());
        String candidateProfessionLabel = analysis.getProfessionTitle() != null && !analysis.getProfessionTitle().isBlank()
                ? analysis.getProfessionTitle()
                : nullToEmpty(analysis.getCandidateField());
        String jobProfessionLabel = jobProfession != null ? jobProfession.displayName() : nullToEmpty(job.getTitle());

        boolean licensed = tier == ProfessionTaxonomy.CompatibilityTier.DIFFERENT_LICENSED_PROFESSION;
        String reason = licensed
                ? "Your background is in " + candidateProfessionLabel + ", and this " + jobProfessionLabel
                        + " role requires its own separate professional license or credential - the two are "
                        + "different regulated professions, so experience in one does not carry over to practicing the other."
                : "Your background is in " + candidateProfessionLabel + ", and this " + jobProfessionLabel
                        + " role is a different profession - even though it may share an industry or a few "
                        + "keywords with your field, the core job itself calls for different training and experience.";

        ParsedMatch synthetic = new ParsedMatch(
                jobId, job.getTitle(), jobContentFingerprint, false, "unrelated", reason,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null, null, null, null);

        applyParsedMatchToScore(score, synthetic, job, analysis, email, jobId,
                cvFingerprint, jobFingerprint, jobContentFingerprint);

        seedDeterministicNarrative(email, jobId, cvFingerprint, jobFingerprint, lang -> licensed
                ? pickByLanguage(lang,
                        "Your background is in " + candidateProfessionLabel + ", and this " + jobProfessionLabel
                                + " role requires its own separate professional license or credential - the two are "
                                + "different regulated professions, so experience in one does not carry over to practicing the other.",
                        "خلفيتك المهنية في " + candidateProfessionLabel + "، وهذه الوظيفة كـ " + jobProfessionLabel
                                + " تتطلب ترخيصًا أو شهادة مهنية منفصلة خاصة بها - فهما مهنتان مرخصتان مختلفتان، "
                                + "والخبرة في إحداهما لا تنتقل تلقائيًا لممارسة الأخرى.",
                        "הרקע שלך הוא ב" + candidateProfessionLabel + ", ותפקיד " + jobProfessionLabel
                                + " דורש רישיון או הסמכה מקצועית נפרדת משלו - מדובר בשני מקצועות מוסדרים שונים, "
                                + "כך שניסיון באחד אינו עובר אוטומטית לעיסוק בשני.")
                : pickByLanguage(lang,
                        "Your background is in " + candidateProfessionLabel + ", and this " + jobProfessionLabel
                                + " role is a different profession - even though it may share an industry or a few "
                                + "keywords with your field, the core job itself calls for different training and experience.",
                        "خلفيتك المهنية في " + candidateProfessionLabel + "، وهذه الوظيفة كـ " + jobProfessionLabel
                                + " هي مهنة مختلفة - وعلى الرغم من أنها قد تشترك في نفس المجال أو بعض الكلمات المفتاحية "
                                + "مع تخصصك، إلا أن جوهر هذه الوظيفة يتطلب تدريبًا وخبرة مختلفين.",
                        "הרקע שלך הוא ב" + candidateProfessionLabel + ", ותפקיד " + jobProfessionLabel
                                + " הוא מקצוע שונה - למרות ששניהם עשויים לשתף תעשייה או כמה מילות מפתח עם התחום שלך, "
                                + "מהות התפקיד דורשת הכשרה וניסיון שונים."));
    }

    // בונה תוצאה סינתטית של "לא רלוונטי" כשה-embedding כבר מראה שהמשרה רחוקה מדי מהפרופיל, בלי לקרוא ל-AI
    private void applyPrefilteredUnrelatedVerdict(
            JobMatchScore score, Job job, CVAnalysis analysis, String email, long jobId,
            String cvFingerprint, String jobFingerprint, String jobContentFingerprint) {

        ParsedMatch synthetic = new ParsedMatch(
                jobId, job.getTitle(), jobContentFingerprint, false, "unrelated",
                "Based on your profile, this role appears to be in a different field.",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null, null, null, null);

        applyParsedMatchToScore(score, synthetic, job, analysis, email, jobId,
                cvFingerprint, jobFingerprint, jobContentFingerprint);

        seedDeterministicNarrative(email, jobId, cvFingerprint, jobFingerprint, lang -> pickByLanguage(lang,
                "Based on your profile, this role appears to be in a different field.",
                "استنادًا إلى ملفك الشخصي، يبدو أن هذه الوظيفة في مجال مختلف.",
                "בהתבסס על הפרופיל שלך, נראה שתפקיד זה שייך לתחום אחר."));
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

            String requiredExperienceLevel,

            String requiredExperienceType,
            Boolean candidateHasRequiredExperienceType,
            String requiredEducationLevel,
            String requiredCertificationLevel,

            java.time.LocalDateTime lastAnalyzedAt,

            List<String> matchedRequiredSkills,
            List<String> matchedPreferredSkills
    ) {}

    // נקודת הכניסה הראשית לקבלת רשימת ציוני התאמה למשתמש עבור רשימת משרות (המסלול הלא-streaming)
    public MatchScoresResult getMatchScores(String email, List<Job> jobs, String language) {
        CVAnalysis analysis = cvAnalysisRepository.findByUserEmail(email).orElse(null);

        if (analysis == null) {
            return new MatchScoresResult(false, List.of());
        }

        if (jobs == null || jobs.isEmpty()) {
            return new MatchScoresResult(true, List.of());
        }

        Map<Long, JobMatchScore> cachedByJobId = ensureCoreScores(email, jobs, language, analysis);

        List<Long> jobIds = jobs.stream().map(Job::getId).toList();
        Map<Long, JobMatchNarrative> narrativeByJobId = new HashMap<>();
        for (JobMatchNarrative narrative : jobMatchNarrativeRepository.findByCandidateEmailAndJobIdInAndLanguage(email, jobIds, language)) {
            narrativeByJobId.put(narrative.getJobId(), narrative);
        }

        List<Map<String, Object>> matches = new ArrayList<>();
        for (Job job : jobs) {
            JobMatchScore score = cachedByJobId.get(job.getId());
            if (score == null) {
                continue;
            }
            String matchReason = resolveMatchReason(email, score, language, narrativeByJobId.get(job.getId()));
            matches.add(scoreToPayload(score, job, matchReason));
        }

        return new MatchScoresResult(true, matches);
    }

    // ממיר אובייקט ציון שמור למפה שמוחזרת ל-frontend, כולל פירוק רשימות המיומנויות מהמחרוזת המאוחסנת
    private Map<String, Object> scoreToPayload(JobMatchScore score, Job job, String resolvedMatchReason) {
        Map<String, Object> match = new LinkedHashMap<>();
        match.put("jobId", score.getJobId());

        match.put("fieldRelated", score.getFieldRelated());
        match.put("matchPercent", score.getMatchPercent());
        match.put("matchReason", resolvedMatchReason);
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

        boolean vocational = VocationalRoleClassifier.isGeneralVocationalRole(job.getTitle());
        match.put("generalVocationalRole", vocational);
        match.put("excludedFromListing", !vocational && Boolean.FALSE.equals(score.getFieldRelated()));

        match.put("stale", score.isStale());
        return match;
    }

    // בונה payload גנרי של "שגיאה בחישוב" כשלא הצלחנו לחשב או לשלוף ציון בכלל
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

        match.put("generalVocationalRole", VocationalRoleClassifier.isGeneralVocationalRole(job.getTitle()));
        match.put("excludedFromListing", false);
        match.put("stale", false);
        return match;
    }

    public boolean hasAnalysis(String email) {
        return cvAnalysisRepository.findByUserEmail(email).isPresent();
    }

    // המסלול המרכזי שמחשב ציוני התאמה בזרימה (streaming) - מחזיר תוצאות מהקאש/מהכללים הדטרמיניסטיים מיד, ושולח לחישוב AI רק את מה שבאמת צריך
    public void computeMatchScoresStreaming(
            String email, List<Job> jobs, String language, Map<Long, float[]> jobEmbeddings,
            String jobType, BiConsumer<Long, Map<String, Object>> onJobResult, Runnable onComplete) {

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
                    onJobResult.accept(job.getId(), scoreToPayload(cached, job, resolveMatchReason(email, cached, language, null)));
                } else if (isInsufficientJobData(job)) {
                    matchMetrics.recordInsufficientData();
                    JobMatchScore score = cachedByJobId.getOrDefault(job.getId(), new JobMatchScore());
                    applyInsufficientDataVerdict(score, email, job.getId(),
                            cvFingerprint, jobFingerprint, jobContentFingerprints.get(job.getId()));
                    score = jobMatchScoreRepositorySafeSave(score, email, job.getId());
                    onJobResult.accept(job.getId(), scoreToPayload(score, job, resolveMatchReason(email, score, language, null)));
                } else {
                    ProfessionTaxonomy.CompatibilityTier tier = checkProfessionCompatibility(analysis, job);
                    if (isHardBlockedProfessionTier(tier)) {
                        matchMetrics.recordProfessionIncompatible();
                        JobMatchScore score = cachedByJobId.getOrDefault(job.getId(), new JobMatchScore());
                        applyProfessionIncompatibleVerdict(score, job, analysis, email, job.getId(),
                                cvFingerprint, jobFingerprint, jobContentFingerprints.get(job.getId()), tier);
                        score = jobMatchScoreRepositorySafeSave(score, email, job.getId());
                        onJobResult.accept(job.getId(), scoreToPayload(score, job, resolveMatchReason(email, score, language, null)));
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

        Map<Long, float[]> effectiveEmbeddings = jobEmbeddings == null
                ? new HashMap<>() : new HashMap<>(jobEmbeddings);
        if ("internal".equals(jobType)) {
            effectiveEmbeddings.putAll(ensureInternalJobEmbeddings(jobsNeedingComputation));
        }

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
                onJobResult.accept(job.getId(), scoreToPayload(score, job, resolveMatchReason(email, score, language, null)));
            } else {
                jobsSentToAi.add(job);
            }
        }

        if (jobsSentToAi.isEmpty()) {
            onComplete.run();
            return;
        }

        AtomicInteger remaining = new AtomicInteger(jobsSentToAi.size());

        for (Job job : jobsSentToAi) {
            long jobId = job.getId();
            String jobFingerprint = jobFingerprints.get(jobId);

            JobMatchScore previousScore = cachedByJobId.get(jobId);

            matchScoreQueueService.enqueueIfNeeded(email, job, jobType, language, cvFingerprint, jobFingerprint);
            CompletableFuture<JobMatchScore> future = matchScoreQueueService.awaitResult(
                    email, jobId, jobType, cvFingerprint, jobFingerprint, queueAwaitTimeoutMs);

            future.whenComplete((score, ex) -> {
                Float similarity = similarityByJobId.get(jobId);
                if (similarity != null) {

                    Boolean fieldRelated = score != null ? score.getFieldRelated() : null;
                    log.info("prefilter-shadow email={} jobId={} similarity={} fieldRelated={}",
                            email, jobId, similarity, fieldRelated);
                }

                Map<String, Object> payload;
                if (score != null) {
                    payload = scoreToPayload(score, job, resolveMatchReason(email, score, language, null));
                } else if (previousScore != null && previousScore.getFieldRelated() != null) {

                    previousScore.setStale(true);
                    payload = scoreToPayload(previousScore, job,
                            resolveMatchReason(email, previousScore, language, null));
                    log.info("match-scores-streaming candidate={} jobId={} recompute failed - serving stale "
                            + "cached score instead of an error", email, jobId);
                } else {
                    payload = errorPayload(jobId, job);
                }
                onJobResult.accept(jobId, payload);

                if (remaining.decrementAndGet() == 0) {
                    onComplete.run();
                }
            });
        }
        log.info("match-scores-streaming candidate={} dispatchMs={}", email, (System.nanoTime() - methodStart) / 1_000_000);
    }

    // מריץ בפועל את חישוב ההתאמה מול ה-AI למשרה בודדת, עם singleflight כדי שלא ירוצו שתי בקשות זהות בו-זמנית
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

                CVAnalysis currentAnalysis = cvAnalysisRepository.findByUserEmail(email).orElse(null);
                if (currentAnalysis == null || !cvFingerprint.equals(fingerprintCv(currentAnalysis))) {
                    log.info("match-scores-timing jobId={} candidate={} -> CV changed mid-computation, discarding stale result",
                            job.getId(), email);
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

    // מוצא בתוך מערך התשובות של ה-AI את האובייקט שמתאים ל-jobId המבוקש
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

    // מוודא שלכל המשרות ברשימה יש ציון עדכני בקאש - קודם מסנן לפי הכללים הדטרמיניסטיים, ורק את השאר שולח ל-AI במקביל
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
                JobMatchScore saved = jobMatchScoreRepositorySafeSave(score, email, job.getId());
                cachedByJobId.put(job.getId(), saved);
            } else if (isStale && isHardBlockedProfessionTier(checkProfessionCompatibility(analysis, job))) {
                ProfessionTaxonomy.CompatibilityTier tier = checkProfessionCompatibility(analysis, job);
                matchMetrics.recordProfessionIncompatible();
                JobMatchScore score = cachedByJobId.getOrDefault(job.getId(), new JobMatchScore());
                applyProfessionIncompatibleVerdict(score, job, analysis, email, job.getId(),
                        cvFingerprint, jobFingerprint, jobContentFingerprints.get(job.getId()), tier);
                JobMatchScore saved = jobMatchScoreRepositorySafeSave(score, email, job.getId());
                cachedByJobId.put(job.getId(), saved);
            } else if (isStale) {
                jobsNeedingComputation.add(job);
            }
        }

        log.info("match-scores-timing candidate={} totalJobs={} cacheHits={} needingComputation={} dbReadMs={}",
                email, jobs.size(), jobs.size() - jobsNeedingComputation.size(), jobsNeedingComputation.size(), dbReadMs);

        if (!jobsNeedingComputation.isEmpty()) {
            long aiPhaseStart = System.nanoTime();
            Semaphore limiter = new Semaphore(MAX_CONCURRENT_MATCH_CALLS);

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

                        log.error("Failed to compute match score for candidate {} / job {}", email, job.getId(), e);
                        score = null;
                    }

                    if (score != null) {
                        cachedByJobId.put(job.getId(), score);
                    } else {

                        JobMatchScore previous = cachedByJobId.get(job.getId());
                        if (previous != null && previous.getFieldRelated() != null) {
                            previous.setStale(true);

                        } else {

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
            }

            long aiPhaseMs = (System.nanoTime() - aiPhaseStart) / 1_000_000;
            log.info("match-scores-timing candidate={} aiPhaseMs={} jobsComputed={} avgMsPerJob={}",
                    email, aiPhaseMs, jobsNeedingComputation.size(),
                    jobsNeedingComputation.isEmpty() ? 0 : aiPhaseMs / jobsNeedingComputation.size());
        }

        log.info("match-scores-timing candidate={} totalMs={}", email, (System.nanoTime() - methodStart) / 1_000_000);
        return cachedByJobId;
    }

    // שולח התראה למועמד אם הציון שהתקבל גבוה מספיק כדי להיחשב "התאמה מצוינת"
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

    // שומר את הציון, ואם יש התנגשות עם שמירה מקבילה על אותה שורה - פשוט מחזיר את מה שכבר נשמר במקום לקרוס
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

    // הלב של האלגוריתם - ממיר את התשובה הגולמית של ה-AI לציון סופי: קובע רלוונטיות תחום, מתאם מיומנויות מול ה-CV ומחשב את כל רכיבי הניקוד המשוקללים
    void applyParsedMatchToScore(
            JobMatchScore score, ParsedMatch parsed, Job job, CVAnalysis analysis, String email, long jobId,
            String cvFingerprint, String jobFingerprint, String jobContentFingerprint) {

        if (parsed.postingLacksRealContent()) {
            applyInsufficientDataVerdict(score, email, jobId, cvFingerprint, jobFingerprint, jobContentFingerprint);
            return;
        }

        score.setInsufficientData(false);

        String effectiveCloseness = parsed.fieldRelationCloseness();
        String overrideMatchReason = null;

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

        String candidateBlob = candidateSkillsBlob(analysis);
        List<String> matchedRequired = new ArrayList<>(concat(parsed.matchedMandatorySkills(), parsed.matchedMandatorySkillsInferred()));
        List<String> matchedPreferred = new ArrayList<>(concat(parsed.matchedPreferredSkills(), parsed.matchedPreferredSkillsInferred()));
        List<String> missingRequired = reconcileAgainstCandidateText(parsed.missingMandatorySkills(), candidateBlob, matchedRequired);
        List<String> missingPreferred = reconcileAgainstCandidateText(parsed.missingPreferredSkills(), candidateBlob, matchedPreferred);
        List<String> allMatched = concat(matchedRequired, matchedPreferred);
        List<String> allMissing = concat(missingRequired, missingPreferred);

        Integer skillsScore = MatchScoreCalculator.computeSkillsScore(
                matchedRequired.size(), missingRequired.size(),
                matchedPreferred.size(), missingPreferred.size());

        boolean isVocationalRole = VocationalRoleClassifier.isGeneralVocationalRole(job.getTitle());
        String requiredEducationLevel = isVocationalRole ? null : parsed.requiredEducationLevel();

        boolean sameSpecificRole = "same_role".equals(effectiveCloseness) || "same_specialization".equals(effectiveCloseness);

        Integer fieldRelevanceScore = MatchScoreCalculator.scoreFieldRelevance(effectiveCloseness);

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
        score.setMissingRequiredSkills(String.join("|", missingRequired));
        score.setMissingPreferredSkills(String.join("|", missingPreferred));
        score.setFieldRelevancePercent(weighted.componentPercents().get(ComponentKey.FIELD_RELEVANCE));
        score.setSkillsMatchPercent(weighted.componentPercents().get(ComponentKey.REQUIRED_SKILLS));
        score.setExperienceMatchPercent(weighted.componentPercents().get(ComponentKey.EXPERIENCE));
        score.setEducationMatchPercent(weighted.componentPercents().get(ComponentKey.EDUCATION));
        score.setCertificationMatchPercent(weighted.componentPercents().get(ComponentKey.CERTIFICATION));
        score.setLocationMatchPercent(weighted.componentPercents().get(ComponentKey.LOCATION));
    }

    // בודק אם מיומנות שה-AI סימן כ"חסרה" בעצם כן מוזכרת בטקסט המועמד, ומעביר אותה לרשימת ה"תואם" אם כן
    private List<String> reconcileAgainstCandidateText(List<String> missingSkills, String candidateBlob, List<String> promoteInto) {
        List<String> stillMissing = new ArrayList<>();
        for (String skill : missingSkills) {
            if (SkillClaimMatcher.mentionsSkill(candidateBlob, skill)) {
                promoteInto.add(skill);
            } else {
                stillMissing.add(skill);
            }
        }
        return stillMissing;
    }

    private record ResolvedNarrative(
            String matchReason, List<String> whyGoodMatch, List<String> whyNotPerfectMatch,
            List<String> improvementSuggestions, String recommendation) {}

    // שומר בקאש את הנרטיב בשפת המקור (השפה שבה חושב ה-AI), כדי שלא נצטרך לתרגם אותה חזרה לעצמה
    private void seedNativeNarrative(String email, JobMatchScore core, String language, boolean includeDetailFields) {
        JobMatchNarrative narrative = jobMatchNarrativeRepository
                .findByCandidateEmailAndJobIdAndLanguage(email, core.getJobId(), language)
                .orElse(new JobMatchNarrative());
        narrative.setCandidateEmail(email);
        narrative.setJobId(core.getJobId());
        narrative.setLanguage(language);
        narrative.setMatchReason(core.getMatchReason());
        narrative.setCvFingerprint(core.getCvFingerprint());
        narrative.setJobFingerprint(core.getJobFingerprint());
        if (includeDetailFields) {
            narrative.setWhyGoodMatch(core.getWhyGoodMatch());
            narrative.setWhyNotPerfectMatch(core.getWhyNotPerfectMatch());
            narrative.setImprovementSuggestions(core.getImprovementSuggestions());
            narrative.setRecommendation(core.getRecommendation());
            narrative.setDetailPromptVersion(core.getDetailPromptVersion());
        }
        jobMatchNarrativeRepository.save(narrative);
    }

    // מחזיר את הפירוט המתורגם (why good match / suggestions / recommendation) מהקאש אם הוא עדכני, אחרת מתרגם עם AI ושומר לפעם הבאה
    private ResolvedNarrative resolveDetailNarrative(String email, JobMatchScore core, String language) {
        String lockKey = email + "::" + core.getJobId() + "::" + language;
        Object lock = narrativeLocks.computeIfAbsent(lockKey, k -> new Object());

        synchronized (lock) {
            JobMatchNarrative cached = jobMatchNarrativeRepository
                    .findByCandidateEmailAndJobIdAndLanguage(email, core.getJobId(), language)
                    .orElse(null);

            boolean detailFieldsPresent = cached != null
                    && cached.getRecommendation() != null && !cached.getRecommendation().isBlank();

            boolean fresh = detailFieldsPresent
                    && java.util.Objects.equals(core.getCvFingerprint(), cached.getCvFingerprint())
                    && java.util.Objects.equals(core.getJobFingerprint(), cached.getJobFingerprint())
                    && java.util.Objects.equals(core.getDetailPromptVersion(), cached.getDetailPromptVersion());

            if (fresh) {
                log.info("match-narrative-detail candidate={} jobId={} language={} -> cache HIT; OpenAI NOT called",
                        email, core.getJobId(), language);
                return new ResolvedNarrative(
                        cached.getMatchReason() != null ? cached.getMatchReason() : core.getMatchReason(),
                        splitSkillsString(cached.getWhyGoodMatch()),
                        splitSkillsString(cached.getWhyNotPerfectMatch()),
                        splitSkillsString(cached.getImprovementSuggestions()),
                        cached.getRecommendation());
            }

            log.info("match-narrative-detail candidate={} jobId={} language={} -> cache MISS ({}); translating now",
                    email, core.getJobId(), language,
                    cached == null ? "no translation saved yet" : "content or detail-prompt version changed since last translation");

            String translated = openAICVAnalysisService.translateJobMatchNarrative(
                    core.getMatchReason(),
                    splitSkillsString(core.getWhyGoodMatch()),
                    splitSkillsString(core.getWhyNotPerfectMatch()),
                    splitSkillsString(core.getImprovementSuggestions()),
                    core.getRecommendation(),
                    language);
            JsonNode json = readDetailObject(translated);

            String matchReason = json != null ? json.path("matchReason").asText(core.getMatchReason()) : core.getMatchReason();
            List<String> whyGoodMatch = json != null && json.has("whyGoodMatch")
                    ? toStringList(json.path("whyGoodMatch")) : splitSkillsString(core.getWhyGoodMatch());
            List<String> whyNotPerfectMatch = json != null && json.has("whyNotPerfectMatch")
                    ? toStringList(json.path("whyNotPerfectMatch")) : splitSkillsString(core.getWhyNotPerfectMatch());
            List<String> improvementSuggestions = json != null && json.has("improvementSuggestions")
                    ? toStringList(json.path("improvementSuggestions")) : splitSkillsString(core.getImprovementSuggestions());
            String recommendation = json != null ? json.path("recommendation").asText(core.getRecommendation()) : core.getRecommendation();

            JobMatchNarrative narrative = cached != null ? cached : new JobMatchNarrative();
            narrative.setCandidateEmail(email);
            narrative.setJobId(core.getJobId());
            narrative.setLanguage(language);
            narrative.setMatchReason(matchReason);
            narrative.setWhyGoodMatch(String.join("|", whyGoodMatch));
            narrative.setWhyNotPerfectMatch(String.join("|", whyNotPerfectMatch));
            narrative.setImprovementSuggestions(String.join("|", improvementSuggestions));
            narrative.setRecommendation(recommendation);
            narrative.setCvFingerprint(core.getCvFingerprint());
            narrative.setJobFingerprint(core.getJobFingerprint());
            narrative.setDetailPromptVersion(core.getDetailPromptVersion());
            jobMatchNarrativeRepository.save(narrative);

            log.info("match-narrative-detail candidate={} jobId={} language={} -> translated and saved",
                    email, core.getJobId(), language);

            return new ResolvedNarrative(matchReason, whyGoodMatch, whyNotPerfectMatch, improvementSuggestions, recommendation);
        }
    }

    // מחזיר את סיבת ההתאמה בשפה המבוקשת - מהקאש אם קיים ועדכני, אחרת מתרגם עם AI ושומר
    private String resolveMatchReason(String email, JobMatchScore core, String language, JobMatchNarrative preloaded) {
        if (core.getMatchReason() == null || core.getMatchReason().isBlank()) {
            return core.getMatchReason();
        }

        boolean preloadedFresh = preloaded != null
                && preloaded.getMatchReason() != null && !preloaded.getMatchReason().isBlank()
                && java.util.Objects.equals(core.getCvFingerprint(), preloaded.getCvFingerprint())
                && java.util.Objects.equals(core.getJobFingerprint(), preloaded.getJobFingerprint());

        if (preloadedFresh) {
            return preloaded.getMatchReason();
        }

        String lockKey = email + "::" + core.getJobId() + "::" + language;
        Object lock = narrativeLocks.computeIfAbsent(lockKey, k -> new Object());

        synchronized (lock) {
            JobMatchNarrative cached = preloaded != null ? preloaded : jobMatchNarrativeRepository
                    .findByCandidateEmailAndJobIdAndLanguage(email, core.getJobId(), language)
                    .orElse(null);

            boolean fresh = cached != null
                    && cached.getMatchReason() != null && !cached.getMatchReason().isBlank()
                    && java.util.Objects.equals(core.getCvFingerprint(), cached.getCvFingerprint())
                    && java.util.Objects.equals(core.getJobFingerprint(), cached.getJobFingerprint());

            if (fresh) {
                return cached.getMatchReason();
            }

            String translated = openAICVAnalysisService.translateJobMatchNarrative(
                    core.getMatchReason(), List.of(), List.of(), List.of(), null, language);
            JsonNode json = readDetailObject(translated);
            String matchReason = json != null ? json.path("matchReason").asText(core.getMatchReason()) : core.getMatchReason();

            JobMatchNarrative narrative = cached != null ? cached : new JobMatchNarrative();
            narrative.setCandidateEmail(email);
            narrative.setJobId(core.getJobId());
            narrative.setLanguage(language);
            narrative.setMatchReason(matchReason);
            narrative.setCvFingerprint(core.getCvFingerprint());
            narrative.setJobFingerprint(core.getJobFingerprint());
            jobMatchNarrativeRepository.save(narrative);

            return matchReason;
        }
    }

    // נקודת הכניסה לעמוד הפירוט של משרה בודדת - מוודא ציון ליבה, ואז שולף/מייצר עם AI את ההסברים המפורטים (why good/not perfect, המלצה)
    public MatchDetailResult getMatchDetail(String email, Job job, String language) {
        CVAnalysis analysis = cvAnalysisRepository.findByUserEmail(email).orElse(null);

        if (analysis == null) {
            return new MatchDetailResult(false, job.getId(), null, null, List.of(), List.of(), List.of(), List.of(), List.of(),
                    null, null, null, null, null, null, null, null, null, null, List.of(), List.of(),
                    null, null, null, null, null, null, List.of(), List.of());
        }

        JobMatchScore core = ensureCoreScores(email, List.of(job), language, analysis).get(job.getId());

        if (core == null || core.getFieldRelated() == null) {

            String reason = core != null ? core.getMatchReason() : null;
            JobMatchNarrative localizedInsufficient = core != null ? jobMatchNarrativeRepository
                    .findByCandidateEmailAndJobIdAndLanguage(email, job.getId(), language)
                    .orElse(null) : null;
            boolean localizedFresh = localizedInsufficient != null
                    && localizedInsufficient.getMatchReason() != null && !localizedInsufficient.getMatchReason().isBlank()
                    && java.util.Objects.equals(core.getCvFingerprint(), localizedInsufficient.getCvFingerprint())
                    && java.util.Objects.equals(core.getJobFingerprint(), localizedInsufficient.getJobFingerprint());
            if (localizedFresh) {
                reason = localizedInsufficient.getMatchReason();
            }
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

            JobMatchNarrative localizedUnrelated = jobMatchNarrativeRepository
                    .findByCandidateEmailAndJobIdAndLanguage(email, job.getId(), language)
                    .orElse(null);
            boolean localizedFresh = localizedUnrelated != null
                    && localizedUnrelated.getMatchReason() != null && !localizedUnrelated.getMatchReason().isBlank()
                    && java.util.Objects.equals(core.getCvFingerprint(), localizedUnrelated.getCvFingerprint())
                    && java.util.Objects.equals(core.getJobFingerprint(), localizedUnrelated.getJobFingerprint());
            String unrelatedReason = localizedFresh ? localizedUnrelated.getMatchReason() : core.getMatchReason();

            return new MatchDetailResult(true, job.getId(), null, unrelatedReason,
                    List.of(), List.of(), List.of(), List.of(), List.of(),
                    "A meaningful evaluation isn't possible for this job given the field mismatch.",
                    false, false, null, null, null, null, null, null, null, List.of(), List.of(),
                    null, null, null, null, null, core.getUpdatedAt(), List.of(), List.of());
        }

        boolean detailStale = core.getRecommendation() == null || core.getRecommendation().isBlank()
                || core.getDetailPromptVersion() == null || core.getDetailPromptVersion() != DETAIL_PROMPT_VERSION;

        if (detailStale) {
            String result = openAICVAnalysisService.computeJobMatchDetail(
                    analysis, job, language, core.getMatchPercent(), matchedSkills, missingSkills,
                    core.getRequiredExperienceType(), core.getCandidateHasRequiredExperienceType());
            JsonNode json = readDetailObject(result);

            if (json != null) {
                core.setLanguageMatchPercent(json.has("languageMatchPercent")
                        ? MatchScoreCalculator.clamp(json.path("languageMatchPercent").asInt())
                        : null);

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

                String recommendation = json.path("recommendation").asText("");
                core.setRecommendation(recommendation.isBlank() ? "No specific recommendation available." : recommendation);
                core.setShouldApply(resolveShouldApply(core.getMatchPercent(), json.path("shouldApply").asBoolean(true)));
                core.setDetailPromptVersion(DETAIL_PROMPT_VERSION);

                core = jobMatchScoreRepository.save(core);

                seedNativeNarrative(email, core, language, true);
            }

        }

        ResolvedNarrative narrative = (core.getRecommendation() != null && !core.getRecommendation().isBlank())
                ? resolveDetailNarrative(email, core, language)
                : new ResolvedNarrative(core.getMatchReason(), splitSkillsString(core.getWhyGoodMatch()),
                        splitSkillsString(core.getWhyNotPerfectMatch()), splitSkillsString(core.getImprovementSuggestions()),
                        core.getRecommendation());

        return new MatchDetailResult(
                true,
                core.getJobId(),
                core.getMatchPercent(),
                narrative.matchReason(),
                matchedSkills,
                missingSkills,
                narrative.whyGoodMatch(),
                narrative.whyNotPerfectMatch(),
                narrative.improvementSuggestions(),
                narrative.recommendation(),
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

    // מתחת ל-35 תמיד "אל תגיש", מעל 70 תמיד "כן תגיש" - רק בטווח שבאמצע נותנים ל-AI להחליט
    private static final int SHOULD_APPLY_FLOOR = 35;
    private static final int SHOULD_APPLY_CEILING = 70;

    // קובע אם להמליץ למועמד להגיש מועמדות - האחוזים הקיצוניים מכריעים בעצמם, ורק בטווח האמצעי סומכים על שיקול הדעת של ה-AI
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

    private static final List<String> UNGROUNDED_FILLER_TERMS = List.of(
            "leadership", "public health", "certification", "certifications",
            "language requirement", "language skills", "local experience", "local regulations"
    );

    private static final List<String> OVERQUALIFICATION_PHRASES = List.of(
            "overqualified", "over-qualified", "exceeds the job's requirement", "exceeds the stated",
            "above the stated", "above the required", "closer to the", "may prefer candidates with experience levels",
            "your seniority may be", "targeted at less experienced"
    );

    // תופס ניסוחים של "תקרת ניסיון" מפורשת - בלי זה אסור לספר למועמד שהוא "overqualified"
    private static final java.util.regex.Pattern EXPLICIT_EXPERIENCE_CAP = java.util.regex.Pattern.compile(
            "(maximum|no more than|up to \\d+\\s*years|junior[- ]only|entry[- ]level only)",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    // מסנן מהפלט של ה-AI (why good/not perfect/suggestions) טענות לא מבוססות - למשל מיקום שמוצג כניסיון, מונחים שלא מופיעים במשרה, או "overqualified" בלי הצדקה מפורשת
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

    // 5 בו-זמנית - לא לשגע את קריאות ה-OpenAI, גם ככה יש rate limit שם
    private static final int MAX_CONCURRENT_MATCH_CALLS = 5;

    record ValidatedBatch(List<JsonNode> validMatches, boolean allValid, Map<Long, List<String>> errorsByJobId) {}

    // קורא ל-AI לחישוב ההתאמה, מוודא (validateBatch) שהתשובה תקינה, ואם לא - שולח פידבק על השגיאות וקורא שוב בניסיון תיקון
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

            List<JsonNode> best = retryResult.validMatches().size() >= firstResult.validMatches().size()
                    ? retryResult.validMatches() : firstResult.validMatches();

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

    // עובר על כל התשובות שחזרו מה-AI ומסנן רק את אלה שעברו את כל בדיקות התקינות (validateMatch)
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

    // מרכיב הודעת שגיאה קריאה ל-AI שמסבירה בדיוק אילו משרות נכשלו ולמה, בשביל ניסיון החישוב החוזר
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

    record ParsedMatch(
            long jobId,
            String jobTitle,
            String jobFingerprint,

            boolean postingLacksRealContent,
            String fieldRelationCloseness,
            String matchReason,
            List<String> matchedMandatorySkills,

            List<String> matchedMandatorySkillsInferred,
            List<String> missingMandatorySkills,
            List<String> matchedPreferredSkills,
            List<String> matchedPreferredSkillsInferred,
            List<String> missingPreferredSkills,
            String requiredExperienceLevel,

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

    private static final List<String> NON_INFERABLE_SKILL_TERMS = List.of(
            "gmp", "good manufacturing practice", "regulatory affairs",
            "sterilization protocol", "hipaa", "iso 27001", "iso 9001", "iso 13485",
            "pci dss", "soc 2", "gdpr", "fda approv", "ce mark",
            "certified", "certification", "certificate",
            "license", "licensed", "licensing", "accredit", "board certified", "bar admission"
    );

    private static final int MAX_INFERRED_SKILLS_PER_JOB = 3;

    // ממיר את ה-JSON הגולמי שחזר מה-AI לאובייקט ParsedMatch מסודר, ומנרמל ערכים ריקים/"unrelated"
    ParsedMatch parseMatch(JsonNode match) {

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
                match.path("postingLacksRealContent").asBoolean(false),
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

    // שער האיכות המרכזי על תשובת ה-AI - מוודא שכל מיומנות/דרישה שהוא טוען עליה באמת מעוגנת בטקסט ה-CV או המשרה, ולא המצאה
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

            String candidateBlob = candidateSkillsBlob(analysis);
            for (String skill : concat(parsed.matchedMandatorySkills(), parsed.matchedPreferredSkills())) {
                if (!evidencedIn(skill, candidateBlob)) {
                    errors.add("matched skill '" + skill + "' is not evidenced anywhere in the candidate's profile.");
                }
            }

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

    // מרכז את כל הטקסט הרלוונטי מה-CV למחרוזת אחת לצורך חיפוש/אימות מיומנויות
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

    // מרכז את כל הטקסט הרלוונטי מהמשרה למחרוזת אחת לצורך חיפוש/אימות מיומנויות
    private String jobRequirementsBlob(Job job) {
        return String.join(" | ",
                nullToEmpty(job.getSkills()),
                nullToEmpty(job.getRequirements()),
                nullToEmpty(job.getDescription())
        ).toLowerCase(Locale.ROOT);
    }

    // בודק אם מונח מסוים (מיומנות/דרישה שה-AI טוען עליה) באמת מופיע בטקסט המקורי, כדי לתפוס טענות מומצאות
    private boolean evidencedIn(String needle, String haystackBlob) {
        if (needle == null || needle.isBlank()) {
            return true;
        }
        String normalizedNeedle = needle.toLowerCase(Locale.ROOT).trim();
        if (haystackBlob.contains(normalizedNeedle)) {
            return true;
        }
        // ֐-׿ עברית, ؀-ۿ ערבית - כדי שפיצול למילים יעבוד גם על CV/משרה לא באנגלית
        for (String word : normalizedNeedle.split("[^a-z0-9\\u0590-\\u05FF\\u0600-\\u06FF]+")) {
            if (word.length() >= 3 && haystackBlob.contains(word)) {
                return true;
            }
        }
        return false;
    }

    // בודק אם כותרת המקצוע של המועמד דומה מספיק לכותרת המשרה, כדי לתפוס מקרים שה-AI פסל בטעות התאמה ברורה
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

    // מוודא שכותרת המשרה שה-AI החזיר בתשובה היא באמת אותה משרה שנשלחה אליו ולא בלבול בין משרות
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

    // מחשב טביעת אצבע ל-CV הנוכחי - משמש לזהות מתי הניתוח השתנה כדי לדעת אם הקאש עדיין תקף
    String fingerprintCv(CVAnalysis analysis) {
        String cvTextHash = analysis.getCvTextHash();

        if (cvTextHash != null && !cvTextHash.isBlank()) {
            return HashUtil.sha256(MATCH_SCHEMA_VERSION + "|cvtext|" + cvTextHash);
        }

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

    // מחשב טביעת אצבע למשרה - משמש לזהות מתי תוכן המשרה השתנה כדי לדעת אם הקאש עדיין תקף
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

    // טביעת אצבע נוספת שכוללת גם את ה-id ושם החברה, כדי להבדיל בין משרות שהתוכן שלהן זהה
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
