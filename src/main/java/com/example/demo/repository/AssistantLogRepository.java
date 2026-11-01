package com.example.demo.repository;

import java.time.LocalDateTime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.entity.AssistantLog;

public interface AssistantLogRepository extends JpaRepository<AssistantLog, Long> {
    @Transactional
    long deleteByCreatedAtBefore(LocalDateTime cutoff);
}
