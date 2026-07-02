package com.jobmatchai.backend.service;

import com.jobmatchai.backend.model.RecentlyViewedJob;
import com.jobmatchai.backend.repository.RecentlyViewedJobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class RecentlyViewedJobService {

    private static final int MAX_RECENTLY_VIEWED = 10;

    @Autowired
    private RecentlyViewedJobRepository recentlyViewedJobRepository;

    public void recordView(String candidateEmail, Long jobId, String jobType,
                            String jobTitle, String companyName, String location) {
        RecentlyViewedJob row = recentlyViewedJobRepository
                .findByCandidateEmailAndJobIdAndJobType(candidateEmail, jobId, jobType)
                .orElse(new RecentlyViewedJob());

        row.setCandidateEmail(candidateEmail);
        row.setJobId(jobId);
        row.setJobType(jobType);
        row.setJobTitle(jobTitle);
        row.setCompanyName(companyName);
        row.setLocation(location);
        row.setViewedAt(LocalDateTime.now().toString());

        recentlyViewedJobRepository.save(row);

        List<RecentlyViewedJob> all = recentlyViewedJobRepository.findByCandidateEmailOrderByViewedAtDesc(candidateEmail);
        if (all.size() > MAX_RECENTLY_VIEWED) {
            recentlyViewedJobRepository.deleteAll(all.subList(MAX_RECENTLY_VIEWED, all.size()));
        }
    }

    public List<RecentlyViewedJob> getRecentlyViewed(String candidateEmail) {
        return recentlyViewedJobRepository.findByCandidateEmailOrderByViewedAtDesc(candidateEmail);
    }
}
