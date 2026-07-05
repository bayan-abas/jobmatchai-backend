package com.jobmatchai.backend.repository;

import com.jobmatchai.backend.model.Application;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ApplicationRepository extends JpaRepository<Application, Long> {

    List<Application> findByCandidateEmail(String candidateEmail);

    long countByCandidateEmailAndCreatedAtAfter(String candidateEmail, LocalDateTime after);

    Optional<Application> findByCandidateEmailAndJobId(String candidateEmail, Long jobId);

    List<Application> findByJobIdIn(List<Long> jobIds);

    List<Application> findByJobId(Long jobId);

    List<Application> findByCompanyEmail(String companyEmail);

    long countByCandidateEmail(String candidateEmail);

    long countByCompanyEmail(String companyEmail);

    long countByJobId(Long jobId);

    void deleteByCandidateEmail(String candidateEmail);

    void deleteByCompanyEmail(String companyEmail);
}