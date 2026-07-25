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

    @Transient
    private String country;

    @Transient
    private String city;

    private String type;
    private String salary;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String aboutSummaryEn;

    @Column(columnDefinition = "TEXT")
    private String aboutSummaryAr;

    @Column(columnDefinition = "TEXT")
    private String aboutSummaryHe;

    @Column(columnDefinition = "TEXT")
    private String requirements;

    @Column(columnDefinition = "TEXT")
    private String skills;

    private String industry;

    private String sourceName;
    private String sourceUrl;
    private String applyUrl;

    private String externalJobId;

    private LocalDateTime importedAt;

    private LocalDateTime publishedAt;

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
