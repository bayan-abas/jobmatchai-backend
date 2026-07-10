package com.jobmatchai.backend.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "recently_viewed_jobs", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"candidate_email", "job_id", "job_type"})
})
public class RecentlyViewedJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String candidateEmail;
    private Long jobId;
    private String jobType;

    // Not persisted - derivable via a (jobId, jobType) join to Job/ExternalJob, and a
    // "recently viewed" breadcrumb should reflect the job's current details anyway, not a
    // snapshot from whenever it was viewed. Populated by RecentlyViewedJobService on read.
    @Transient
    private String jobTitle;

    @Transient
    private String companyName;

    @Transient
    private String location;

    private LocalDateTime viewedAt;

    public RecentlyViewedJob() {}

    public Long getId() {
        return id;
    }

    public String getCandidateEmail() {
        return candidateEmail;
    }

    public void setCandidateEmail(String candidateEmail) {
        this.candidateEmail = candidateEmail;
    }

    public Long getJobId() {
        return jobId;
    }

    public void setJobId(Long jobId) {
        this.jobId = jobId;
    }

    public String getJobType() {
        return jobType;
    }

    public void setJobType(String jobType) {
        this.jobType = jobType;
    }

    public String getJobTitle() {
        return jobTitle;
    }

    public void setJobTitle(String jobTitle) {
        this.jobTitle = jobTitle;
    }

    public String getCompanyName() {
        return companyName;
    }

    public void setCompanyName(String companyName) {
        this.companyName = companyName;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public LocalDateTime getViewedAt() {
        return viewedAt;
    }

    public void setViewedAt(LocalDateTime viewedAt) {
        this.viewedAt = viewedAt;
    }
}
