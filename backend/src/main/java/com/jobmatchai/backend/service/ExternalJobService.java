package com.jobmatchai.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.jobmatchai.backend.model.ExternalJob;
import com.jobmatchai.backend.model.Job;
import com.jobmatchai.backend.repository.ExternalJobRepository;
import com.jobmatchai.backend.repository.JobMatchNarrativeRepository;
import com.jobmatchai.backend.repository.JobMatchScoreRepository;
import com.jobmatchai.backend.repository.MatchScoreJobRepository;
import com.jobmatchai.backend.repository.RecentlyViewedJobRepository;
import com.jobmatchai.backend.repository.SavedJobRepository;
import com.jobmatchai.backend.service.provider.ExternalJobData;
import com.jobmatchai.backend.service.provider.ExternalJobProvider;
import com.jobmatchai.backend.util.HashUtil;

import io.github.bucket4j.Bucket;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

@Service
public class ExternalJobService {

    private static final Logger log = LoggerFactory.getLogger(ExternalJobService.class);

    // מוסיפים מיליארד ל-id של משרה חיצונית כדי שלא תתנגש עם id של משרה פנימית באותן טבלאות match-score/narrative
    private static final long EXTERNAL_ID_OFFSET = 1_000_000_000L;

    private static final List<String> SUPPORTED_LANGUAGES = List.of("en", "ar", "he");

    @Autowired
    private ExternalJobRepository externalJobRepository;

    @Autowired
    private JobMatchScoreRepository jobMatchScoreRepository;

    @Autowired
    private JobMatchNarrativeRepository jobMatchNarrativeRepository;

    @Autowired
    private MatchScoreJobRepository matchScoreJobRepository;

    @Autowired
    private SavedJobRepository savedJobRepository;

    @Autowired
    private RecentlyViewedJobRepository recentlyViewedJobRepository;

    @Lazy
    @Autowired
    private ExternalJobService self;

    @Autowired
    private List<ExternalJobProvider> externalJobProviders;

    @Autowired
    private JobMatchService jobMatchService;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private OpenAICVAnalysisService openAICVAnalysisService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${externaljobs.import.keywords:software developer,web developer,project manager,accountant,mechanical engineer,civil engineer,electrical engineer,graphic designer,electrician," +
            "physician,doctor,dentist,pharmacist,physical therapist,veterinarian,registered nurse,healthcare assistant,lawyer,attorney,teacher,chef," +
            "marketing manager,human resources,financial analyst,data analyst,IT support,social worker,real estate agent,plumber,HVAC technician,welder,security guard,bank teller,translator," +
            "retail sales associate,cashier,customer service representative,sales representative,administrative assistant,warehouse worker,logistics coordinator,driver,transportation," +
            "hospitality staff,hotel receptionist,cleaner,construction worker,manufacturing worker," +
            "remote customer service,remote software developer,work from home}")
    private String categoryKeywordsCsv;

    @Value("${externaljobs.import.country:il}")
    private String defaultCountry;

    @Value("${externaljobs.retention.days:3}")
    private int retentionDays;

    @Value("${externaljobs.import.content-prep-concurrency:2}")
    private int contentPrepConcurrency;

    @Value("${externaljobs.import.content-prep-rate-limit-per-minute:10}")
    private int contentPrepRateLimitPerMinute;

    @Value("${externaljobs.import.content-prep-max-attempts:5}")
    private int contentPrepMaxAttempts;

    private Semaphore contentPrepConcurrencyLimiter;
    private Bucket contentPrepRateLimitBucket;
    private ExecutorService contentPrepExecutor;

    // מכין מגבלת מקביליות ו-rate limit לקריאות ה-AI שמשלימות תיאור/כישורים למשרות חיצוניות
    @PostConstruct
    void initContentPrep() {
        contentPrepConcurrencyLimiter = new Semaphore(Math.max(1, contentPrepConcurrency));
        contentPrepRateLimitBucket = Bucket.builder()
                .addLimit(limit -> limit.capacity(Math.max(1, contentPrepRateLimitPerMinute))
                        .refillGreedy(Math.max(1, contentPrepRateLimitPerMinute), Duration.ofMinutes(1)))
                .build();

        contentPrepExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public record ImportResult(int imported, int skipped, int total) {}

    public record ContentPrepFailure(Long jobId, String jobTitle, String field, String reason) {}

    public record BackfillResult(int candidatesFound, int fullyCompleted, List<ContentPrepFailure> failures) {}

    // מפרק את רשימת הקטגוריות מה-config למילות חיפוש בפועל, עם ברירת מחדל אם הרשימה ריקה
    private List<String> categoryKeywords() {
        List<String> keywords = Arrays.stream(categoryKeywordsCsv.split(","))
                .map(String::trim)
                .filter(k -> !k.isBlank())
                .toList();

        return keywords.isEmpty() ? List.of("software developer") : keywords;
    }

    // נקודת הכניסה לייבוא משרות - אם לא נתנו מילת חיפוש ספציפית מייבאים לפי כל הקטגוריות המוגדרות
    public ImportResult importJobs(String keywords, String country) {
        String searchCountry = (country == null || country.isBlank()) ? defaultCountry : country;

        if (keywords == null || keywords.isBlank()) {
            return importAllCategories(searchCountry);
        }

        return importForKeyword(keywords, searchCountry);
    }

    // עובר על כל קטגוריית חיפוש ומייבא ממנה משרות, בנפרד לספקים שלא צריכים מילת חיפוש (נמשכים פעם אחת בלבד)
    private ImportResult importAllCategories(String country) {
        int imported = 0;
        int skipped = 0;
        int total = 0;

        List<ExternalJobProvider> keywordIndependent =
                externalJobProviders.stream().filter(p -> !p.usesKeywords()).toList();
        List<ExternalJobProvider> keywordDependent =
                externalJobProviders.stream().filter(ExternalJobProvider::usesKeywords).toList();

        if (!keywordIndependent.isEmpty()) {
            ImportResult result = importFromProviders(keywordIndependent, null, country);
            imported += result.imported();
            skipped += result.skipped();
            total += result.total();
        }

        for (String keyword : categoryKeywords()) {
            ImportResult result = importFromProviders(keywordDependent, keyword, country);
            imported += result.imported();
            skipped += result.skipped();
            total += result.total();
        }

        return new ImportResult(imported, skipped, total);
    }

    private ImportResult importForKeyword(String keywords, String country) {
        return importFromProviders(externalJobProviders, keywords, country);
    }

    // הליבה של הייבוא - שולף משרות מהספקים, מזהה אילו כבר קיימות (לפי externalId/applyUrl), מעדכן שינויים ומוסיף חדשות תוך סינון כפילויות בין ספקים
    private ImportResult importFromProviders(List<ExternalJobProvider> providers, String keywords, String country) {
        List<ExternalJobData> fetched = new ArrayList<>();
        for (ExternalJobProvider provider : providers) {

            try {
                fetched.addAll(provider.fetchJobs(keywords, country, 50));
            } catch (Exception e) {
                log.warn("Provider {} failed to fetch jobs (keywords='{}', country='{}') - continuing with "
                        + "remaining providers.", provider.getClass().getSimpleName(), keywords, country, e);
            }
        }

        int updated = 0;
        int unchanged = 0;
        int crossProviderDuplicates = 0;
        List<ExternalJob> newJobs = new ArrayList<>();
        List<ExternalJob> changedJobs = new ArrayList<>();
        List<ExternalJob> touchedExisting = new ArrayList<>();

        Map<String, ExternalJob> existingByExternalId = new HashMap<>();
        Map<String, ExternalJob> existingByApplyUrl = new HashMap<>();
        loadExistingJobs(fetched, existingByExternalId, existingByApplyUrl);

        Set<String> seenTitleCompanyKeys = new HashSet<>();
        for (ExternalJobRepository.TitleCompanyProjection row : externalJobRepository.findAllTitleCompanyProjections()) {
            addTitleCompanyKey(seenTitleCompanyKeys, row.getTitle(), row.getCompanyName());
        }

        for (ExternalJobData data : fetched) {
            ExternalJob existing = existingByExternalId.get(data.externalId());
            if (existing == null) {
                existing = existingByApplyUrl.get(data.applyUrl());
            }

            if (existing != null) {

                existing.setImportedAt(LocalDateTime.now());
                touchedExisting.add(existing);
                addTitleCompanyKey(seenTitleCompanyKeys, existing.getTitle(), existing.getCompanyName());

                if (contentChanged(existing, data)) {
                    applyJobData(existing, data);
                    changedJobs.add(existing);
                    updated++;
                } else {
                    unchanged++;
                }
                continue;
            }

            String titleCompanyKey = titleCompanyKey(data.title(), data.companyName());
            if (titleCompanyKey != null && !seenTitleCompanyKeys.add(titleCompanyKey)) {
                log.debug("Skipping likely cross-provider duplicate job (title='{}', company='{}') - already "
                        + "seen in this import batch.", data.title(), data.companyName());
                crossProviderDuplicates++;
                continue;
            }

            ExternalJob job = new ExternalJob();
            job.setExternalJobId(data.externalId());
            applyJobData(job, data);
            job.setImportedAt(LocalDateTime.now());
            newJobs.add(job);
        }

        List<ExternalJob> needingEmbeddings = new ArrayList<>(newJobs);
        needingEmbeddings.addAll(changedJobs);
        attachEmbeddings(needingEmbeddings);

        for (ExternalJob job : changedJobs) {
            job.setAboutSummaryEn(null);
            job.setAboutSummaryAr(null);
            job.setAboutSummaryHe(null);
        }

        prepareJobContent(needingEmbeddings);

        externalJobRepository.saveAll(newJobs);

        externalJobRepository.saveAll(touchedExisting);

        return new ImportResult(newJobs.size(), updated + unchanged + crossProviderDuplicates, fetched.size());
    }

    // טוען בבת אחת (query אחד) את כל המשרות הקיימות שעשויות להתאים למה שנשלף, כדי לא לבצע שאילתה לכל משרה בנפרד
    private void loadExistingJobs(List<ExternalJobData> fetched,
                                   Map<String, ExternalJob> existingByExternalId,
                                   Map<String, ExternalJob> existingByApplyUrl) {
        if (fetched.isEmpty()) {
            return;
        }

        List<String> externalIds = fetched.stream()
                .map(ExternalJobData::externalId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        List<String> applyUrls = fetched.stream()
                .map(ExternalJobData::applyUrl)
                .filter(url -> url != null && !url.isBlank())
                .distinct()
                .toList();

        if (externalIds.isEmpty() && applyUrls.isEmpty()) {
            return;
        }

        List<String> externalIdsParam = externalIds.isEmpty() ? List.of("\0none") : externalIds;
        List<String> applyUrlsParam = applyUrls.isEmpty() ? List.of("\0none") : applyUrls;

        List<ExternalJob> existingRows =
                externalJobRepository.findByExternalJobIdInOrApplyUrlIn(externalIdsParam, applyUrlsParam);

        for (ExternalJob row : existingRows) {
            if (row.getExternalJobId() != null) {
                existingByExternalId.put(row.getExternalJobId(), row);
            }
            if (row.getApplyUrl() != null) {
                existingByApplyUrl.put(row.getApplyUrl(), row);
            }
        }
    }

    private String titleCompanyKey(String title, String companyName) {
        if (title == null || companyName == null || title.isBlank() || companyName.isBlank()) {

            return null;
        }
        return title.trim().toLowerCase() + "||" + companyName.trim().toLowerCase();
    }

    private void addTitleCompanyKey(Set<String> keys, String title, String companyName) {
        String key = titleCompanyKey(title, companyName);
        if (key != null) {
            keys.add(key);
        }
    }

    private void applyJobData(ExternalJob job, ExternalJobData data) {
        job.setTitle(data.title());
        job.setCompanyName(data.companyName());
        job.setLocation(data.location());
        job.setType(data.type());
        job.setSalary(data.salary());
        job.setDescription(data.description());
        job.setRequirements(data.requirements());
        job.setSkills(data.skills());
        job.setIndustry(data.industry());
        job.setApplyUrl(data.applyUrl());
        job.setSourceUrl(data.sourceUrl());
        job.setSourceName(data.sourceName());
        job.setPublishedAt(data.publishedAt());
    }

    // בודק אם התוכן של המשרה השתנה בפועל (כותרת/תיאור) - אם כן צריך לחשב מחדש embedding וסיכומים
    private boolean contentChanged(ExternalJob existing, ExternalJobData data) {
        return !nullToEmpty(existing.getTitle()).equals(nullToEmpty(data.title()))
                || !nullToEmpty(existing.getDescription()).equals(nullToEmpty(data.description()));
    }

    // מחשב embedding בבאטש אחד לכל המשרות שצריכות אותו ומצמיד לכל משרה יחד עם ה-hash והמודל ששימש
    private void attachEmbeddings(List<ExternalJob> jobs) {
        if (jobs.isEmpty()) {
            return;
        }

        List<String> texts = jobs.stream().map(this::embeddingText).toList();
        List<float[]> vectors = embeddingService.embedBatch(texts);

        if (vectors.size() != jobs.size()) {
            return;
        }

        String modelKey = embeddingService.modelKey();
        for (int i = 0; i < jobs.size(); i++) {
            ExternalJob job = jobs.get(i);
            job.setContentEmbedding(embeddingService.toJson(vectors.get(i)));
            job.setContentEmbeddingHash(HashUtil.sha256(texts.get(i)));
            job.setContentEmbeddingModel(modelKey);
        }
    }

    private String embeddingText(ExternalJob job) {
        String description = job.getDescription();
        if (description != null && description.length() > 1500) {
            description = description.substring(0, 1500);
        }
        return nullToEmpty(job.getTitle()) + ". " + nullToEmpty(description);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    // מחזיר את כל המשרות החיצוניות אחרי סינון - רק כאלה שבישראל או remote מוצגות למשתמש
    public List<ExternalJob> getAllExternalJobs() {
        List<ExternalJob> jobs = externalJobRepository.findAllByOrderByImportedAtDesc();
        jobs.forEach(this::populateTransientLocationFields);
        return jobs.stream().filter(this::isIsraelOrRemote).toList();
    }

    // בודק אם המשרה רלוונטית להצגה - Jobicy תמיד remote, אחרת בודקים שהמיקום/סוג המשרה מזכירים ישראל או remote
    private boolean isIsraelOrRemote(ExternalJob job) {
        if ("Jobicy".equalsIgnoreCase(job.getSourceName())) {
            return true;
        }

        String combined = (nullToEmpty(job.getLocation()) + " " + nullToEmpty(job.getType())).toLowerCase();
        return combined.contains("israel") || combined.contains("remote");
    }

    public Optional<ExternalJob> getExternalJobById(Long id) {
        return externalJobRepository.findById(id).map(job -> {
            populateTransientLocationFields(job);
            return job;
        });
    }

    // מחזיר סיכום "אודות המשרה" מהמטמון אם קיים בשפה המבוקשת, ואם לא - מייצר אותו עכשיו דרך AI ושומר לפעם הבאה
    public Map<String, Object> getOrGenerateAboutSummary(Long externalJobId, String language) {
        ExternalJob job = externalJobRepository.findById(externalJobId).orElse(null);
        if (job == null) {
            return Map.of();
        }

        String description = nullToEmpty(job.getDescription());
        if (description.isBlank()) {
            return Map.of();
        }

        String effectiveLanguage = SUPPORTED_LANGUAGES.contains(language) ? language : "en";
        String cachedSummary = getAboutSummaryField(job, effectiveLanguage);
        if (cachedSummary != null) {
            Map<String, Object> cached = parseJsonToMap(cachedSummary);
            if (cached != null) {
                return cached;
            }
        }

        String rawSummary;
        try {

            rawSummary = openAICVAnalysisService.summarizeJobDescription(
                    job.getTitle(), job.getCompanyName(), description, effectiveLanguage);
        } catch (Exception e) {
            log.warn("On-demand about-summary generation failed for external job id={} language={}",
                    externalJobId, effectiveLanguage, e);
            return Map.of();
        }

        Map<String, Object> parsed = parseJsonToMap(rawSummary);

        if (parsed == null || parsed.isEmpty()) {

            return Map.of();
        }

        setAboutSummaryField(job, effectiveLanguage, rawSummary);
        externalJobRepository.save(job);

        return parsed;
    }

    private String getAboutSummaryField(ExternalJob job, String language) {
        return switch (language) {
            case "ar" -> job.getAboutSummaryAr();
            case "he" -> job.getAboutSummaryHe();
            default -> job.getAboutSummaryEn();
        };
    }

    private void setAboutSummaryField(ExternalJob job, String language, String value) {
        switch (language) {
            case "ar" -> job.setAboutSummaryAr(value);
            case "he" -> job.setAboutSummaryHe(value);
            default -> job.setAboutSummaryEn(value);
        }
    }

    // מייצר סיכום "אודות המשרה" בכל שפה נתמכת שעוד חסרה למשרה הזו, עם ניסיונות חוזרים ורישום כשלים
    private void populateAboutSummaryAllLanguages(ExternalJob job, List<ContentPrepFailure> failures) {
        String description = nullToEmpty(job.getDescription());
        if (description.isBlank()) {

            for (String language : SUPPORTED_LANGUAGES) {
                if (getAboutSummaryField(job, language) == null) {
                    failures.add(new ContentPrepFailure(job.getId(), job.getTitle(),
                            "about-summary[" + language + "]", "job has no description to summarize"));
                }
            }
            return;
        }

        for (String language : SUPPORTED_LANGUAGES) {
            if (getAboutSummaryField(job, language) != null) {
                continue;
            }

            String rawSummary = callWithRetry(job, "about-summary[" + language + "]", failures, () ->
                    openAICVAnalysisService.summarizeJobDescription(
                            job.getTitle(), job.getCompanyName(), description, language));
            if (rawSummary == null) {
                continue;
            }

            Map<String, Object> parsed = parseJsonToMap(rawSummary);
            if (parsed == null || parsed.isEmpty()) {
                log.warn("content-prep about-summary[{}] returned no usable content for external job id={} title='{}'",
                        language, job.getId(), job.getTitle());
                continue;
            }
            setAboutSummaryField(job, language, rawSummary);
        }
    }

    private void prepareJobContent(List<ExternalJob> jobs) {
        prepareJobContent(jobs, new CopyOnWriteArrayList<>());
    }

    // משלים תוכן חסר (דרישות/כישורים + סיכומים) לכל המשרות שהתקבלו, במקביל ותחת מגבלת קצב הקריאות ל-AI
    private void prepareJobContent(List<ExternalJob> jobs, List<ContentPrepFailure> failures) {
        if (jobs.isEmpty()) {
            return;
        }

        List<CompletableFuture<Void>> tasks = jobs.stream()
                .map(job -> CompletableFuture.runAsync(() -> {
                    try {
                        contentPrepConcurrencyLimiter.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    try {
                        populateRequirementsAndSkills(job, failures);
                        populateAboutSummaryAllLanguages(job, failures);
                    } finally {
                        contentPrepConcurrencyLimiter.release();
                    }
                }, contentPrepExecutor))
                .toList();

        tasks.forEach(CompletableFuture::join);
    }

    // מריץ קריאת AI עם ניסיונות חוזרים ו-backoff גדל, ואם כולם נכשלים רושם את הכשל ומחזיר null במקום לזרוק
    private String callWithRetry(ExternalJob job, String operation, List<ContentPrepFailure> failures, Callable<String> call) {
        Exception lastError = null;
        for (int attempt = 1; attempt <= Math.max(1, contentPrepMaxAttempts); attempt++) {
            try {
                contentPrepRateLimitBucket.asBlocking().consume(1);
                return call.call();
            } catch (Exception e) {
                lastError = e;
                if (attempt >= contentPrepMaxAttempts) {
                    break;
                }
                long backoffMs = Math.min(60_000L, 15_000L << (attempt - 1));
                log.warn("content-prep '{}' attempt {}/{} failed for external job id={} title='{}' - retrying in {}ms: {}",
                        operation, attempt, contentPrepMaxAttempts, job.getId(), job.getTitle(), backoffMs, e.toString());
                sleepQuietly(backoffMs);
            }
        }

        log.warn("content-prep '{}' permanently failed for external job id={} title='{}' after {} attempt(s)",
                operation, job.getId(), job.getTitle(), contentPrepMaxAttempts, lastError);
        failures.add(new ContentPrepFailure(job.getId(), job.getTitle(), operation,
                lastError == null ? "unknown" : lastError.getClass().getSimpleName() + ": " + lastError.getMessage()));
        return null;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // משלים דרישות/כישורים למשרה בודדת (אם חסר) ושומר מיד, לשימוש לפני התאמה בזמן אמת
    private void ensureRequirementsAndSkills(ExternalJob job) {
        if (populateRequirementsAndSkills(job, new CopyOnWriteArrayList<>())) {
            externalJobRepository.save(job);
        }
    }

    // הופך משרה חיצונית לאובייקט Job רגיל בשביל מנוע ההתאמה, עם id מוזז (EXTERNAL_ID_OFFSET) שלא יתנגש עם משרות פנימיות
    private Job buildTransientJobForMatching(ExternalJob externalJob) {
        ensureRequirementsAndSkills(externalJob);

        Job job = new Job(
                externalJob.getTitle(),
                externalJob.getCompanyName(),
                null,
                externalJob.getLocation(),
                externalJob.getType(),
                externalJob.getSalary(),
                externalJob.getDescription(),
                externalJob.getRequirements(),
                externalJob.getSkills()
        );
        job.setId(EXTERNAL_ID_OFFSET + externalJob.getId());
        return job;
    }

    // מחלץ דרישות וכישורים מתוך תיאור המשרה באמצעות AI, כשהשדות האלה עדיין ריקים
    private boolean populateRequirementsAndSkills(ExternalJob job, List<ContentPrepFailure> failures) {
        if (!nullToEmpty(job.getRequirements()).isBlank() || !nullToEmpty(job.getSkills()).isBlank()) {
            return false;
        }

        String description = nullToEmpty(job.getDescription());
        if (description.isBlank()) {

            failures.add(new ContentPrepFailure(job.getId(), job.getTitle(),
                    "requirements/skills", "job has no description to extract from"));
            return false;
        }

        String raw = callWithRetry(job, "requirements/skills", failures, () ->
                openAICVAnalysisService.extractRequirementsAndSkills(job.getTitle(), job.getCompanyName(), description));
        if (raw == null) {
            return false;
        }

        Map<String, Object> parsed = parseJsonToMap(raw);
        if (parsed == null) {
            log.warn("content-prep requirements/skills returned unparseable JSON for external job id={} title='{}'",
                    job.getId(), job.getTitle());
            return false;
        }

        String requirements = String.valueOf(parsed.getOrDefault("requirements", "")).trim();
        String skills = nullToEmpty(sanitizeSkillsList(
                String.valueOf(parsed.getOrDefault("skills", "")).trim()));
        if (requirements.isBlank() && skills.isBlank()) {

            return false;
        }

        job.setRequirements(requirements.isBlank() ? null : requirements);
        job.setSkills(skills.isBlank() ? null : skills);
        return true;
    }

    private static final Set<String> DISALLOWED_SKILL_TOKENS =
            Set.of("n/a", "na", "none", "unknown", "n\\a");

    // מנקה את רשימת הכישורים שחזרה מה-AI - מסיר טוקנים ריקים או חסרי משמעות כמו "n/a"/"none"
    private String sanitizeSkillsList(String rawSkills) {
        if (rawSkills == null || rawSkills.isBlank()) {
            return null;
        }

        List<String> cleaned = Arrays.stream(rawSkills.split("[,;|]"))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .filter(token -> token.chars().anyMatch(Character::isLetter))
                .filter(token -> !DISALLOWED_SKILL_TOKENS.contains(token.toLowerCase(Locale.ROOT)))
                .toList();

        return cleaned.isEmpty() ? null : String.join(", ", cleaned);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonToMap(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    // ממלא שדות מיקום שלא נשמרים ב-DB (country/city) לתצוגה בלבד, כולל הטיפול המיוחד ל-Jobicy כ-Remote
    private void populateTransientLocationFields(ExternalJob job) {
        job.setCountry(defaultCountry.toUpperCase());

        if ("Jobicy".equalsIgnoreCase(job.getSourceName())) {
            job.setLocation("Remote");
        }

        job.setCity(job.getLocation());
    }

    // מחשב פרטי התאמה מלאים למשרה חיצונית ספציפית - ממיר ל-Job זמני ומעביר למנוע ההתאמה הרגיל
    public JobMatchService.MatchDetailResult getMatchDetailForExternalJob(
            String email, Long externalJobId, String language) {

        ExternalJob externalJob = externalJobRepository.findById(externalJobId).orElse(null);
        if (externalJob == null) {
            return new JobMatchService.MatchDetailResult(
                    false, externalJobId, null, null, List.of(), List.of(), List.of(), List.of(), List.of(), null, null,
                    null, null, null, null, null, null, null, List.of(), List.of(),
                    null, null, null, null, null, null, List.of(), List.of(), null);
        }

        Job transientJob = buildTransientJobForMatching(externalJob);

        JobMatchService.MatchDetailResult result = jobMatchService.getMatchDetail(email, transientJob, language);

        return new JobMatchService.MatchDetailResult(
                result.hasAnalysis(),
                externalJobId,
                result.matchPercent(),
                result.matchReason(),
                result.matchedSkills(),
                result.missingSkills(),
                result.whyGoodMatch(),
                result.whyNotPerfectMatch(),
                result.improvementSuggestions(),
                result.recommendation(),
                result.shouldApply(),
                result.fieldRelated(),
                result.skillsMatchPercent(),
                result.experienceMatchPercent(),
                result.educationMatchPercent(),
                result.fieldRelevancePercent(),
                result.certificationMatchPercent(),
                result.locationMatchPercent(),
                result.missingRequiredSkills(),
                result.missingPreferredSkills(),
                result.requiredExperienceLevel(),
                result.requiredExperienceType(),
                result.candidateHasRequiredExperienceType(),
                result.requiredEducationLevel(),
                result.requiredCertificationLevel(),
                result.lastAnalyzedAt(),
                result.matchedRequiredSkills(),
                result.matchedPreferredSkills(),
                result.generalVocationalRole()
        );
    }

    public boolean hasAnalysis(String email) {
        return jobMatchService.hasAnalysis(email);
    }

    // מחשב ציוני התאמה למספר משרות חיצוניות בבת אחת ומחזיר את ה-id המקוריים (בלי ה-offset) ללקוח
    public JobMatchService.MatchScoresResult getMatchScoresForExternalJobs(
            String email, List<Long> externalJobIds, String language) {

        List<ExternalJob> externalJobs = externalJobIds.isEmpty()
                ? List.of()
                : externalJobRepository.findAllById(externalJobIds);

        List<Job> transientJobs = new ArrayList<>();
        for (ExternalJob externalJob : externalJobs) {
            transientJobs.add(buildTransientJobForMatching(externalJob));
        }

        JobMatchService.MatchScoresResult result =
                jobMatchService.getMatchScores(email, transientJobs, language);

        if (!result.hasAnalysis() || result.matches().isEmpty()) {
            return result;
        }

        List<Map<String, Object>> remappedMatches = new ArrayList<>();
        for (Map<String, Object> match : result.matches()) {
            Map<String, Object> remapped = new LinkedHashMap<>(match);
            Object rawJobId = match.get("jobId");
            if (rawJobId instanceof Number number) {
                remapped.put("jobId", number.longValue() - EXTERNAL_ID_OFFSET);
            }
            remappedMatches.add(remapped);
        }

        return new JobMatchService.MatchScoresResult(true, remappedMatches);
    }

    // כמו getMatchScoresForExternalJobs אבל בסטרימינג - מחזיר כל תוצאה ל-callback ברגע שהיא מוכנה במקום לחכות לכולן
    public void streamMatchScoresForExternalJobs(
            String email, List<Long> externalJobIds, String language,
            java.util.function.BiConsumer<Long, Map<String, Object>> onJobResult, Runnable onComplete) {

        List<ExternalJob> externalJobs = externalJobIds == null || externalJobIds.isEmpty()
                ? List.of()
                : externalJobRepository.findAllById(externalJobIds);

        List<Job> transientJobs = new ArrayList<>();
        Map<Long, float[]> jobEmbeddings = new HashMap<>();

        for (ExternalJob externalJob : externalJobs) {
            Job job = buildTransientJobForMatching(externalJob);
            long offsetId = job.getId();
            transientJobs.add(job);

            float[] vector = embeddingService.fromJson(externalJob.getContentEmbedding());
            if (vector != null) {
                jobEmbeddings.put(offsetId, vector);
            }
        }

        jobMatchService.computeMatchScoresStreaming(email, transientJobs, language, jobEmbeddings, "external",
                (offsetId, payload) -> {
                    Map<String, Object> remapped = new LinkedHashMap<>(payload);
                    remapped.put("jobId", offsetId - EXTERNAL_ID_OFFSET);
                    onJobResult.accept(offsetId - EXTERNAL_ID_OFFSET, remapped);
                },
                onComplete);
    }

    // רץ אוטומטית לפי cron - מייבא משרות חדשות מהספקים ואז מפעיל מחיקת משרות ישנות שלא הופיעו יותר
    @Scheduled(cron = "${externaljobs.import.schedule-cron:0 0 */6 * * *}")
    public void scheduledImport() {
        ImportResult result;
        try {
            result = importJobs(null, defaultCountry);
            log.info("Scheduled external job import complete (country={}): imported={} skipped={} total={}",
                    defaultCountry, result.imported(), result.skipped(), result.total());
        } catch (Exception e) {
            log.error("Scheduled external job import failed (country={}) - skipping retention pruning for this "
                    + "cycle since it's unclear which postings actually got a chance to reappear.", defaultCountry, e);
            return;
        }

        try {

            self.pruneExpiredJobs(result);
        } catch (Exception e) {
            log.error("Scheduled external job retention pruning failed (country={})", defaultCountry, e);
        }
    }

    private static final int MIN_FETCHED_TO_TRUST_PRUNING = 1;

    // מוחק משרות חיצוניות שלא חזרו בייבוא האחרון מעבר לתקופת השמירה, כולל כל הנתונים הקשורים אליהן (ציונים/narratives/queue)
    @Transactional
    public void pruneExpiredJobs(ImportResult result) {
        if (retentionDays <= 0) {
            return;
        }

        if (result.total() < MIN_FETCHED_TO_TRUST_PRUNING) {
            log.warn("Skipping retention pruning this cycle - the import fetched 0 jobs total across every "
                    + "provider/category, which looks like a broad provider outage rather than a real empty "
                    + "result set. Pruning against this cycle could wrongly delete still-live postings that "
                    + "simply had no chance to reappear.");
            return;
        }

        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        List<Long> idsToPrune = externalJobRepository.findIdByImportedAtBefore(cutoff);
        if (idsToPrune.isEmpty()) {
            return;
        }

        List<Long> offsetIds = idsToPrune.stream().map(id -> EXTERNAL_ID_OFFSET + id).toList();
        jobMatchScoreRepository.deleteByJobIdIn(offsetIds);
        jobMatchNarrativeRepository.deleteByJobIdIn(offsetIds);
        matchScoreJobRepository.deleteByJobIdInAndJobType(idsToPrune, "external");

        externalJobRepository.deleteAllByIdInBatch(idsToPrune);

        int deleted = idsToPrune.size();
        if (deleted > 0) {
            log.info("Retention pruning removed {} external job(s) not re-imported since before {}.", deleted, cutoff);
        }
    }

    // בעליית האפליקציה - אם טבלת המשרות החיצוניות ריקה לגמרי, מייבא ישר כדי שלא יהיה מסך ריק למשתמשים
    @EventListener(ApplicationReadyEvent.class)
    public void importOnStartupIfEmpty() {
        if (externalJobRepository.count() == 0) {
            importJobs(null, defaultCountry);
        }
    }

    // בעליית האפליקציה - משלים embedding למשרות שאיכשהו נשארו בלעדיו (למשל מגרסה ישנה של הקוד)
    @EventListener(ApplicationReadyEvent.class)
    public void backfillMissingEmbeddingsOnStartup() {
        List<ExternalJob> missing = externalJobRepository.findByContentEmbeddingIsNull();
        if (missing.isEmpty()) {
            return;
        }
        attachEmbeddings(missing);
        externalJobRepository.saveAll(missing);
    }

    // פעולת תחזוקה ידנית - מוצא משרות עם תוכן חסר (דרישות/כישורים/סיכומים) ומריץ עליהן את השלמת התוכן
    public BackfillResult backfillMissingContent() {
        List<ExternalJob> candidates = externalJobRepository.findAll().stream()
                .filter(this::isMissingAnyContentField)
                .toList();

        if (candidates.isEmpty()) {
            return new BackfillResult(0, 0, List.of());
        }

        List<ContentPrepFailure> failures = new CopyOnWriteArrayList<>();
        prepareJobContent(candidates, failures);
        externalJobRepository.saveAll(candidates);

        int fullyCompleted = (int) candidates.stream().filter(j -> !isMissingAnyContentField(j)).count();

        return new BackfillResult(candidates.size(), fullyCompleted, failures);
    }

    private boolean isMissingAnyContentField(ExternalJob job) {
        boolean missingRequirementsAndSkills =
                nullToEmpty(job.getRequirements()).isBlank() && nullToEmpty(job.getSkills()).isBlank();
        return missingRequirementsAndSkills
                || job.getAboutSummaryEn() == null
                || job.getAboutSummaryAr() == null
                || job.getAboutSummaryHe() == null;
    }

    public record DuplicateCleanupResult(int duplicateGroupsFound, int rowsRemoved) {}

    // מקבץ משרות לפי כותרת+חברה, משאיר רק את הישנה ביותר מכל קבוצה ומוחק את השאר עם כל הנתונים הקשורים אליהן
    @Transactional
    public DuplicateCleanupResult removeDuplicateExternalJobs() {
        List<ExternalJob> all = externalJobRepository.findAll();

        Map<String, List<ExternalJob>> groups = new LinkedHashMap<>();
        for (ExternalJob job : all) {
            String key = titleCompanyKey(job.getTitle(), job.getCompanyName());
            if (key == null) {
                continue;
            }
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(job);
        }

        List<Long> idsToRemove = new ArrayList<>();
        int duplicateGroups = 0;
        for (List<ExternalJob> group : groups.values()) {
            if (group.size() <= 1) {
                continue;
            }
            duplicateGroups++;
            group.stream()
                    .sorted(Comparator.comparing(ExternalJob::getId))
                    .skip(1)
                    .forEach(job -> idsToRemove.add(job.getId()));
        }

        if (idsToRemove.isEmpty()) {
            return new DuplicateCleanupResult(0, 0);
        }

        List<Long> offsetIds = idsToRemove.stream().map(id -> EXTERNAL_ID_OFFSET + id).toList();
        jobMatchScoreRepository.deleteByJobIdIn(offsetIds);
        jobMatchNarrativeRepository.deleteByJobIdIn(offsetIds);
        matchScoreJobRepository.deleteByJobIdInAndJobType(idsToRemove, "external");
        recentlyViewedJobRepository.deleteByJobIdInAndJobType(idsToRemove, "external");
        savedJobRepository.deleteByJobIdInAndJobType(idsToRemove, "external");
        externalJobRepository.deleteAllByIdInBatch(idsToRemove);

        log.info("Removed {} duplicate external job(s) across {} title+company group(s).",
                idsToRemove.size(), duplicateGroups);

        return new DuplicateCleanupResult(duplicateGroups, idsToRemove.size());
    }

    // פעולת תחזוקה ידנית - מריץ מחדש את ניקוי רשימת הכישורים על כל המשרות הקיימות ושומר רק את מה שהשתנה
    public int resanitizeExistingSkills() {
        List<ExternalJob> all = externalJobRepository.findAll();
        List<ExternalJob> changed = new ArrayList<>();

        for (ExternalJob job : all) {
            String cleaned = sanitizeSkillsList(job.getSkills());
            if (!Objects.equals(cleaned, job.getSkills())) {
                job.setSkills(cleaned);
                changed.add(job);
            }
        }

        if (!changed.isEmpty()) {
            externalJobRepository.saveAll(changed);
        }

        return changed.size();
    }
}
