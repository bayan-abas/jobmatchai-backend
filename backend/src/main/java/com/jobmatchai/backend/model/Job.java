package com.jobmatchai.backend.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "jobs")
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;
    private String companyName;
    private String companyEmail;

    private String location;
    private String type;
    private String salary;

    @Column(length = 2000)
    private String description;

    private String requirements;
    private String skills;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    // Cached OpenAI embedding of this posting's own content (title + description) - backs the
    // deterministic, keyword-free pre-filter in JobMatchService that decides whether a job is
    // even worth an AI classification call, mirroring ExternalJob's identical fields. Lazily
    // computed and fingerprinted the same way (see JobMatchService#ensureInternalJobEmbeddings):
    // only recomputed when the title/description text or the embedding model/dimensions change.
    @Column(name = "content_embedding", columnDefinition = "TEXT")
    private String contentEmbedding;

    @Column(name = "content_embedding_hash")
    private String contentEmbeddingHash;

    @Column(name = "content_embedding_model")
    private String contentEmbeddingModel;

    // Defaults every newly-constructed Job (via this field initializer, for addJob's brand-new
    // Job()) to ACTIVE - a job is open for applications unless a company explicitly closes it.
    // Deliberately NO "default" in columnDefinition, even though the column itself does carry a
    // DB-level default (see db/migration/add_job_status.sql, which is what actually adds it on an
    // already-populated table) - reproduced live against a real Postgres database: once the
    // column exists, Hibernate's ddl-auto=update re-evaluates it on every subsequent boot and,
    // seeing "default" in columnDefinition, re-issues
    // "alter table jobs alter column status set data type varchar(20) default 'ACTIVE'" - which
    // Postgres rejects outright ("syntax error at or near default", since ALTER COLUMN ... SET
    // DATA TYPE cannot carry a DEFAULT clause in the same statement; that requires a separate SET
    // DEFAULT). Leaving "default" out here stops Hibernate from retrying that broken statement on
    // every restart - the column's actual DB-level default (set once, by the migration) and this
    // field's own initializer together still guarantee every row - old and new - resolves to
    // ACTIVE without it being declared here too.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(20)")
    private JobStatus status = JobStatus.ACTIVE;

    public Job() {}

    public Job(String title, String companyName, String companyEmail,
               String location, String type, String salary,
               String description, String requirements, String skills) {

        this.title = title;
        this.companyName = companyName;
        this.companyEmail = companyEmail;
        this.location = location;
        this.type = type;
        this.salary = salary;
        this.description = description;
        this.requirements = requirements;
        this.skills = skills;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public String getCompanyEmail() {
        return companyEmail;
    }

    public void setCompanyEmail(String companyEmail) {
        this.companyEmail = companyEmail;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
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

    public JobStatus getStatus() {
        return status;
    }

    public void setStatus(JobStatus status) {
        this.status = status;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}