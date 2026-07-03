package com.jobmatchai.backend.repository;

import com.jobmatchai.backend.model.Interview;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InterviewRepository extends JpaRepository<Interview, Long> {
}
