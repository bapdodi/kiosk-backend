package com.example.demo.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.entity.OrderVoiceLog;
import com.example.demo.service.OrderVoiceService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/order-voice")
@RequiredArgsConstructor
public class OrderVoiceController {
    private final OrderVoiceService service;

    @GetMapping("/status")
    public Map<String, Boolean> status() {
        return Map.of("available", service.available());
    }

    @PostMapping("/session")
    public ResponseEntity<?> session() {
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.createSession());
        } catch (ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode()).cacheControl(CacheControl.noStore())
                    .body(Map.of("error", e.getReason()));
        }
    }

    @PostMapping("/log")
    public ResponseEntity<?> logSession(@RequestBody OrderVoiceLog logEntry) {
        try {
            OrderVoiceLog saved = service.saveLog(logEntry);
            log.info("[AI 통화 로그] status={}, customer={}, tools={}, error={}",
                    saved.getStatus(), saved.getCustomerName(), saved.getTools(), saved.getErrorMessage());
            return ResponseEntity.ok(Map.of("saved", true, "id", saved.getId() != null ? saved.getId() : 0));
        } catch (Exception e) {
            log.warn("AI 통화 로그 저장 실패: {}", e.getMessage());
            return ResponseEntity.ok(Map.of("saved", false));
        }
    }

    @GetMapping("/logs")
    public ResponseEntity<List<OrderVoiceLog>> getLogs(@RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.getRecentLogs(limit));
    }
}
