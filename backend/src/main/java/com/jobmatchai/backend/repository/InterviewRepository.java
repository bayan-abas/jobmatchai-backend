package com.jobmatchai.backend.repository;

import com.jobmatchai.backend.model.Interview;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InterviewRepository extends JpaRepository<Interview, Long> {

    void deleteByCandidateEmail(String candidateEmail);

    void deleteByCompanyEmail(String companyEmail);

    // שולף בבת אחת את כל הראיונות של כמה בקשות עבודה, כדי שאפשר יהיה לצרף אותם לרשימת הבקשות בלי שאילתה לכל בקשה בנפרד
    List<Interview> findByApplicationIdIn(List<Long> applicationIds);

    // כל הראיונות של בקשה ספציפית, מהחדש לישן - אם היה יותר מראיון אחד (למשל אחרי תיאום מחדש)
    List<Interview> findByApplicationIdOrderByIdDesc(Long applicationId);
}
