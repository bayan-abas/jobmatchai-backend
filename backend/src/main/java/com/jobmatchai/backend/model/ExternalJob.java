package com.jobmatchai.backend.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "external_jobs")
public class ExternalJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;
    private String companyName;

    private String location;

    // Not persisted - every import provider only ever fetches from the single configured
    // country (externaljobs.import.country), and city was either an exact copy of location
    // or unset depending on the provider, so neither was real per-row data worth storing.
    // Populated by ExternalJobService when jobs are read, so the API response shape (and the
    // frontend's country/city filters) stay exactly as they were.
    @Transient
    private String country;

    @Transient
    private String city;

    private String type;
    private String salary;

    // TEXT (not a bounded VARCHAR) - match scoring must see the posting's COMPLETE description,
    // not a truncated excerpt (found via live testing that a real posting's requirements section
    // can start well past character 2000, after a long company-intro paragraph). See
    // JobicyJobProvider#resolveDescription for the one remaining safety ceiling (a generous
    // upper bound against a pathologically huge response, not a normal-case truncation).
    @Column(columnDefinition = "TEXT")
    private String description;

    // AI-generated structured summary of the full description (JSON: roleOverview,
    // responsibilities, requiredQualifications, preferredQualifications, experienceLevel,
    // workArrangement, importantConditions - see OpenAICVAnalysisService#summarizeJobDescription)
    // shown in the frontend's "About this job" section instead of the long raw description,
    // which stays available in full for match scoring. Generated lazily on first request and
    // cached here rather than regenerated on every view - see
    // ExternalJobService#getOrGenerateAboutSummary.
    @Column(columnDefinition = "TEXT")
    private String aboutSummary;

    // sha256(description + "|" + language) at the time aboutSummary was generated - a mismatch
    // (description changed on re-import, or a different language was requested) means the cached
    // summary is stale and must be regenerated.
    private String aboutSummaryContentHash;

    private String requirements;
    private String skills;

    // One of frontend/src/utils/jobInference.ts's INDUSTRY_KEYS, resolved by the provider from
    // its own category/occupation data at import time (see ExternalJobData.industry) - null
    // when the provider gave no such signal, in which case the frontend falls back to
    // title-based classification instead of guessing from this field.
    private String industry;

    private String sourceName;
    private String sourceUrl;
    private String applyUrl;

    private String externalJobId;

    private LocalDateTime importedAt;

    // The provider's own posted/updated date for this listing (see ExternalDateParser) - null
    // when the provider didn't supply one or it didn't parse. Distinct from importedAt (this
    // app's own "when did WE last confirm it's still live" timestamp) - this is what the
    // frontend shows as the job's publication date.
    private LocalDateTime publishedAt;

    // Cached OpenAI embedding of this job's title+description, computed once at import time (see
    // ExternalJobService) - backs the deterministic, keyword-free pre-filter in JobMatchService
    // that decides whether a job is even worth an AI classification call, by cosine similarity
    // against the candidate's own profile embedding (CVAnalysis#profileEmbedding).
    @Column(name = "content_embedding", columnDefinition = "TEXT")
    private String contentEmbedding;

    @Column(name = "content_embedding_hash")
    private String contentEmbeddingHash;

    @Column(name = "content_embedding_model")
    private String contentEmbeddingModel;

    public ExternalJob() {}

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getCompanyName() {
        return companyName;
    }

    public void setCompanyName(String companyName) {
        this.companyName = companyName;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getSalary() {
        return salary;
    }

    public void setSalary(String salary) {
        this.salary = salary;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getRequirements() {
        return requirements;
    }

    public void setRequirements(String requirements) {
        this.requirements = requirements;
    }

    public String getSkills() {
        return skills;
    }

    public void setSkills(String skills) {
        this.skills = skills;
    }

    public String getIndustry() {
        return industry;
    }

    public void setIndustry(String industry) {
        this.industry = industry;
    }

    public String getSourceName() {
        return sourceName;
    }

    public void setSourceName(String sourceName) {
        this.sourceName = sourceName;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public String getApplyUrl() {
        return applyUrl;
    }

    public void setApplyUrl(String applyUrl) {
        this.applyUrl = applyUrl;
    }

    public String getExternalJobId() {
        return externalJobId;
    }

    public void setExternalJobId(String externalJobId) {
        this.externalJobId = externalJobId;
    }

    public LocalDateTime getImportedAt() {
        return importedAt;
    }

    public void setImportedAt(LocalDateTime importedAt) {
        this.importedAt = importedAt;
    }

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(LocalDateTime publishedAt) {
        this.publishedAt = publishedAt;
    }

    public String getContentEmbedding() {
        return contentEmbedding;
    }

    public void setContentEmbedding(String contentEmbedding) {
        this.contentEmbedding = contentEmbedding;
    }

    public String getContentEmbeddingHash() {
        return contentEmbeddingHash;
    }

    public void setContentEmbeddingHash(String contentEmbeddingHash) {
        this.contentEmbeddingHash = contentEmbeddingHash;
    }

    public String getContentEmbeddingModel() {
        return contentEmbeddingModel;
    }

    public void setContentEmbeddingModel(String contentEmbeddingModel) {
        this.contentEmbeddingModel = contentEmbeddingModel;
    }

    public String getAboutSummary() {
        return aboutSummary;
    }

    public void setAboutSummary(String aboutSummary) {
        this.aboutSummary = aboutSummary;
    }

    public String getAboutSummaryContentHash() {
        return aboutSummaryContentHash;
    }

    public void setAboutSummaryContentHash(String aboutSummaryContentHash) {
        this.aboutSummaryContentHash = aboutSummaryContentHash;
    }
}
