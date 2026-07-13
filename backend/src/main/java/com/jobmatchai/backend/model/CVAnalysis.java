package com.jobmatchai.backend.model;

import jakarta.persistence.*;

@Entity
@Table(name = "cv_analysis")
public class CVAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_email")
    private String userEmail;

    @Column(name = "candidate_field", columnDefinition = "TEXT")
    private String candidateField;

    // The specific profession/title (e.g. "Doctor", "Registered Nurse", "Information Systems
    // Analyst") - candidateField alone is too coarse a bucket for the job matcher to reliably
    // judge field relevance from (e.g. "healthcare" covers both doctors and nurses, which are
    // related but not interchangeable for licensing purposes).
    @Column(name = "profession_title", columnDefinition = "TEXT")
    private String professionTitle;

    @Column(columnDefinition = "TEXT")
    private String skills;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(columnDefinition = "TEXT")
    private String strengths;

    @Column(name = "missing_skills", columnDefinition = "TEXT")
    private String missingSkills;

    @Column(name = "recommended_roles", columnDefinition = "TEXT")
    private String recommendedRoles;

    @Column(name = "overall_score")
    private Integer overallScore;

    // Structured evidence fields the job matcher relies on for regulated-profession decisions
    // (fieldRelated + education/certification component scores) instead of inferring them from
    // whatever the free-text summary/strengths prose happened to mention.
    @Column(name = "education_evidence")
    private String educationEvidence;

    @Column(name = "certifications_evidence")
    private String certificationsEvidence;

    // Distinct from certificationsEvidence: certifications are a general credential signal (any
    // field), while this is specifically about a LEGAL PRACTICE LICENSE (medical license, bar
    // admission, nursing license, PE license, etc.) - the exact evidence the regulated-profession
    // fieldRelated guardrail needs, that a generic "certifications" bucket would blur.
    @Column(name = "licenses_evidence")
    private String licensesEvidence;

    @Column(name = "years_of_experience")
    private String yearsOfExperience;

    // Bucketed seniority (none/entry_level/mid_level/senior_level), distinct from
    // yearsOfExperience's free-text display string - this is the value JobMatchService's
    // deterministic experience-scoring formula actually compares against a job's required
    // seniority level, since two free-text strings ("8+" vs "senior") can't be compared
    // numerically without this kind of shared, fixed vocabulary on both sides.
    @Column(name = "experience_level")
    private String experienceLevel;

    // Named technologies/tools/domain-specific practical skills, split from softSkills so the
    // job matcher can score "required skills" against a clean, comparable list instead of a
    // mixed bag of hard and soft skills.
    @Column(name = "technical_skills", columnDefinition = "TEXT")
    private String technicalSkills;

    @Column(name = "soft_skills", columnDefinition = "TEXT")
    private String softSkills;

    @Column(columnDefinition = "TEXT")
    private String languages;

    @Column(name = "previous_job_titles", columnDefinition = "TEXT")
    private String previousJobTitles;

    @Column(name = "evaluation_reason", columnDefinition = "TEXT")
    private String evaluationReason;

    @Column(name = "missing_information", columnDefinition = "TEXT")
    private String missingInformation;

    @Column(name = "cv_text_hash")
    private String cvTextHash;

    // Cached OpenAI embedding of this candidate's profile (professionTitle + candidateField +
    // skills + summary) - backs the deterministic, keyword-free pre-filter in JobMatchService
    // that decides whether a job is even worth an AI classification call. Lazily computed and
    // fingerprinted the same way cvTextHash is: profileEmbeddingHash/-Model only match (and this
    // vector only gets reused) when the underlying profile text and embedding model/dimensions
    // are both unchanged - see EmbeddingService#ensureProfileEmbedding.
    @Column(name = "profile_embedding", columnDefinition = "TEXT")
    private String profileEmbedding;

    @Column(name = "profile_embedding_hash")
    private String profileEmbeddingHash;

    @Column(name = "profile_embedding_model")
    private String profileEmbeddingModel;

    public CVAnalysis() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUserEmail() {
        return userEmail;
    }

    public void setUserEmail(String userEmail) {
        this.userEmail = userEmail;
    }

    public String getCandidateField() {
        return candidateField;
    }

    public void setCandidateField(String candidateField) {
        this.candidateField = candidateField;
    }

    public String getSkills() {
        return skills;
    }

    public void setSkills(String skills) {
        this.skills = skills;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getStrengths() {
        return strengths;
    }

    public void setStrengths(String strengths) {
        this.strengths = strengths;
    }

    public String getMissingSkills() {
        return missingSkills;
    }

    public void setMissingSkills(String missingSkills) {
        this.missingSkills = missingSkills;
    }

    public String getRecommendedRoles() {
        return recommendedRoles;
    }

    public void setRecommendedRoles(String recommendedRoles) {
        this.recommendedRoles = recommendedRoles;
    }

    public Integer getOverallScore() {
        return overallScore;
    }

    public void setOverallScore(Integer overallScore) {
        this.overallScore = overallScore;
    }

    public String getEvaluationReason() {
        return evaluationReason;
    }

    public void setEvaluationReason(String evaluationReason) {
        this.evaluationReason = evaluationReason;
    }

    public String getMissingInformation() {
        return missingInformation;
    }

    public void setMissingInformation(String missingInformation) {
        this.missingInformation = missingInformation;
    }

    public String getCvTextHash() {
        return cvTextHash;
    }

    public void setCvTextHash(String cvTextHash) {
        this.cvTextHash = cvTextHash;
    }

    public String getProfileEmbedding() {
        return profileEmbedding;
    }

    public void setProfileEmbedding(String profileEmbedding) {
        this.profileEmbedding = profileEmbedding;
    }

    public String getProfileEmbeddingHash() {
        return profileEmbeddingHash;
    }

    public void setProfileEmbeddingHash(String profileEmbeddingHash) {
        this.profileEmbeddingHash = profileEmbeddingHash;
    }

    public String getProfileEmbeddingModel() {
        return profileEmbeddingModel;
    }

    public void setProfileEmbeddingModel(String profileEmbeddingModel) {
        this.profileEmbeddingModel = profileEmbeddingModel;
    }

    public String getProfessionTitle() {
        return professionTitle;
    }

    public void setProfessionTitle(String professionTitle) {
        this.professionTitle = professionTitle;
    }

    public String getEducationEvidence() {
        return educationEvidence;
    }

    public void setEducationEvidence(String educationEvidence) {
        this.educationEvidence = educationEvidence;
    }

    public String getCertificationsEvidence() {
        return certificationsEvidence;
    }

    public void setCertificationsEvidence(String certificationsEvidence) {
        this.certificationsEvidence = certificationsEvidence;
    }

    public String getLicensesEvidence() {
        return licensesEvidence;
    }

    public void setLicensesEvidence(String licensesEvidence) {
        this.licensesEvidence = licensesEvidence;
    }

    public String getYearsOfExperience() {
        return yearsOfExperience;
    }

    public void setYearsOfExperience(String yearsOfExperience) {
        this.yearsOfExperience = yearsOfExperience;
    }

    public String getExperienceLevel() {
        return experienceLevel;
    }

    public void setExperienceLevel(String experienceLevel) {
        this.experienceLevel = experienceLevel;
    }

    public String getTechnicalSkills() {
        return technicalSkills;
    }

    public void setTechnicalSkills(String technicalSkills) {
        this.technicalSkills = technicalSkills;
    }

    public String getSoftSkills() {
        return softSkills;
    }

    public void setSoftSkills(String softSkills) {
        this.softSkills = softSkills;
    }

    public String getLanguages() {
        return languages;
    }

    public void setLanguages(String languages) {
        this.languages = languages;
    }

    public String getPreviousJobTitles() {
        return previousJobTitles;
    }

    public void setPreviousJobTitles(String previousJobTitles) {
        this.previousJobTitles = previousJobTitles;
    }
}


