package com.jobmatchai.backend.model;

import jakarta.persistence.*;

@Entity
@Table(name = "candidate_ai_summary")
public class CandidateAiSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "candidate_email")
    private String candidateEmail;

    @Column(name = "job_id")
    private Long jobId;

    @Column(name = "professional_background", columnDefinition = "TEXT")
    private String professionalBackground;

    @Column(name = "key_skills", columnDefinition = "TEXT")
    private String keySkills;

    @Column(name = "years_of_experience")
    private String yearsOfExperience;

    @Column(columnDefinition = "TEXT")
    private String strengths;

    @Column(columnDefinition = "TEXT")
    private String weaknesses;

    @Column(name = "overall_suitability", columnDefinition = "TEXT")
    private String overallSuitability;

    @Column(name = "match_score")
    private Integer matchScore;

    @Column(name = "cv_fingerprint")
    private String cvFingerprint;

    @Column(name = "job_fingerprint")
    private String jobFingerprint;

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

    public String getProfessionalBackground() {
        return professionalBackground;
    }

    public void setProfessionalBackground(String professionalBackground) {
        this.professionalBackground = professionalBackground;
    }

    public String getKeySkills() {
        return keySkills;
    }

    public void setKeySkills(String keySkills) {
        this.keySkills = keySkills;
    }

    public String getYearsOfExperience() {
        return yearsOfExperience;
    }

    public void setYearsOfExperience(String yearsOfExperience) {
        this.yearsOfExperience = yearsOfExperience;
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

    public Integer getMatchScore() {
        return matchScore;
    }

    public void setMatchScore(Integer matchScore) {
        this.matchScore = matchScore;
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
