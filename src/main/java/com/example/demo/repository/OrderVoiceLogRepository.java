package com.example.demo.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.example.demo.entity.OrderVoiceLog;

public interface OrderVoiceLogRepository extends JpaRepository<OrderVoiceLog, Long> {
    List<OrderVoiceLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
