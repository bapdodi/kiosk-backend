package com.example.demo.controller;

import java.util.LinkedHashSet;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.service.ErpSyncService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/sync")
@RequiredArgsConstructor
public class SyncController {
    // 경로가 /api/sync/admin/** 인 이유: ERP 상품 DB 를 갱신할 수 있는 API 이므로
    // (erp, apply) SecurityConfig 의 /api/*/admin/**
    // 규칙에 걸려야 로그인한 관리자만 부를 수 있다.

    private final ErpSyncService erpSyncService;

    @PostMapping("/admin/erp")
    public ResponseEntity<?> syncWithErp() {
        try {
            return ResponseEntity.ok(erpSyncService.syncProducts());
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Error during synchronization: " + e.getMessage());
        }
    }

    /** 거래처(GURAE)만 다시 받아온다. 상품 동기화에도 포함되지만, 단가 등급만 급히 고칠 때 쓴다. */
    @PostMapping("/admin/erp/customers")
    public ResponseEntity<?> syncCustomers() {
        try {
            return ResponseEntity.ok(java.util.Map.of("synced", erpSyncService.syncCustomers()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Error during customer synchronization: " + e.getMessage());
        }
    }

    @GetMapping("/admin/erp/preview")
    public ResponseEntity<?> previewErpProducts() {
        try {
            return ResponseEntity.ok(erpSyncService.previewProducts());
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Error loading ERP products: " + e.getMessage());
        }
    }

    @PostMapping("/admin/erp/apply")
    public ResponseEntity<?> applySelectedErpProducts(@RequestBody List<String> syncKeys) {
        try {
            return ResponseEntity.ok(erpSyncService.syncProducts(new LinkedHashSet<>(syncKeys)));
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Error during ERP synchronization: " + e.getMessage());
        }
    }
}
