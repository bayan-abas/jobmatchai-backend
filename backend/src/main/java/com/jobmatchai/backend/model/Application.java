package com.jobmatchai.backend.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

// The candidate-facing check-then-insert (see ApplicationController.applyToJob) is not
// atomic under concurrency - a double-click or two overlapping requests could both pass
// the "already applied" check before either insert lands. This constraint is the actual
// backstop against duplicate applications for the same job.
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

    // TEXT, not @Lob - see contactMessage's comment below: @Lob on a String maps to a Postgres
    // oid (large object reference), and reading any row with a real value back throws
    // "Large Objects may not be used in auto-commit mode" at the JDBC level. Reproduced live:
    // this field was the SECOND instance of the exact mistake contactMessage/rejectionReason
    // were already fixed for - it broke the company's own applications list (500) the moment a
    // candidate submitted real pre-interview answers. See ApplicationSchemaConfig for the
    // migration of any rows already written under the old oid-backed column.
    @Column(columnDefinition = "TEXT")
    private String preInterviewAnswersJson;

    // Set only when a company accepts the application (see
    // ApplicationController#updateStatus) - one of ApplicationController's
    // ALLOWED_CONTACT_METHODS ("phone_call", "email", "whatsapp", "linkedin",
    // "in_person_meeting", "other"). contactMethodOther holds the company's custom text when
    // contactMethod is "other"; null in every other case. contactMessage is an optional free-text
    // note from the company (e.g. when they'll reach out, next steps, interview/onboarding
    // instructions) - shown to the candidate and included in the acceptance notification.
    private String contactMethod;
    private String contactMethodOther;

    // TEXT, not @Lob - found via live testing that @Lob on a String maps to a Postgres oid
    // (large object reference) here rather than plain text, and reading any row with a real
    // value back throws at the JDBC level. columnDefinition = "TEXT" is the pattern already
    // used correctly elsewhere in this codebase (ExternalJob#description/aboutSummary,
    // EmailVerificationCode) - this field was the one inconsistency.
    @Column(columnDefinition = "TEXT")
    private String contactMessage;

    // Set only when a company rejects the application (see ApplicationController#updateStatus) -
    // mandatory, company-written free text, never AI-generated or a generic template. Preserved
    // exactly as entered - shown to the candidate under "Reason for rejection" and included in
    // the rejection notification. TEXT, matching contactMessage's own fix above.
    @Column(columnDefinition = "TEXT")
    private String rejectionReason;

    // Not persisted - the underlying Job's CURRENT status (see JobStatus), looked up and set by
    // ApplicationController#getApplicationsByCandidate at read time so a candidate's "My
    // Applications" list can show a Closed badge for an application whose job has since closed,
    // without ever needing a real FK/join from Application to Job (there isn't one - jobId is a
    // bare Long everywhere else in this class). Null when the referenced job no longer exists at
    // all (e.g. deleted), same as before this field existed.
    @Transient
    private String jobStatus;

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

    // Not a persisted column - the API response shape (and every existing frontend caller)
    // still expects an "appliedDate" field, so it's computed from createdAt on the fly
    // instead of being a second, separately-stored copy of the same date.
    public String getAppliedDate() {
        return createdAt == null ? null : createdAt.toLocalDate().toString();
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
}