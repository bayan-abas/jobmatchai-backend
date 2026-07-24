package com.jobmatchai.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.jobmatchai.backend.model.ExternalJob;
import com.jobmatchai.backend.model.Job;
import com.jobmatchai.backend.repository.ExternalJobRepository;
import com.jobmatchai.backend.repository.JobMatchNarrativeRepository;
import com.jobmatchai.backend.repository.JobMatchScoreRepository;
import com.jobmatchai.backend.repository.MatchScoreJobRepository;
import com.jobmatchai.backend.service.provider.ExternalJobData;
import com.jobmatchai.backend.service.provider.ExternalJobProvider;
import com.jobmatchai.backend.util.HashUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class ExternalJobService {

    private static final Logger log = LoggerFactory.getLogger(ExternalJobService.class);

    /**
     * Offset added to an ExternalJob's real id when building a transient Job wrapper for
     * JobMatchService, so cached rows in job_match_scores (keyed by a plain Long jobId with
     * no foreign key) never collide with real internal Job ids.
     */
    private static final long EXTERNAL_ID_OFFSET = 1_000_000_000L;

    // Every language getOrGenerateAboutSummary/prepareJobContent generate an about-summary for -
    // must match LanguageContext's supported languages on the frontend.
    private static final List<String> SUPPORTED_LANGUAGES = List.of("en", "ar", "he");

    @Autowired
    private ExternalJobRepository externalJobRepository;

    @Autowired
    private JobMatchScoreRepository jobMatchScoreRepository;

    @Autowired
    private JobMatchNarrativeRepository jobMatchNarrativeRepository;

    @Autowired
    private MatchScoreJobRepository matchScoreJobRepository;

    /**
     * Spring auto-collects every ExternalJobProvider bean here, so all active sources (Jooble,
     * JSearch, ...) are queried and merged on each import. Adding a new provider later just
     * means adding a new @Component implementing ExternalJobProvider - nothing here changes.
     */
    @Autowired
    private List<ExternalJobProvider> externalJobProviders;

    @Autowired
    private JobMatchService jobMatchService;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private OpenAICVAnalysisService openAICVAnalysisService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // The full set of categories imported every cycle (see importAllCategories) - covers the
    // general Israeli job market broadly, not just tech, plus explicit remote/work-from-home
    // variants so remote postings surface across categories rather than only through Jobicy
    // (which is itself a tech-leaning remote-jobs feed - see JobicyJobProvider). Deliberately a
    // config property (not a constant) so it can be extended/tuned per deployment without a
    // code change.
    @Value("${externaljobs.import.keywords:software developer,web developer,project manager,accountant,mechanical engineer,civil engineer,electrical engineer,graphic designer,electrician," +
            "physician,doctor,dentist,pharmacist,physical therapist,veterinarian,registered nurse,healthcare assistant,lawyer,attorney,teacher,chef," +
            "marketing manager,human resources,financial analyst,data analyst,IT support,social worker,real estate agent,plumber,HVAC technician,welder,security guard,bank teller,translator," +
            "retail sales associate,cashier,customer service representative,sales representative,administrative assistant,warehouse worker,logistics coordinator,driver,transportation," +
            "hospitality staff,hotel receptionist,cleaner,construction worker,manufacturing worker," +
            "remote customer service,remote software developer,work from home}")
    private String categoryKeywordsCsv;

    @Value("${externaljobs.import.country:il}")
    private String defaultCountry;

    // None of these providers expose an explicit "this posting is closed" flag - the only
    // available signal that a listing is gone is that it stops reappearing in the scheduled
    // re-imports (see importFromProviders' "reappearing confirms it's still live" comment below).
    // This IS the closed-job/sync mechanism: retentionDays is how long a job is allowed to go
    // without reappearing before it's treated as closed and pruned. Short enough (relative to the
    // 6-hourly import cadence, ~4 cycles/day) that a genuinely closed posting disappears within a
    // few days rather than lingering for weeks, but still several cycles wide so a job surviving
    // pruning doesn't require reappearing in literally every single cycle - insulation against a
    // provider's ranking briefly bumping a still-open job out of a keyword's top results, or one
    // cycle's fetch having a transient hiccup (see MIN_FETCHED_TO_TRUST_PRUNING below for the
    // broader-outage case).
    @Value("${externaljobs.retention.days:3}")
    private int retentionDays;

    public record ImportResult(int imported, int skipped, int total) {}

    private List<String> categoryKeywords() {
        List<String> keywords = Arrays.stream(categoryKeywordsCsv.split(","))
                .map(String::trim)
                .filter(k -> !k.isBlank())
                .toList();

        return keywords.isEmpty() ? List.of("software developer") : keywords;
    }

    public ImportResult importJobs(String keywords, String country) {
        String searchCountry = (country == null || country.isBlank()) ? defaultCountry : country;

        // No specific keyword given (the unattended scheduled/startup path) means "import
        // everything" - every configured category, not just one. A single query only ever
        // asking about "software developer" is exactly why the job pool used to be almost
        // entirely tech postings: providers return their top/most-relevant results for
        // whatever is asked, so a category that's never queried simply never appears.
        if (keywords == null || keywords.isBlank()) {
            return importAllCategories(searchCountry);
        }

        return importForKeyword(keywords, searchCountry);
    }

    private ImportResult importAllCategories(String country) {
        int imported = 0;
        int skipped = 0;
        int total = 0;

        // Providers that ignore the keyword entirely (see ExternalJobProvider#usesKeywords,
        // e.g. JobicyJobProvider's single fixed remote-jobs feed) would otherwise get hit once
        // per category for the exact same unchanging result set - call them exactly once
        // instead. Providers whose results genuinely depend on the keyword get one call per
        // category, which is the whole point of importing "all categories".
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

    private ImportResult importFromProviders(List<ExternalJobProvider> providers, String keywords, String country) {
        List<ExternalJobData> fetched = new ArrayList<>();
        for (ExternalJobProvider provider : providers) {
            // Defensive per-provider isolation: every current ExternalJobProvider already
            // catches its own exceptions internally and returns an empty list on failure (see
            // e.g. JobicyJobProvider#fetchJobs), but this guards against a future provider that
            // doesn't - one provider's outage/bug must never abort the whole import cycle (and
            // the other providers' results already fetched) for every other category too.
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

        // Batch-load every existing row that could possibly match ANY job in this fetch in one
        // (or two, via the OR-in-SQL single query) round trip, instead of one
        // findByExternalJobIdOrApplyUrl query per fetched job - against a remote database, a
        // 50-job-per-provider fetch used to mean 50+ individual round trips here every cycle.
        Map<String, ExternalJob> existingByExternalId = new HashMap<>();
        Map<String, ExternalJob> existingByApplyUrl = new HashMap<>();
        loadExistingJobs(fetched, existingByExternalId, existingByApplyUrl);

        // Cross-provider dedup signal, scoped to this single import batch only: two different
        // providers can aggregate the exact same real-world posting under two different external
        // ids/apply urls (e.g. Jobicy's own id vs. JSearch re-aggregating the same LinkedIn/Indeed
        // posting), which the id/url lookup above can never catch since neither value matches.
        // Deliberately conservative - only an exact, case-insensitive, trimmed title+company match
        // within the same cycle counts as a duplicate, never fuzzy matching, to avoid merging two
        // genuinely different postings that simply share a common title (e.g. "Software Engineer"
        // at two unrelated companies would have different companyName and NOT match).
        Set<String> seenTitleCompanyKeys = new HashSet<>();

        for (ExternalJobData data : fetched) {
            ExternalJob existing = existingByExternalId.get(data.externalId());
            if (existing == null) {
                existing = existingByApplyUrl.get(data.applyUrl());
            }

            if (existing != null) {
                // Reappearing in a fresh fetch confirms the posting is still live, regardless of
                // whether its content changed - this is what keeps a still-open job from being
                // silently pruned by retention just because it hasn't needed a content update.
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

        // One batch embeddings call per import cycle covering every new AND changed job, instead
        // of one API call per job - this is the entire reason job embeddings are computed at
        // import time rather than lazily per match request: the cost is paid once per job
        // (again only when its content actually changes), off the user-facing request path.
        // Fails open on any embeddings-API problem (embedBatch returns an empty list) - jobs are
        // still saved without an embedding and simply always go through the AI match path until
        // a later startup backfill fills them in.
        List<ExternalJob> needingEmbeddings = new ArrayList<>(newJobs);
        needingEmbeddings.addAll(changedJobs);
        attachEmbeddings(needingEmbeddings);

        // Content actually changing (title/description) invalidates any about-summary generated
        // from the old description - unlike requirements/skills, which applyJobData already reset
        // to whatever the fresh provider data gives (null, for every provider today), nothing
        // upstream clears these, so it's done explicitly here before regenerating them below.
        for (ExternalJob job : changedJobs) {
            job.setAboutSummaryEn(null);
            job.setAboutSummaryAr(null);
            job.setAboutSummaryHe(null);
        }
        // Same list as the embeddings above: every new AND changed job gets requirements/skills
        // and an about-summary in every language prepared now, off the user-facing request path,
        // instead of the first candidate to open the job paying for it (see prepareJobContent).
        prepareJobContent(needingEmbeddings);

        externalJobRepository.saveAll(newJobs);
        // Covers both changed and unchanged existing rows in one call - every touched row needs
        // its bumped importedAt persisted regardless of whether its content also changed.
        externalJobRepository.saveAll(touchedExisting);

        return new ImportResult(newJobs.size(), updated + unchanged + crossProviderDuplicates, fetched.size());
    }

    // Fills the two lookup maps from a single batch query covering every fetched job's
    // externalId/applyUrl, so the per-job loop in importFromProviders is pure in-memory map
    // lookups afterward. A row can legitimately land in both maps (its externalId matches one map
    // entry and its applyUrl matches another) - that's fine, both maps just point at the same
    // persisted ExternalJob instance in that case.
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

        // JPA's derived "In...In" query still needs both collections non-empty to build a
        // sensible WHERE clause; pass a single impossible sentinel value for whichever side has
        // nothing to match instead of an empty IN(), which some JPA providers reject outright.
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
            // No confident signal without both fields present - never treat as a duplicate on
            // partial data, to stay conservative.
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

    // Only the fields that would actually change the candidate-facing content (and therefore
    // warrant a fresh embedding) - salary/location/type churn is common on some providers'
    // re-fetches even when the role itself is unchanged, but title/description are the real
    // signal something meaningfully changed.
    private boolean contentChanged(ExternalJob existing, ExternalJobData data) {
        return !nullToEmpty(existing.getTitle()).equals(nullToEmpty(data.title()))
                || !nullToEmpty(existing.getDescription()).equals(nullToEmpty(data.description()));
    }

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

    public List<ExternalJob> getAllExternalJobs() {
        List<ExternalJob> jobs = externalJobRepository.findAllByOrderByImportedAtDesc();
        jobs.forEach(this::populateTransientLocationFields);
        return jobs.stream().filter(this::isIsraelOrRemote).toList();
    }

    // "country" isn't real per-job data (see populateTransientLocationFields - it's always just
    // the single configured import country, "IL", copied onto every row regardless of the
    // job's actual scope), so it can't be used to filter. Jobicy (currently the only working
    // provider - see JoobleJobProvider/JSearchJobProvider doc comments) always calls its
    // /remote-jobs endpoint with geo=israel, which Jobicy itself resolves server-side into jobs
    // actually eligible for Israel-based remote candidates - every Jobicy-sourced job is
    // therefore remote AND Israel-eligible BY CONSTRUCTION, regardless of what its displayed
    // location text says (Jobicy jobs commonly show broad region tags like "EMEA" or "APAC,
    // EMEA, LATAM, Canada, USA", which read as "a job in other countries" even though the
    // posting is actually open to remote Israeli applicants). Trusting the provider's own
    // contract here is far more reliable than trying to parse an open-ended region-list string.
    // Any other/future provider falls back to checking its own location text for "israel" or
    // "remote".
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

    // Lazily generates (on first request per job+language) and caches a structured AI summary
    // of the posting's full description for the frontend's "About this job" section - never
    // used for match scoring, which always reads the raw description directly (see
    // getMatchScoresForExternalJobs/getMatchDetailForExternalJob above). One cached column PER
    // language (see ExternalJob), rather than a single slot plus a content hash - a candidate
    // viewing in Hebrew right after another viewed in English no longer discards the other
    // language's cached copy. prepareJobContent proactively fills all three at import time, so
    // this on-demand path is mainly a fallback for jobs imported before that existed, or whose
    // import-time generation failed.
    //
    // Returns a plain Map (not the JsonNode it's built from) - a JsonNode embedded directly in a
    // Map<String,Object> controller response gets serialized via bean introspection (its own
    // isArray()/isObject()/... predicate methods) instead of as JSON, since the map's value type
    // erases to Object. Converting here, once, is what makes the response actually come back as
    // real JSON instead of a dump of JsonNode's internal accessors.
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

        String rawSummary = openAICVAnalysisService.summarizeJobDescription(
                job.getTitle(), job.getCompanyName(), description, effectiveLanguage);
        Map<String, Object> parsed = parseJsonToMap(rawSummary);

        if (parsed == null || parsed.isEmpty()) {
            // Generation failed (or the AI returned nothing usable) - don't cache a failure,
            // so the next view retries instead of getting stuck on a blank summary forever.
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

    // Proactively generates the about-summary for all three supported languages in memory only
    // (no save) - used by prepareJobContent at import time so a candidate opening this job for
    // the first time, in any language, already has a ready summary instead of waiting on
    // getOrGenerateAboutSummary's on-demand path above. Each language is independent and
    // best-effort: one language's AI failure must never block the other two, or the
    // requirements/skills extraction, from completing for this job.
    private void populateAboutSummaryAllLanguages(ExternalJob job) {
        String description = nullToEmpty(job.getDescription());
        if (description.isBlank()) {
            return;
        }

        for (String language : SUPPORTED_LANGUAGES) {
            if (getAboutSummaryField(job, language) != null) {
                continue;
            }
            try {
                String rawSummary = openAICVAnalysisService.summarizeJobDescription(
                        job.getTitle(), job.getCompanyName(), description, language);
                Map<String, Object> parsed = parseJsonToMap(rawSummary);
                if (parsed == null || parsed.isEmpty()) {
                    continue;
                }
                setAboutSummaryField(job, language, rawSummary);
            } catch (Exception e) {
                log.warn("Failed to generate '{}' about-summary for external job id={} - will fall back to "
                        + "on-demand generation on first view", language, job.getId(), e);
            }
        }
    }

    // Proactively prepares everything about a newly-imported or content-changed external job that
    // doesn't depend on any specific candidate - requirements/skills extraction, and the
    // about-summary in every language - so that when a candidate opens it for the first time,
    // only the actual candidate-specific match score computation remains; there's no more
    // job-side AI extraction to wait on. Mutates in memory only, exactly like attachEmbeddings
    // above - the caller's own saveAll persists the result in the same batch.
    //
    // Match Score itself is deliberately NOT computed here: it's a function of a specific
    // candidate's CV, and there's no fixed, enumerable set of candidates to precompute it for at
    // import time - it still gets computed (and cached) the first time any candidate actually
    // requests it, same as before, just now against already-complete job data from the start.
    private void prepareJobContent(List<ExternalJob> jobs) {
        for (ExternalJob job : jobs) {
            populateRequirementsAndSkills(job);
            populateAboutSummaryAllLanguages(job);
        }
    }

    // Lazily backfills ExternalJob#requirements/#skills the first time this specific job's match
    // detail is requested (see getMatchDetailForExternalJob), for any job that missed the
    // proactive import-time extraction below (see prepareJobContent) - e.g. a job imported before
    // this existed, or whose import-time extraction failed.
    private void ensureRequirementsAndSkills(ExternalJob job) {
        if (populateRequirementsAndSkills(job)) {
            externalJobRepository.save(job);
        }
    }

    // Extracts and sets requirements/skills in memory only (no save) - shared by the lazy
    // per-request backfill above and the batch import-time preparation below, which need the
    // exact same extraction logic but save on different schedules (immediately vs. batched with
    // the rest of an import cycle). Returns whether anything actually changed, so callers only
    // pay for a save when there's something new to persist.
    //
    // Every provider today leaves requirements/skills blank (see
    // JobicyJobProvider#resolveDescription), so the match-scoring prompt was showing the AI
    // "Required skills: N/A" / "Requirements: N/A" even for postings whose full description
    // clearly states real requirements, which measurably made the AI less likely to return a
    // matched/missing-skills breakdown at all for jobs whose description reads as narrative prose
    // rather than an obviously bulleted skills list (confirmed via production data: internal jobs,
    // which always have a company-typed skills/requirements field, essentially never come back
    // with empty skill arrays; external jobs frequently did).
    private boolean populateRequirementsAndSkills(ExternalJob job) {
        if (!nullToEmpty(job.getRequirements()).isBlank() || !nullToEmpty(job.getSkills()).isBlank()) {
            return false;
        }

        String description = nullToEmpty(job.getDescription());
        if (description.isBlank()) {
            return false;
        }

        // Best-effort only. A production incident (see the "value too long for type character
        // varying(255)" case that motivated this try/catch - the columns were still their old
        // JPA-default width when this first shipped) proved that an uncaught failure HERE was
        // taking the entire match-detail request down with it, denying the candidate their match
        // score/skills breakdown entirely over something that has nothing to do with computing
        // it. Any failure here - AI error, JSON parse failure, a future DB constraint, anything -
        // must never prevent the real match computation, or the rest of an import batch, from
        // proceeding; the next view (or the next import cycle's changedJobs pass) simply retries
        // this (still-blank) extraction from scratch.
        try {
            String raw = openAICVAnalysisService.extractRequirementsAndSkills(
                    job.getTitle(), job.getCompanyName(), description);
            Map<String, Object> parsed = parseJsonToMap(raw);
            if (parsed == null) {
                return false;
            }

            String requirements = String.valueOf(parsed.getOrDefault("requirements", "")).trim();
            String skills = String.valueOf(parsed.getOrDefault("skills", "")).trim();
            if (requirements.isBlank() && skills.isBlank()) {
                // Generation failed, returned nothing usable, or the posting genuinely names no
                // concrete requirements/skills - don't write empty strings as a "cached" result,
                // so the next attempt retries instead of a transient failure permanently looking
                // identical to "this posting truly has none".
                return false;
            }

            job.setRequirements(requirements.isBlank() ? null : requirements);
            job.setSkills(skills.isBlank() ? null : skills);
            return true;
        } catch (Exception e) {
            log.warn("Failed to extract requirements/skills for external job id={} - match scoring will proceed without them",
                    job.getId(), e);
            return false;
        }
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

    // country/city aren't stored (see ExternalJob) - filled in here from the single import
    // config value and the location string, so every read path returns the same shape the
    // API always has. Mutates the loaded (already-detached, no active transaction on these
    // read-only methods) entity in memory only - never re-persisted, exactly like the
    // country/city transient fields below.
    private void populateTransientLocationFields(ExternalJob job) {
        job.setCountry(defaultCountry.toUpperCase());

        // Jobicy's own "jobGeo" text (e.g. "EMEA", "EMEA, LATAM", "Cochia, Europe, Germany,
        // Israel, Netherlands, UK") is real data, but reads as "this job is based in other
        // countries" to a candidate scanning the list - even though isIsraelOrRemote already
        // established every Jobicy job is remote AND Israel-eligible by construction. Showing
        // the plain, unambiguous "Remote" label here is what makes that fact visible instead of
        // silently correct-but-confusing.
        if ("Jobicy".equalsIgnoreCase(job.getSourceName())) {
            job.setLocation("Remote");
        }

        job.setCity(job.getLocation());
    }

    public JobMatchService.MatchDetailResult getMatchDetailForExternalJob(
            String email, Long externalJobId, String language) {

        ExternalJob externalJob = externalJobRepository.findById(externalJobId).orElse(null);
        if (externalJob == null) {
            return new JobMatchService.MatchDetailResult(
                    false, externalJobId, null, null, List.of(), List.of(), List.of(), List.of(), List.of(), null, null,
                    null, null, null, null, null, null, null, null, List.of(), List.of(),
                    null, null, null, null, null, null, List.of(), List.of());
        }

        ensureRequirementsAndSkills(externalJob);

        Job transientJob = new Job(
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
        transientJob.setId(EXTERNAL_ID_OFFSET + externalJob.getId());

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
                result.languageMatchPercent(),
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
                result.matchedPreferredSkills()
        );
    }

    // Thin delegate so ExternalJobController's streaming endpoint can emit its "no-analysis"
    // event up front without needing JobMatchService/CVAnalysisRepository injected directly.
    public boolean hasAnalysis(String email) {
        return jobMatchService.hasAnalysis(email);
    }

    public JobMatchService.MatchScoresResult getMatchScoresForExternalJobs(
            String email, List<Long> externalJobIds, String language) {

        List<ExternalJob> externalJobs = externalJobIds.isEmpty()
                ? List.of()
                : externalJobRepository.findAllById(externalJobIds);

        List<Job> transientJobs = new ArrayList<>();
        for (ExternalJob externalJob : externalJobs) {
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
            transientJobs.add(job);
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

    // Streaming counterpart of getMatchScoresForExternalJobs (see ExternalJobController's SSE
    // endpoint) - same transient-Job-wrapper/EXTERNAL_ID_OFFSET pattern, but additionally passes
    // each job's cached content embedding through to JobMatchService's pre-filter, and remaps the
    // offset id back down to the real external id on every callback instead of on a whole
    // response at once.
    public void streamMatchScoresForExternalJobs(
            String email, List<Long> externalJobIds, String language,
            java.util.function.BiConsumer<Long, Map<String, Object>> onJobResult, Runnable onComplete) {

        List<ExternalJob> externalJobs = externalJobIds == null || externalJobIds.isEmpty()
                ? List.of()
                : externalJobRepository.findAllById(externalJobIds);

        List<Job> transientJobs = new ArrayList<>();
        Map<Long, float[]> jobEmbeddings = new HashMap<>();

        for (ExternalJob externalJob : externalJobs) {
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
            long offsetId = EXTERNAL_ID_OFFSET + externalJob.getId();
            job.setId(offsetId);
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

    // Unattended, unmonitored entry point (no controller/caller to report a failure to) - unlike
    // ExternalJobController's /import endpoint, which returns its failure straight to whoever
    // triggered it, an uncaught exception here would only ever surface as a generic
    // "TaskUtils$LoggingErrorHandler" log line with no indication of which country/cycle failed
    // or whether pruning still ran. This wraps the whole cycle so a failure is diagnosable from
    // logs alone, and mirrors the /import endpoint's own try/catch (see
    // ExternalJobController#importJobs) but with the specific context (country, result counts)
    // that only matters for an unattended run.
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
            pruneExpiredJobs(result);
        } catch (Exception e) {
            log.error("Scheduled external job retention pruning failed (country={})", defaultCountry, e);
        }
    }

    // A job's importedAt only refreshes when it reappears in a fresh fetch (see
    // importFromProviders), so naive age-based pruning has a real false-positive risk: a job that
    // temporarily falls out of a provider's top-50 ranked results for a few cycles - or a cycle
    // where a provider call transiently fails - gets treated exactly like a genuinely expired
    // posting, even though it may still be live. Two mitigations, deliberately simple rather than
    // adding per-job "missed cycle" tracking:
    //   1. retentionDays itself is generous (21 days by default) relative to the import cadence
    //      (every 6 hours = ~84 cycles) - a job has to be absent from every single fetch for the
    //      entire retention window, not just a handful of cycles, before it's ever a pruning
    //      candidate at all.
    //   2. If this cycle's import fetched suspiciously few results overall (a strong signal of a
    //      broad outage - e.g. every provider failing, or a network-level problem this cycle),
    //      skip pruning entirely this run rather than pruning against a fetch that had little
    //      chance to confirm which postings are still live. Pruning simply waits for the next,
    //      hopefully-healthy cycle instead.
    private static final int MIN_FETCHED_TO_TRUST_PRUNING = 1;

    private void pruneExpiredJobs(ImportResult result) {
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

        // Every persisted/queued match-score artifact for these jobs must go with them - a
        // pruned job can never be recomputed for, so leaving these behind would just be permanent
        // orphans. External jobs use the EXTERNAL_ID_OFFSET convention for JobMatchScore.jobId
        // (see the class-level comment) but NOT for MatchScoreJob, which instead has its own
        // jobType column - see MatchScoreJob's own comment for why the two differ.
        List<Long> offsetIds = idsToPrune.stream().map(id -> EXTERNAL_ID_OFFSET + id).toList();
        jobMatchScoreRepository.deleteByJobIdIn(offsetIds);
        jobMatchNarrativeRepository.deleteByJobIdIn(offsetIds);
        matchScoreJobRepository.deleteByJobIdInAndJobType(idsToPrune, "external");

        // Not wrapped in one @Transactional spanning all three deletes: pruneExpiredJobs is a
        // private method invoked via self-call from the @Scheduled scheduledImport() - Spring's
        // proxy-based AOP can't intercept that invocation, so a @Transactional annotation here
        // would silently do nothing rather than actually group these into one transaction. Each
        // delete above is still individually atomic (Spring Data wraps every repository method in
        // its own transaction), and this whole cycle is idempotent and re-run every 6 hours, so a
        // crash in the exact window between these calls only ever means the next cycle finishes
        // what this one started - never a stuck, unrecoverable state.
        externalJobRepository.deleteAllByIdInBatch(idsToPrune);

        int deleted = idsToPrune.size();
        if (deleted > 0) {
            log.info("Retention pruning removed {} external job(s) not re-imported since before {}.", deleted, cutoff);
        }
    }

    /**
     * Runs once, right after the app finishes starting up, so the external_jobs table isn't
     * sitting empty until the first 6-hour @Scheduled tick fires. Only imports if the table is
     * currently empty, so restarts don't re-hit provider APIs when jobs already exist.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void importOnStartupIfEmpty() {
        if (externalJobRepository.count() == 0) {
            importJobs(null, defaultCountry);
        }
    }

    /**
     * One-time-per-boot catch-up for rows imported before the embedding pre-filter existed (or
     * any row whose embedding call failed at import time) - cheap no-op once nothing is missing,
     * so it's safe to run on every startup rather than only once ever.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void backfillMissingEmbeddingsOnStartup() {
        List<ExternalJob> missing = externalJobRepository.findByContentEmbeddingIsNull();
        if (missing.isEmpty()) {
            return;
        }
        attachEmbeddings(missing);
        externalJobRepository.saveAll(missing);
    }
}
