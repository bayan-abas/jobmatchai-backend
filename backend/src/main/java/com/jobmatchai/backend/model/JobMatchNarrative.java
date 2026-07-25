package com.jobmatchai.backend.model;

import jakarta.persistence.*;

@Entity
@Table(name = "job_match_narratives", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"candidate_email", "job_id", "language"})
})
public class JobMatchNarrative {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "candidate_email")
    private String candidateEmail;

    @Column(name = "job_id")
    private Long jobId;

    @Column(name = "language")
    private String language;

    @Column(name = "match_reason", columnDefinition = "TEXT")
    private String matchReason;

    @Column(name = "why_good_match", columnDefinition = "TEXT")
    private String whyGoodMatch;

    @Column(name = "why_not_perfect_match", columnDefinition = "TEXT")
    private String whyNotPerfectMatch;

    @Column(name = "improvement_suggestions", columnDefinition = "TEXT")
    private String improvementSuggestions;

    @Column(name = "recommendation", columnDefinition = "TEXT")
    private String recommendation;

    @Column(name = "cv_fingerprint")
    private String cvFingerprint;

    @Column(name = "job_fingerprint")
    private String jobFingerprint;

    @Column(name = "detail_prompt_version")
    private Integer detailPromptVersion;

    public JobMatchNarrative() {}

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

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getMatchReason() {
        return matchReason;
    }

    public void setMatchReason(String matchReason) {
        this.matchReason = matchReason;
    }

    public String getWhyGoodMatch() {
        return whyGoodMatch;
    }

    public void setWhyGoodMatch(String whyGoodMatch) {
        this.whyGoodMatch = whyGoodMatch;
    }

    public String getWhyNotPerfectMatch() {
        return whyNotPerfectMatch;
    }

    public void setWhyNotPerfectMatch(String whyNotPerfectMatch) {
        this.whyNotPerfectMatch = whyNotPerfectMatch;
    }

    public String getImprovementSuggestions() {
        return improvementSuggestions;
    }

    public void setImprovementSuggestions(String improvementSuggestions) {
        this.improvementSuggestions = improvementSuggestions;
    }

    public String getRecommendation() {
        return recommendation;
    }

    public void setRecommendation(String recommendation) {
        this.recommendation = recommendation;
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

    public Integer getDetailPromptVersion() {
        return detailPromptVersion;
    }

    public void setDetailPromptVersion(Integer detailPromptVersion) {
        this.detailPromptVersion = detailPromptVersion;
    }
}
