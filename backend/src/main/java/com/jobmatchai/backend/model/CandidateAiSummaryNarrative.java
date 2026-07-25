package com.jobmatchai.backend.model;

import jakarta.persistence.*;

@Entity
@Table(name = "candidate_ai_summary_narratives", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"candidate_email", "job_id", "language"})
})
public class CandidateAiSummaryNarrative {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "candidate_email")
    private String candidateEmail;

    @Column(name = "job_id")
    private Long jobId;

    @Column(name = "language")
    private String language;

    @Column(name = "professional_background", columnDefinition = "TEXT")
    private String professionalBackground;

    @Column(columnDefinition = "TEXT")
    private String strengths;

    @Column(columnDefinition = "TEXT")
    private String weaknesses;

    @Column(name = "overall_suitability", columnDefinition = "TEXT")
    private String overallSuitability;

    @Column(name = "cv_fingerprint")
    private String cvFingerprint;

    @Column(name = "job_fingerprint")
    private String jobFingerprint;

    public CandidateAiSummaryNarrative() {}

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

    public String getProfessionalBackground() {
        return professionalBackground;
    }

    public void setProfessionalBackground(String professionalBackground) {
        this.professionalBackground = professionalBackground;
    }

    public String getStrengths() {
        return strengths;
    }

    public void setStrengths(String strengths) {
        this.strengths = strengths;
    }

    public String getWeaknesses() {
        return weaknesses;
    }

    public void setWeaknesses(String weaknesses) {
        this.weaknesses = weaknesses;
    }

    public String getOverallSuitability() {
        return overallSuitability;
    }

    public void setOverallSuitability(String overallSuitability) {
        this.overallSuitability = overallSuitability;
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
}
