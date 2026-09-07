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

    private final ErpSyncService erpSyncService;

    @PostMapping("/erp")
    public ResponseEntity<?> syncWithErp() {
        try {
            return ResponseEntity.ok(erpSyncService.syncProducts());
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Error during synchronization: " + e.getMessage());
        }
    }

    @GetMapping("/erp/preview")
    public ResponseEntity<?> previewErpProducts() {
        try {
            return ResponseEntity.ok(erpSyncService.previewProducts());
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Error loading ERP products: " + e.getMessage());
        }
    }

    @PostMapping("/erp/apply")
    public ResponseEntity<?> applySelectedErpProducts(@RequestBody List<String> syncKeys) {
        try {
            return ResponseEntity.ok(erpSyncService.syncProducts(new LinkedHashSet<>(syncKeys)));
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Error during ERP synchronization: " + e.getMessage());
        }
    }
}
