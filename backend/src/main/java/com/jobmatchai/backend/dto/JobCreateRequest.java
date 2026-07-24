package com.jobmatchai.backend.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.PositiveOrZero;

// Deliberately excludes id, companyEmail, applicant counters, and created/updated timestamps -
// those are either backend-controlled or derived from the authenticated caller, never taken
// from the client. Binding the Job JPA entity directly to @RequestBody previously let a client
// supply "id" for an existing job, which Spring Data JPA's save() treats as an update (merge)
// rather than an insert - allowing any company to hijack another company's job posting by id.
//
// minSalary/maxSalary/minExperienceYears/maxExperienceYears are validation-only: the Job entity
// still stores salary/experience as the free-text `salary`/`requirements` strings the frontend
// already composes (e.g. "50000 - 80000", "Experience: 2 - 5 years") - these numeric fields exist
// so the raw numbers behind that text can be rejected server-side (negative, or max < min)
// BEFORE the request ever reaches the controller body / JobRepository.save(), independent of
// whatever the frontend's own validation does. All four are optional (a job posting need not
// specify a range at all) - null just skips that field's checks.
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
