package com.jobmatchai.backend.service;

import com.jobmatchai.backend.model.ExternalJob;
import com.jobmatchai.backend.model.Job;
import com.jobmatchai.backend.repository.ExternalJobRepository;
import com.jobmatchai.backend.service.provider.ExternalJobData;
import com.jobmatchai.backend.service.provider.ExternalJobProvider;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ExternalJobService {

    /**
     * Offset added to an ExternalJob's real id when building a transient Job wrapper for
     * JobMatchService, so cached rows in job_match_scores (keyed by a plain Long jobId with
     * no foreign key) never collide with real internal Job ids.
     */
    private static final long EXTERNAL_ID_OFFSET = 1_000_000_000L;

    @Autowired
    private ExternalJobRepository externalJobRepository;

    /**
     * Spring auto-collects every ExternalJobProvider bean here, so all active sources (Jooble,
     * JSearch, ...) are queried and merged on each import. Adding a new provider later just
     * means adding a new @Component implementing ExternalJobProvider - nothing here changes.
     */
    @Autowired
    private List<ExternalJobProvider> externalJobProviders;

    @Autowired
    private JobMatchService jobMatchService;

    // The full set of categories imported every cycle (see importAllCategories) - covers the
    // general Israeli job market broadly, not just tech, plus explicit remote/work-from-home
    // variants so remote postings surface across categories rather than only through Jobicy
    // (which is itself a tech-leaning remote-jobs feed - see JobicyJobProvider). Deliberately a
    // config property (not a constant) so it can be extended/tuned per deployment without a
    // code change.
    @Value("${externaljobs.import.keywords:software developer,web developer,project manager,accountant,mechanical engineer,graphic designer,electrician,registered nurse,teacher," +
            "retail sales associate,cashier,customer service representative,sales representative,administrative assistant,warehouse worker,logistics coordinator,driver,transportation," +
            "hospitality staff,hotel receptionist,healthcare assistant,cleaner,construction worker,manufacturing worker," +
            "remote customer service,remote software developer,work from home}")
    private String categoryKeywordsCsv;

    @Value("${externaljobs.import.country:il}")
    private String defaultCountry;

    // Real job postings expire. Without this, external_jobs only ever grows - every import
    // cycle adds more rows on top of previously-imported (likely long-expired) postings, which
    // both keeps showing the candidate stale/dead listings forever and makes every full
    // CV-match recompute (see JobMatchService) slower over time since it has to score an
    // ever-growing table instead of a realistically-sized current one.
    @Value("${externaljobs.retention.days:21}")
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
            fetched.addAll(provider.fetchJobs(keywords, country, 50));
        }

        int imported = 0;
        int skipped = 0;

        for (ExternalJobData data : fetched) {
            boolean alreadyExists = externalJobRepository.existsByExternalJobIdOrApplyUrl(
                    data.externalId(), data.applyUrl());

            if (alreadyExists) {
                skipped++;
                continue;
            }

            ExternalJob job = new ExternalJob();
            job.setExternalJobId(data.externalId());
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
            job.setImportedAt(LocalDateTime.now());

            externalJobRepository.save(job);
            imported++;
        }

        return new ImportResult(imported, skipped, fetched.size());
    }

    public List<ExternalJob> getAllExternalJobs() {
        List<ExternalJob> jobs = externalJobRepository.findAllByOrderByImportedAtDesc();
        jobs.forEach(this::populateTransientLocationFields);
        return jobs;
    }

    public java.util.Optional<ExternalJob> getExternalJobById(Long id) {
        return externalJobRepository.findById(id).map(job -> {
            populateTransientLocationFields(job);
            return job;
        });
    }

    // country/city aren't stored (see ExternalJob) - filled in here from the single import
    // config value and the location string, so every read path returns the same shape the
    // API always has.
    private void populateTransientLocationFields(ExternalJob job) {
        job.setCountry(defaultCountry.toUpperCase());
        job.setCity(job.getLocation());
    }

    public JobMatchService.MatchDetailResult getMatchDetailForExternalJob(
            String email, Long externalJobId, String language) {

        ExternalJob externalJob = externalJobRepository.findById(externalJobId).orElse(null);
        if (externalJob == null) {
            return new JobMatchService.MatchDetailResult(
                    false, externalJobId, null, null, List.of(), List.of(), List.of(), List.of(), List.of(), null, null,
                    null, null, null, null, null, null, null, null, List.of(), List.of());
        }

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
                result.missingPreferredSkills()
        );
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

    @Scheduled(cron = "${externaljobs.import.schedule-cron:0 0 */6 * * *}")
    public void scheduledImport() {
        importJobs(null, defaultCountry);
        pruneExpiredJobs();
    }

    private void pruneExpiredJobs() {
        if (retentionDays <= 0) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        externalJobRepository.deleteByImportedAtBefore(cutoff);
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
}
