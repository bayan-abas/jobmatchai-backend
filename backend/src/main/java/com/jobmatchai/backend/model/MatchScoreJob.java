package com.jobmatchai.backend.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

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

    @Column(name = "job_type", nullable = false)
    private String jobType;

    @Column(name = "language")
    private String language;

    @Column(name = "cv_fingerprint")
    private String cvFingerprint;

    @Column(name = "job_fingerprint")
    private String jobFingerprint;

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

    // בעת יצירת job חדש בתור - קובע זמן יצירה, זמן זמינות וסטטוס התחלתי אם עוד לא הוגדרו
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

    // מעדכן את חותמת הזמן "עודכן לאחרונה" בכל שינוי של רשומת ה-job
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
