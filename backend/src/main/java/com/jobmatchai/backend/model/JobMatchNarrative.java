package com.jobmatchai.backend.model;

import jakarta.persistence.*;

// Per-language cache of JobMatchScore's narrative text (matchReason, whyGoodMatch,
// whyNotPerfectMatch, improvementSuggestions, recommendation). JobMatchScore itself holds the
// deterministic score fields plus the "canonical" narrative in whatever language first generated
// it - this table holds a translated (or natively-generated) copy per requested language, so a UI
// language switch never has to re-call the scoring AI (which could legitimately return a
// different score on a fresh call) just to get text in a different language. See
// JobMatchService#getMatchDetail/scoreToPayload for the read/write logic.
@Entity
@Table(name = "job_match_narratives", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"candidate_email", "job_id", "language"})
})
public class JobMatchNarrative {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "candidate_email")
    private String candidateEmail;

    @Column(name = "job_id")
    private Long jobId;

    @Column(name = "language")
    private String language;

    @Column(name = "match_reason", columnDefinition = "TEXT")
    private String matchReason;

    @Column(name = "why_good_match", columnDefinition = "TEXT")
    private String whyGoodMatch;

    @Column(name = "why_not_perfect_match", columnDefinition = "TEXT")
    private String whyNotPerfectMatch;

    @Column(name = "improvement_suggestions", columnDefinition = "TEXT")
    private String improvementSuggestions;

    @Column(name = "recommendation", columnDefinition = "TEXT")
    private String recommendation;

    // Must match the parent JobMatchScore's cvFingerprint/jobFingerprint at read time for this
    // row to be considered fresh - otherwise the underlying CV/job content has changed since this
    // language was translated and it must be regenerated.
    @Column(name = "cv_fingerprint")
    private String cvFingerprint;

    @Column(name = "job_fingerprint")
    private String jobFingerprint;

    // Mirrors JobMatchService.DETAIL_PROMPT_VERSION at the time whyGoodMatch/whyNotPerfectMatch/
    // improvementSuggestions/recommendation were last (re)generated for THIS language. Null when
    // only matchReason has been translated for this language and the detail fields haven't been
    // generated/translated yet - a null-or-mismatched value means the detail fields on this row
    // are not (yet) usable, even if matchReason is fine.
    @Column(name = "detail_prompt_version")
    private Integer detailPromptVersion;

    public JobMatchNarrative() {}

    public Long getId() {
        return id;
    }

    public String getCandidateEmail() {
        return candidateEmail;
    }

    public void setCandidateEmail(String candidateEmail) {
        this.candidateEmail = candidateEmail;
    }

    public Long getJobId() {
        return jobId;
    }

    public void setJobId(Long jobId) {
        this.jobId = jobId;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getMatchReason() {
        return matchReason;
    }

    public void setMatchReason(String matchReason) {
        this.matchReason = matchReason;
    }

    public String getWhyGoodMatch() {
        return whyGoodMatch;
    }

    public void setWhyGoodMatch(String whyGoodMatch) {
        this.whyGoodMatch = whyGoodMatch;
    }

    public String getWhyNotPerfectMatch() {
        return whyNotPerfectMatch;
    }

    public void setWhyNotPerfectMatch(String whyNotPerfectMatch) {
        this.whyNotPerfectMatch = whyNotPerfectMatch;
    }

    public String getImprovementSuggestions() {
        return improvementSuggestions;
    }

    public void setImprovementSuggestions(String improvementSuggestions) {
        this.improvementSuggestions = improvementSuggestions;
    }

    public String getRecommendation() {
        return recommendation;
    }

    public void setRecommendation(String recommendation) {
        this.recommendation = recommendation;
    }

    public String getCvFingerprint() {
        return cvFingerprint;
    }

    public void setCvFingerprint(String cvFingerprint) {
        this.cvFingerprint = cvFingerprint;
    }

    public String getJobFingerprint() {
        return jobFingerprint;
    }

    public void setJobFingerprint(String jobFingerprint) {
        this.jobFingerprint = jobFingerprint;
    }

    public Integer getDetailPromptVersion() {
        return detailPromptVersion;
    }

    public void setDetailPromptVersion(Integer detailPromptVersion) {
        this.detailPromptVersion = detailPromptVersion;
    }
}
