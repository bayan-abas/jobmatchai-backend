package com.jobmatchai.backend.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

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

    @Column(name = "matched_required_skills", columnDefinition = "TEXT")
    private String matchedRequiredSkills;

    @Column(name = "matched_preferred_skills", columnDefinition = "TEXT")
    private String matchedPreferredSkills;

    @Column(name = "missing_required_skills", columnDefinition = "TEXT")
    private String missingRequiredSkills;

    @Column(name = "missing_preferred_skills", columnDefinition = "TEXT")
    private String missingPreferredSkills;

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

    @Column(name = "detail_prompt_version")
    private Integer detailPromptVersion;

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

    @Column(name = "field_relevance_percent")
    private Integer fieldRelevancePercent;

    @Column(name = "certification_match_percent")
    private Integer certificationMatchPercent;

    @Column(name = "location_match_percent")
    private Integer locationMatchPercent;

    @Column(name = "job_content_fingerprint")
    private String jobContentFingerprint;

    @Column(name = "insufficient_data")
    private Boolean insufficientData;

    @Column(name = "required_experience_level")
    private String requiredExperienceLevel;

    @Column(name = "required_experience_type")
    private String requiredExperienceType;

    @Column(name = "candidate_has_required_experience_type")
    private Boolean candidateHasRequiredExperienceType;

    @Column(name = "required_education_level")
    private String requiredEducationLevel;

    @Column(name = "required_certification_level")
    private String requiredCertificationLevel;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // מעדכן את תאריך העדכון האחרון בכל שמירה או עדכון של רשומת ההתאמה
    @PrePersist
    @PreUpdate
    protected void onSave() {
        updatedAt = LocalDateTime.now();
    }

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

    // הגנה כפולה - אם איכשהו נשמר ערך מחוץ לטווח ב-DB, לא לצאת עם זה החוצה
    public Integer getMatchPercent() {
        return matchPercent == null ? null : Math.max(0, Math.min(100, matchPercent));
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

    public Integer getDetailPromptVersion() {
        return detailPromptVersion;
    }

    public void setDetailPromptVersion(Integer detailPromptVersion) {
        this.detailPromptVersion = detailPromptVersion;
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

    public String getMatchedRequiredSkills() {
        return matchedRequiredSkills;
    }

    public void setMatchedRequiredSkills(String matchedRequiredSkills) {
        this.matchedRequiredSkills = matchedRequiredSkills;
    }

    public String getMatchedPreferredSkills() {
        return matchedPreferredSkills;
    }

    public void setMatchedPreferredSkills(String matchedPreferredSkills) {
        this.matchedPreferredSkills = matchedPreferredSkills;
    }

    public String getMissingRequiredSkills() {
        return missingRequiredSkills;
    }

    public void setMissingRequiredSkills(String missingRequiredSkills) {
        this.missingRequiredSkills = missingRequiredSkills;
    }

    public String getMissingPreferredSkills() {
        return missingPreferredSkills;
    }

    public void setMissingPreferredSkills(String missingPreferredSkills) {
        this.missingPreferredSkills = missingPreferredSkills;
    }

    public Integer getFieldRelevancePercent() {
        return fieldRelevancePercent;
    }

    public void setFieldRelevancePercent(Integer fieldRelevancePercent) {
        this.fieldRelevancePercent = fieldRelevancePercent;
    }

    public Integer getCertificationMatchPercent() {
        return certificationMatchPercent;
    }

    public void setCertificationMatchPercent(Integer certificationMatchPercent) {
        this.certificationMatchPercent = certificationMatchPercent;
    }

    public String getRequiredExperienceLevel() {
        return requiredExperienceLevel;
    }

    public void setRequiredExperienceLevel(String requiredExperienceLevel) {
        this.requiredExperienceLevel = requiredExperienceLevel;
    }

    public String getRequiredExperienceType() {
        return requiredExperienceType;
    }

    public void setRequiredExperienceType(String requiredExperienceType) {
        this.requiredExperienceType = requiredExperienceType;
    }

    public Boolean getCandidateHasRequiredExperienceType() {
        return candidateHasRequiredExperienceType;
    }

    public void setCandidateHasRequiredExperienceType(Boolean candidateHasRequiredExperienceType) {
        this.candidateHasRequiredExperienceType = candidateHasRequiredExperienceType;
    }

    public String getRequiredEducationLevel() {
        return requiredEducationLevel;
    }

    public void setRequiredEducationLevel(String requiredEducationLevel) {
        this.requiredEducationLevel = requiredEducationLevel;
    }

    public String getRequiredCertificationLevel() {
        return requiredCertificationLevel;
    }

    public void setRequiredCertificationLevel(String requiredCertificationLevel) {
        this.requiredCertificationLevel = requiredCertificationLevel;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public Integer getLocationMatchPercent() {
        return locationMatchPercent;
    }

    public void setLocationMatchPercent(Integer locationMatchPercent) {
        this.locationMatchPercent = locationMatchPercent;
    }

    public String getJobContentFingerprint() {
        return jobContentFingerprint;
    }

    public void setJobContentFingerprint(String jobContentFingerprint) {
        this.jobContentFingerprint = jobContentFingerprint;
    }

    public Boolean getInsufficientData() {
        return insufficientData;
    }

    public void setInsufficientData(Boolean insufficientData) {
        this.insufficientData = insufficientData;
    }

    @Transient
    private boolean stale = false;

    public boolean isStale() {
        return stale;
    }

    public void setStale(boolean stale) {
        this.stale = stale;
    }
}
