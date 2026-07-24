package com.jobmatchai.backend.model;

// A job's own lifecycle, independent of any Application's status. ACTIVE jobs are the only ones
// candidate/public listing endpoints return and the only ones new applications can be submitted
// against (see JobController#getAllJobs, ApplicationController#applyToJob) - CLOSED jobs stay in
// the database untouched (never cascade-deleted, see JobController#deleteJob for the actual
// delete path, which this is deliberately separate from) so a company keeps full access to the
// job's own existing applicants/history after closing it.
public enum JobStatus {
    ACTIVE,
    CLOSED
}
