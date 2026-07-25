package com.jobmatchai.backend.model;

import jakarta.persistence.*;

@Entity
@Table(name = "cv_analysis_cache", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"cv_text_hash", "language", "prompt_version"})
})
public class CVAnalysisCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cv_text_hash")
    private String cvTextHash;

    @Column(name = "language")
    private String language;

    @Column(name = "prompt_version")
    private String promptVersion;

    @Column(name = "candidate_field", columnDefinition = "TEXT")
    private String candidateField;

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

    @Column(name = "education_evidence")
    private String educationEvidence;

    @Column(name = "certifications_evidence")
    private String certificationsEvidence;

    @Column(name = "licenses_evidence")
    private String licensesEvidence;

    @Column(name = "years_of_experience")
    private String yearsOfExperience;

    @Column(name = "experience_level")
    private String experienceLevel;

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

    public CVAnalysisCache() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCvTextHash() {
        return cvTextHash;
    }

    public void setCvTextHash(String cvTextHash) {
        this.cvTextHash = cvTextHash;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
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
