package com.jobmatchai.backend.model;

import jakarta.persistence.*;

@Entity
@Table(name = "skill_explanations", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"skill_name_lower", "language"})
})
public class SkillExplanation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "skill_name_lower")
    private String skillNameLower;

    private String language;

    @Column(name = "skill_name_display")
    private String skillNameDisplay;

    @Column(name = "why_important", columnDefinition = "TEXT")
    private String whyImportant;

    @Column(name = "where_used", columnDefinition = "TEXT")
    private String whereUsed;

    @Column(name = "recommended_resources", columnDefinition = "TEXT")
    private String recommendedResources;

    @Column(name = "learning_tips", columnDefinition = "TEXT")
    private String learningTips;

    public SkillExplanation() {}

    public Long getId() {
        return id;
    }

    public String getSkillNameLower() {
        return skillNameLower;
    }

    public void setSkillNameLower(String skillNameLower) {
        this.skillNameLower = skillNameLower;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getSkillNameDisplay() {
        return skillNameDisplay;
    }

    public void setSkillNameDisplay(String skillNameDisplay) {
        this.skillNameDisplay = skillNameDisplay;
    }

    public String getWhyImportant() {
        return whyImportant;
    }

    public void setWhyImportant(String whyImportant) {
        this.whyImportant = whyImportant;
    }

    public String getWhereUsed() {
        return whereUsed;
    }

    public void setWhereUsed(String whereUsed) {
        this.whereUsed = whereUsed;
    }

    public String getRecommendedResources() {
        return recommendedResources;
    }

    public void setRecommendedResources(String recommendedResources) {
        this.recommendedResources = recommendedResources;
    }

    public String getLearningTips() {
        return learningTips;
    }

    public void setLearningTips(String learningTips) {
        this.learningTips = learningTips;
    }
}
