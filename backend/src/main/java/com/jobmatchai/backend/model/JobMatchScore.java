
package com.jobmatchai.backend.model;

import jakarta.persistence.*;

@Entity
@Table(name = "job_match_scores", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"candidate_email", "job_id"})
})
public class JobMatchScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "candidate_email")
    private String candidateEmail;

    @Column(name = "job_id")
    private Long jobId;

    @Column(name = "match_percent")
    private Integer matchPercent;

    @Column(name = "match_reason", columnDefinition = "TEXT")
    private String matchReason;

    @Column(name = "matched_skills", columnDefinition = "TEXT")
    private String matchedSkills;

    @Column(name = "missing_skills", columnDefinition = "TEXT")
    private String missingSkills;

    @Column(name = "cv_fingerprint")
    private String cvFingerprint;

    @Column(name = "job_fingerprint")
    private String jobFingerprint;

    @Column(name = "why_good_match", columnDefinition = "TEXT")
    private String whyGoodMatch;

    @Column(name = "why_not_perfect_match", columnDefinition = "TEXT")
    private String whyNotPerfectMatch;

    @Column(name = "improvement_suggestions", columnDefinition = "TEXT")
    private String improvementSuggestions;

    @Column(name = "recommendation", columnDefinition = "TEXT")
    private String recommendation;

    @Column(name = "should_apply")
    private Boolean shouldApply;

    @Column(name = "field_related")
    private Boolean fieldRelated;

    @Column(name = "skills_match_percent")
    private Integer skillsMatchPercent;

    @Column(name = "experience_match_percent")
    private Integer experienceMatchPercent;

    @Column(name = "education_match_percent")
    private Integer educationMatchPercent;

    @Column(name = "language_match_percent")
    private Integer languageMatchPercent;

    public JobMatchScore() {}

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

    public Integer getMatchPercent() {
        return matchPercent;
    }

    public void setMatchPercent(Integer matchPercent) {
        this.matchPercent = matchPercent;
    }

    public String getMatchReason() {
        return matchReason;
    }

    public void setMatchReason(String matchReason) {
        this.matchReason = matchReason;
    }

    public String getMatchedSkills() {
        return matchedSkills;
    }

    public void setMatchedSkills(String matchedSkills) {
        this.matchedSkills = matchedSkills;
    }

    public String getMissingSkills() {
        return missingSkills;
    }

    public void setMissingSkills(String missingSkills) {
        this.missingSkills = missingSkills;
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

    public Boolean getShouldApply() {
        return shouldApply;
    }

    public void setShouldApply(Boolean shouldApply) {
        this.shouldApply = shouldApply;
    }

    public Boolean getFieldRelated() {
        return fieldRelated;
    }

    public void setFieldRelated(Boolean fieldRelated) {
        this.fieldRelated = fieldRelated;
    }

    public Integer getSkillsMatchPercent() {
        return skillsMatchPercent;
    }

    public void setSkillsMatchPercent(Integer skillsMatchPercent) {
        this.skillsMatchPercent = skillsMatchPercent;
    }

    public Integer getExperienceMatchPercent() {
        return experienceMatchPercent;
    }

    public void setExperienceMatchPercent(Integer experienceMatchPercent) {
        this.experienceMatchPercent = experienceMatchPercent;
    }

    public Integer getEducationMatchPercent() {
        return educationMatchPercent;
    }

    public void setEducationMatchPercent(Integer educationMatchPercent) {
        this.educationMatchPercent = educationMatchPercent;
    }

    public Integer getLanguageMatchPercent() {
        return languageMatchPercent;
    }

    public void setLanguageMatchPercent(Integer languageMatchPercent) {
        this.languageMatchPercent = languageMatchPercent;
    }
}
