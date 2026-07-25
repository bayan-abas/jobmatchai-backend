package com.jobmatchai.backend.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.PositiveOrZero;

public record JobCreateRequest(
        String title,
        String companyName,
        String location,
        String type,
        String salary,
        String description,
        String requirements,
        String skills,

        @PositiveOrZero(message = "Minimum salary cannot be negative")
        Integer minSalary,

        @PositiveOrZero(message = "Maximum salary cannot be negative")
        Integer maxSalary,

        @PositiveOrZero(message = "Minimum years of experience cannot be negative")
        Integer minExperienceYears,

        @PositiveOrZero(message = "Maximum years of experience cannot be negative")
        Integer maxExperienceYears
) {
    @AssertTrue(message = "Maximum salary cannot be lower than minimum salary")
    public boolean isSalaryRangeValid() {
        return minSalary == null || maxSalary == null || maxSalary >= minSalary;
    }

    @AssertTrue(message = "Maximum years of experience cannot be lower than minimum years of experience")
    public boolean isExperienceRangeValid() {
        return minExperienceYears == null || maxExperienceYears == null || maxExperienceYears >= minExperienceYears;
    }
}
