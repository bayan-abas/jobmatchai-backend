package com.jobmatchai.backend.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "applications")
public class Application {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long jobId;
    private String jobTitle;
    private String companyName;
    private String companyEmail;

    private String candidateEmail;
    private String candidateName;

    private String status;
    private String appliedDate;

    private LocalDateTime createdAt;

    private Boolean viewedByCompany;
    private LocalDateTime viewedAt;

    @Lob
    private String preInterviewAnswersJson;

    public Application() {}

    public Application(Long jobId, String jobTitle, String companyName, String companyEmail,
                       String candidateEmail, String candidateName,
                       String status, String appliedDate) {
        this.jobId = jobId;
        this.jobTitle = jobTitle;
        this.companyName = companyName;
        this.companyEmail = companyEmail;
        this.candidateEmail = candidateEmail;
        this.candidateName = candidateName;
        this.status = status;
        this.appliedDate = appliedDate;
    }

    public Long getId() {
        return id;
    }

    public Long getJobId() {
        return jobId;
    }

    public void setJobId(Long jobId) {
        this.jobId = jobId;
    }

    public String getJobTitle() {
        return jobTitle;
    }

    public void setJobTitle(String jobTitle) {
        this.jobTitle = jobTitle;
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

    public String getCandidateEmail() {
        return candidateEmail;
    }

    public void setCandidateEmail(String candidateEmail) {
        this.candidateEmail = candidateEmail;
    }

    public String getCandidateName() {
        return candidateName;
    }

    public void setCandidateName(String candidateName) {
        this.candidateName = candidateName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getAppliedDate() {
        return appliedDate;
    }

    public void setAppliedDate(String appliedDate) {
        this.appliedDate = appliedDate;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public boolean isViewedByCompany() {
        return Boolean.TRUE.equals(viewedByCompany);
    }

    public void setViewedByCompany(boolean viewedByCompany) {
        this.viewedByCompany = viewedByCompany;
    }

    public LocalDateTime getViewedAt() {
        return viewedAt;
    }

    public void setViewedAt(LocalDateTime viewedAt) {
        this.viewedAt = viewedAt;
    }

    public String getPreInterviewAnswersJson() {
        return preInterviewAnswersJson;
    }

    public void setPreInterviewAnswersJson(String preInterviewAnswersJson) {
        this.preInterviewAnswersJson = preInterviewAnswersJson;
    }
}