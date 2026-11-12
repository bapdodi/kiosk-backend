package com.example.demo.controller;

import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import com.example.demo.service.OrderVoiceService;
import lombok.RequiredArgsConstructor;

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
}
