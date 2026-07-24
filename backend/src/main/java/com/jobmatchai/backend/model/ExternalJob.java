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
    // shown in the frontend's "About this job" section instead of the long raw description, which
    // stays available in full for match scoring. One column PER SUPPORTED LANGUAGE (not one column
    // plus a content hash) - the app supports en/ar/he, and a single shared slot meant a candidate
    // viewing in Hebrew right after another viewed in English would silently discard and
    // regenerate over the previous language's cached copy every time. Prepared proactively for all
    // three languages at import time (see ExternalJobService#prepareJobContent); a job imported
    // before this existed, or whose import-time generation failed, still falls back to lazy
    // per-language generation on first request (see ExternalJobService#getOrGenerateAboutSummary),
    // it just won't have already been ready in advance.
    @Column(columnDefinition = "TEXT")
    private String aboutSummaryEn;

    @Column(columnDefinition = "TEXT")
    private String aboutSummaryAr;

    @Column(columnDefinition = "TEXT")
    private String aboutSummaryHe;

    // Populated for every job proactively at import time (see
    // ExternalJobService#prepareJobContent) since no provider currently supplies a separate
    // structured requirements/skills field of its own (see JobicyJobProvider#resolveDescription) -
    // extracted from the raw description via AI and persisted here permanently so every match
    // computation (any candidate, any code path) reads real structured text instead of "N/A". A
    // job imported before this existed, or whose import-time extraction failed, still falls back
    // to a lazy backfill the first time its match detail is requested (see
    // ExternalJobService#ensureRequirementsAndSkills). TEXT (not the JPA default VARCHAR(255))
    // since that default silently caps out far below what a real requirements paragraph needs -
    // description already had to make the same change for the same reason.
    @Column(columnDefinition = "TEXT")
    private String requirements;

    @Column(columnDefinition = "TEXT")
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

    public String getAboutSummaryEn() {
        return aboutSummaryEn;
    }

    public void setAboutSummaryEn(String aboutSummaryEn) {
        this.aboutSummaryEn = aboutSummaryEn;
    }

    public String getAboutSummaryAr() {
        return aboutSummaryAr;
    }

    public void setAboutSummaryAr(String aboutSummaryAr) {
        this.aboutSummaryAr = aboutSummaryAr;
    }

    public String getAboutSummaryHe() {
        return aboutSummaryHe;
    }

    public void setAboutSummaryHe(String aboutSummaryHe) {
        this.aboutSummaryHe = aboutSummaryHe;
    }
}
