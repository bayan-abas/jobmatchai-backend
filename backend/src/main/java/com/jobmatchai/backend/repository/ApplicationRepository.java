package com.jobmatchai.backend.repository;

import com.jobmatchai.backend.model.Application;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApplicationRepository extends JpaRepository<Application, Long> {

    List<Application> findByCandidateEmail(String candidateEmail);

    Optional<Application> findByCandidateEmailAndJobId(String candidateEmail, Long jobId);

    List<Application> findByJobIdIn(List<Long> jobIds);

    long countByCandidateEmail(String candidateEmail);
}