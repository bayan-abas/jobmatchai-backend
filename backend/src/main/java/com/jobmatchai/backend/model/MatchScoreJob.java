package com.jobmatchai.backend.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

// The persistent work queue backing background match-score computation (see
// service.MatchScoreQueueService / service.MatchScoreQueueWorker). A row here represents "this
// candidate+job+jobType needs a fresh AI comparison" - claimed by a worker via SELECT ... FOR
// UPDATE SKIP LOCKED (see MatchScoreJobRepository#claimBatch), which is what makes claiming safe
// across multiple concurrent worker threads AND multiple app instances without needing an
// external broker: two workers racing for the same row can never both win it. The result of a
// completed job is NOT stored here - it's written straight to JobMatchScore (the durable,
// fingerprint-cached result every reader already queries) and this row is deleted; only FAILED
// rows (after exhausting retries) are kept, for observability.
@Entity
@Table(name = "match_score_jobs",
        indexes = {
                @Index(name = "idx_msj_status_available", columnList = "status, available_at"),
                @Index(name = "idx_msj_candidate_job", columnList = "candidate_email, job_id, job_type")
        },
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"candidate_email", "job_id", "job_type"})
        })
public class MatchScoreJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "candidate_email", nullable = false)
    private String candidateEmail;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    // "internal" or "external" - which repository/service owns this job id, so the worker knows
    // how to load it (JobRepository vs ExternalJobService's transient-Job wrapper).
    @Column(name = "job_type", nullable = false)
    private String jobType;

    @Column(name = "language")
    private String language;

    // Informational only - the worker always re-derives the CURRENT fingerprint fresh at process
    // time rather than trusting this snapshot, so a CV/job edit between enqueue and processing is
    // never scored against stale data.
    @Column(name = "cv_fingerprint")
    private String cvFingerprint;

    @Column(name = "job_fingerprint")
    private String jobFingerprint;

    // A content snapshot of the job at enqueue time, captured directly from the Job object the
    // enqueueing request already had in hand - deliberately NOT a foreign key the worker resolves
    // later. This is what lets the worker process a row without re-fetching the job (internal
    // jobs via JobRepository, external ones via ExternalJobService's offset-id transient-Job
    // wrapper) at all, which would otherwise require JobMatchService to depend on
    // ExternalJobService - a circular dependency, since ExternalJobService already depends on
    // JobMatchService. A snapshot a few seconds stale is a non-issue: if the job's real content
    // changed in that window, the next time any candidate views it the fingerprint simply won't
    // match and it naturally gets recomputed, exactly like any other cache staleness.
    @Column(name = "job_title", columnDefinition = "TEXT")
    private String jobTitle;

    @Column(name = "job_company_name", columnDefinition = "TEXT")
    private String jobCompanyName;

    @Column(name = "job_location", columnDefinition = "TEXT")
    private String jobLocation;

    @Column(name = "job_employment_type", columnDefinition = "TEXT")
    private String jobEmploymentType;

    @Column(name = "job_salary", columnDefinition = "TEXT")
    private String jobSalary;

    @Column(name = "job_description", columnDefinition = "TEXT")
    private String jobDescription;

    @Column(name = "job_requirements", columnDefinition = "TEXT")
    private String jobRequirements;

    @Column(name = "job_skills", columnDefinition = "TEXT")
    private String jobSkills;

    // PENDING -> IN_PROGRESS -> (row deleted on success) or -> PENDING again with backoff (retry)
    // or -> FAILED (attempts exhausted).
    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "available_at", nullable = false)
    private LocalDateTime availableAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    public MatchScoreJob() {
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (availableAt == null) {
            availableAt = now;
        }
        if (status == null) {
            status = "PENDING";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

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

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getCvFingerprint() {
        return cvFingerprint;
    }

    public void setCvFingerprint(String cvFingerprint) {
        this.cvFingerprint = cvFingerprint;
    }

    public String getJobFingerprint() {
        return jobFingerprint;
    }

    public void setJobFingerprint(String jobFingerprint) {
        this.jobFingerprint = jobFingerprint;
    }

    public String getJobTitle() {
        return jobTitle;
    }

    public void setJobTitle(String jobTitle) {
        this.jobTitle = jobTitle;
    }

    public String getJobCompanyName() {
        return jobCompanyName;
    }

    public void setJobCompanyName(String jobCompanyName) {
        this.jobCompanyName = jobCompanyName;
    }

    public String getJobLocation() {
        return jobLocation;
    }

    public void setJobLocation(String jobLocation) {
        this.jobLocation = jobLocation;
    }

    public String getJobEmploymentType() {
        return jobEmploymentType;
    }

    public void setJobEmploymentType(String jobEmploymentType) {
        this.jobEmploymentType = jobEmploymentType;
    }

    public String getJobSalary() {
        return jobSalary;
    }

    public void setJobSalary(String jobSalary) {
        this.jobSalary = jobSalary;
    }

    public String getJobDescription() {
        return jobDescription;
    }

    public void setJobDescription(String jobDescription) {
        this.jobDescription = jobDescription;
    }

    public String getJobRequirements() {
        return jobRequirements;
    }

    public void setJobRequirements(String jobRequirements) {
        this.jobRequirements = jobRequirements;
    }

    public String getJobSkills() {
        return jobSkills;
    }

    public void setJobSkills(String jobSkills) {
        this.jobSkills = jobSkills;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public LocalDateTime getAvailableAt() {
        return availableAt;
    }

    public void setAvailableAt(LocalDateTime availableAt) {
        this.availableAt = availableAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }
}
