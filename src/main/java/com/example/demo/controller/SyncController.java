package com.example.demo.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
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
    public ResponseEntity<String> syncWithErp() {
        try {
            erpSyncService.syncProducts();
            return ResponseEntity.ok("Synchronization successful");
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Error during synchronization: " + e.getMessage());
        }
    }
}
