package com.jobmatchai.backend.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Column(unique = true)
    @NotBlank(message = "Email is required")
    @Email(message = "Please enter a valid email address")
    private String email;

    private String password;

    private String role;

    private String cvFileName;

    // The file name exactly as the candidate uploaded it, kept separate from cvFileName
    // (the sanitized/timestamp-prefixed name it's actually stored under on disk) so the
    // UI can always show the real name back to the user.
    private String originalCvFileName;

    private Boolean premium;

    private LocalDateTime premiumSince;

    private String stripeCustomerId;

    private String stripeSubscriptionId;

    private String phone;

    private String location;

    private String currentTitle;

    private String yearsOfExperience;

    private String skills;

    @Column(columnDefinition = "TEXT")
    private String professionalSummary;

    // Company-side profile fields. Collected on the company registration form but previously
    // only ever written to browser localStorage - never persisted, invisible on any other
    // device, and gone the moment local storage was cleared.
    private String industry;

    private String companySize;

    private String website;

    @Column(columnDefinition = "TEXT")
    private String companyDescription;

    public User() {}

    public User(String name, String email, String password, String role) {
        this.name = name;
        this.email = email;
        this.password = password;
        this.role = role;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }
    
    public String getCvFileName() {
        return cvFileName;
    }

    public void setCvFileName(String cvFileName) {
        this.cvFileName = cvFileName;
    }

    public String getOriginalCvFileName() {
        return originalCvFileName;
    }

    public void setOriginalCvFileName(String originalCvFileName) {
        this.originalCvFileName = originalCvFileName;
    }

    public boolean isPremium() {
        return Boolean.TRUE.equals(premium);
    }

    public void setPremium(boolean premium) {
        this.premium = premium;
    }

    public LocalDateTime getPremiumSince() {
        return premiumSince;
    }

    public void setPremiumSince(LocalDateTime premiumSince) {
        this.premiumSince = premiumSince;
    }

    public String getStripeCustomerId() {
        return stripeCustomerId;
    }

    public void setStripeCustomerId(String stripeCustomerId) {
        this.stripeCustomerId = stripeCustomerId;
    }

    public String getStripeSubscriptionId() {
        return stripeSubscriptionId;
    }

    public void setStripeSubscriptionId(String stripeSubscriptionId) {
        this.stripeSubscriptionId = stripeSubscriptionId;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getCurrentTitle() {
        return currentTitle;
    }

    public void setCurrentTitle(String currentTitle) {
        this.currentTitle = currentTitle;
    }

    public String getYearsOfExperience() {
        return yearsOfExperience;
    }

    public void setYearsOfExperience(String yearsOfExperience) {
        this.yearsOfExperience = yearsOfExperience;
    }

    public String getSkills() {
        return skills;
    }

    public void setSkills(String skills) {
        this.skills = skills;
    }

    public String getProfessionalSummary() {
        return professionalSummary;
    }

    public void setProfessionalSummary(String professionalSummary) {
        this.professionalSummary = professionalSummary;
    }

    public String getIndustry() {
        return industry;
    }

    public void setIndustry(String industry) {
        this.industry = industry;
    }

    public String getCompanySize() {
        return companySize;
    }

    public void setCompanySize(String companySize) {
        this.companySize = companySize;
    }

    public String getWebsite() {
        return website;
    }

    public void setWebsite(String website) {
        this.website = website;
    }

    public String getCompanyDescription() {
        return companyDescription;
    }

    public void setCompanyDescription(String companyDescription) {
        this.companyDescription = companyDescription;
    }
}