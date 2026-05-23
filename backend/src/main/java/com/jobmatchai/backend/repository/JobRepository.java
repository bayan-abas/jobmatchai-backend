package com.jobmatchai.backend.repository;

import com.jobmatchai.backend.model.Job;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobRepository extends JpaRepository<Job, Long> {
}