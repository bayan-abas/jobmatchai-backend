
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

    // Combined (mandatory + preferred) lists, kept for backward compatibility with existing
    // readers (AI chat context, frontend job cards). missingRequiredSkills/missingPreferredSkills
    // below are the same data split out for callers that need the mandatory/preferred distinction.
    @Column(name = "matched_skills", columnDefinition = "TEXT")
    private String matchedSkills;

    @Column(name = "missing_skills", columnDefinition = "TEXT")
    private String missingSkills;

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

    // Weighted-scoring components (see MatchScoreCalculator) - matchPercent is the single
    // backend-computed weighted combination of these, not a number the AI invents directly.
    @Column(name = "field_relevance_percent")
    private Integer fieldRelevancePercent;

    @Column(name = "certification_match_percent")
    private Integer certificationMatchPercent;

    @Column(name = "location_match_percent")
    private Integer locationMatchPercent;

    // The per-job fingerprint (jobId + title + company + normalized title + content hash) the
    // AI was asked to echo back for this result, so a stale/misattributed cache row can be told
    // apart from one that was actually validated against the exact job it's attached to.
    @Column(name = "job_content_fingerprint")
    private String jobContentFingerprint;

    // True when this job's own posting text was too thin (title-only, or a one-line description
    // with no real requirements/skills) to support a reliable comparison at all - a deterministic,
    // pre-AI gate (see JobMatchService#isInsufficientJobData), never an AI judgment call. Distinct
    // from fieldRelated=null (JobMatchService's transient "AI call failed, please retry" sentinel,
    // which is NEVER persisted): this is a real, stable, cacheable verdict about the JOB POSTING
    // itself, not a failure - a candidate never gets asked to "retry" a job that will never have
    // enough content to score, and matchPercent/skills/component fields are correctly left null
    // rather than a fabricated-looking number computed from almost nothing.
    @Column(name = "insufficient_data")
    private Boolean insufficientData;

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
}
