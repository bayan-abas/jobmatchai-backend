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

    @Value("${externaljobs.import.keywords:software developer}")
    private String defaultKeywords;

    @Value("${externaljobs.import.country:il}")
    private String defaultCountry;

    public record ImportResult(int imported, int skipped, int total) {}

    public ImportResult importJobs(String keywords, String country) {
        String searchKeywords = (keywords == null || keywords.isBlank()) ? defaultKeywords : keywords;
        String searchCountry = (country == null || country.isBlank()) ? defaultCountry : country;

        List<ExternalJobData> fetched = new ArrayList<>();
        for (ExternalJobProvider provider : externalJobProviders) {
            fetched.addAll(provider.fetchJobs(searchKeywords, searchCountry, 50));
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
            job.setCountry(data.country());
            job.setCity(data.city());
            job.setType(data.type());
            job.setSalary(data.salary());
            job.setDescription(data.description());
            job.setRequirements(data.requirements());
            job.setSkills(data.skills());
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
        return externalJobRepository.findAll();
    }

    public java.util.Optional<ExternalJob> getExternalJobById(Long id) {
        return externalJobRepository.findById(id);
    }

    public JobMatchService.MatchDetailResult getMatchDetailForExternalJob(
            String email, Long externalJobId, String language) {

        ExternalJob externalJob = externalJobRepository.findById(externalJobId).orElse(null);
        if (externalJob == null) {
            return new JobMatchService.MatchDetailResult(
                    false, externalJobId, null, null, List.of(), List.of(), List.of(), List.of(), List.of(), null, null,
                    null, null, null, null, null);
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
                result.languageMatchPercent()
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
        importJobs(defaultKeywords, defaultCountry);
    }

    /**
     * Runs once, right after the app finishes starting up, so the external_jobs table isn't
     * sitting empty until the first 6-hour @Scheduled tick fires. Only imports if the table is
     * currently empty, so restarts don't re-hit provider APIs when jobs already exist.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void importOnStartupIfEmpty() {
        if (externalJobRepository.count() == 0) {
            importJobs(defaultKeywords, defaultCountry);
        }
    }
}
