package com.jobmatchai.backend.dto;

// Deliberately excludes id, companyEmail, applicant counters, and created/updated timestamps -
// those are either backend-controlled or derived from the authenticated caller, never taken
// from the client. Binding the Job JPA entity directly to @RequestBody previously let a client
// supply "id" for an existing job, which Spring Data JPA's save() treats as an update (merge)
// rather than an insert - allowing any company to hijack another company's job posting by id.
public record JobCreateRequest(
        String title,
        String companyName,
        String location,
        String type,
        String salary,
        String description,
        String requirements,
        String skills
) {}
