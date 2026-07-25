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

    @Column(name = "content_embedding", columnDefinition = "TEXT")
    private String contentEmbedding;

    @Column(name = "content_embedding_hash")
    private String contentEmbeddingHash;

    @Column(name = "content_embedding_model")
    private String contentEmbeddingModel;

    // בלי "default" ב-columnDefinition בכוונה - עם default, ה-Hibernate מנסה בכל עליה להריץ
    // ALTER COLUMN עם SET DATA TYPE + DEFAULT באותה פקודה, ופוסטגרס דוחה את זה עם שגיאת syntax
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

    // מגדיר את תאריך היצירה אוטומטית לפני השמירה הראשונה במסד, אם עוד לא הוגדר
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
