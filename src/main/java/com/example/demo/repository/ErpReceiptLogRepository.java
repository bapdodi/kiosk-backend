package com.example.demo.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.demo.entity.ErpReceiptLog;

public interface ErpReceiptLogRepository extends JpaRepository<ErpReceiptLog, Long> {
    Optional<ErpReceiptLog> findByRequestId(String requestId);

    List<ErpReceiptLog> findTop100ByOrderByCreatedAtDesc();

    List<ErpReceiptLog> findByErpDateBetweenOrderByCreatedAtDesc(String from, String to);
}
