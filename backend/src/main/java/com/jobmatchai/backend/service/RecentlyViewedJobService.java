package com.jobmatchai.backend.service;

import com.jobmatchai.backend.model.ExternalJob;
import com.jobmatchai.backend.model.Job;
import com.jobmatchai.backend.model.RecentlyViewedJob;
import com.jobmatchai.backend.repository.ExternalJobRepository;
import com.jobmatchai.backend.repository.JobRepository;
import com.jobmatchai.backend.repository.RecentlyViewedJobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class RecentlyViewedJobService {

    private static final int MAX_RECENTLY_VIEWED = 10;

    @Autowired
    private RecentlyViewedJobRepository recentlyViewedJobRepository;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private ExternalJobRepository externalJobRepository;

    public void recordView(String candidateEmail, Long jobId, String jobType) {
        RecentlyViewedJob row = recentlyViewedJobRepository
                .findByCandidateEmailAndJobIdAndJobType(candidateEmail, jobId, jobType)
                .orElse(new RecentlyViewedJob());

        row.setCandidateEmail(candidateEmail);
        row.setJobId(jobId);
        row.setJobType(jobType);
        row.setViewedAt(LocalDateTime.now());

        recentlyViewedJobRepository.save(row);

        List<RecentlyViewedJob> all = recentlyViewedJobRepository.findByCandidateEmailOrderByViewedAtDesc(candidateEmail);
        if (all.size() > MAX_RECENTLY_VIEWED) {
            recentlyViewedJobRepository.deleteAll(all.subList(MAX_RECENTLY_VIEWED, all.size()));
        }
    }

    // jobTitle/companyName/location aren't stored (see RecentlyViewedJob) - joined here
    // against the live Job/ExternalJob tables instead, batched per jobType so this stays
    // one extra query each rather than one per row.
    public List<RecentlyViewedJob> getRecentlyViewed(String candidateEmail) {
        List<RecentlyViewedJob> rows = recentlyViewedJobRepository.findByCandidateEmailOrderByViewedAtDesc(candidateEmail);

        List<Long> internalIds = rows.stream()
                .filter(row -> "internal".equals(row.getJobType()))
                .map(RecentlyViewedJob::getJobId)
                .toList();
        List<Long> externalIds = rows.stream()
                .filter(row -> "external".equals(row.getJobType()))
                .map(RecentlyViewedJob::getJobId)
                .toList();

        Map<Long, Job> jobsById = new HashMap<>();
        if (!internalIds.isEmpty()) {
            jobRepository.findAllById(internalIds).forEach(job -> jobsById.put(job.getId(), job));
        }

        Map<Long, ExternalJob> externalJobsById = new HashMap<>();
        if (!externalIds.isEmpty()) {
            externalJobRepository.findAllById(externalIds).forEach(job -> externalJobsById.put(job.getId(), job));
        }

        for (RecentlyViewedJob row : rows) {
            if ("internal".equals(row.getJobType())) {
                Job job = jobsById.get(row.getJobId());
                row.setJobTitle(job != null ? job.getTitle() : null);
                row.setCompanyName(job != null ? job.getCompanyName() : null);
                row.setLocation(job != null ? job.getLocation() : null);
            } else {
                ExternalJob job = externalJobsById.get(row.getJobId());
                row.setJobTitle(job != null ? job.getTitle() : null);
                row.setCompanyName(job != null ? job.getCompanyName() : null);
                row.setLocation(job != null ? job.getLocation() : null);
            }
        }

        return rows;
    }
}
