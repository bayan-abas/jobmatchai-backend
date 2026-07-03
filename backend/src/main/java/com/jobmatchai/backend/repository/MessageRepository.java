package com.jobmatchai.backend.repository;

import com.jobmatchai.backend.model.Message;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageRepository extends JpaRepository<Message, Long> {
}
