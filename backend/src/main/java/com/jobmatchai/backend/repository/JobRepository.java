package com.jobmatchai.backend.repository;

import com.jobmatchai.backend.model.Job;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JobRepository extends JpaRepository<Job, Long> {

    List<Job> findByCompanyEmail(String companyEmail);

}