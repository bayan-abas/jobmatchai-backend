package com.jobmatchai.backend.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "applications", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"candidate_email", "job_id"})
})
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

    private LocalDateTime createdAt;

    private Boolean viewedByCompany;
    private LocalDateTime viewedAt;

    @Column(columnDefinition = "TEXT")
    private String preInterviewAnswersJson;

    private String contactMethod;
    private String contactMethodOther;

    @Column(columnDefinition = "TEXT")
    private String contactMessage;

    @Column(columnDefinition = "TEXT")
    private String rejectionReason;

    @Transient
    private String jobStatus;

    @Transient
    private Interview interview;

    public Application() {}

    public Application(Long jobId, String jobTitle, String companyName, String companyEmail,
                       String candidateEmail, String candidateName,
                       String status) {
        this.jobId = jobId;
        this.jobTitle = jobTitle;
        this.companyName = companyName;
        this.companyEmail = companyEmail;
        this.candidateEmail = candidateEmail;
        this.candidateName = candidateName;
        this.status = status;
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

    // ממיר את תאריך היצירה לפורמט תאריך פשוט (בלי שעה) לתצוגה בצד הלקוח
    public String getAppliedDate() {
        return createdAt == null ? null : createdAt.toLocalDate().toString();
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    // עוטף את השדה הבוליאני כדי ש-null יתפרש כ-false במקום לזרוק שגיאה
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

    public String getContactMethod() {
        return contactMethod;
    }

    public void setContactMethod(String contactMethod) {
        this.contactMethod = contactMethod;
    }

    public String getContactMethodOther() {
        return contactMethodOther;
    }

    public void setContactMethodOther(String contactMethodOther) {
        this.contactMethodOther = contactMethodOther;
    }

    public String getContactMessage() {
        return contactMessage;
    }

    public void setContactMessage(String contactMessage) {
        this.contactMessage = contactMessage;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    public String getJobStatus() {
        return jobStatus;
    }

    public void setJobStatus(String jobStatus) {
        this.jobStatus = jobStatus;
    }

    public Interview getInterview() {
        return interview;
    }

    public void setInterview(Interview interview) {
        this.interview = interview;
    }
}
