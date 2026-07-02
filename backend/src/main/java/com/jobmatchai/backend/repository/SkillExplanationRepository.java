package com.jobmatchai.backend.repository;

import com.jobmatchai.backend.model.SkillExplanation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SkillExplanationRepository extends JpaRepository<SkillExplanation, Long> {

    Optional<SkillExplanation> findBySkillNameLowerAndLanguage(String skillNameLower, String language);
}
