package com.example.demo.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.service.ErpReceivingService;
import com.example.demo.service.ErpReceivingService.VoucherRequest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 재고 입고(ERP 매입전표) 관리 화면용 API.
 * 경로에 /admin/ 이 들어가야 SecurityConfig 의 ADMIN 규칙(/api/*&#47;admin/**)이 걸린다.
 */
@RestController
@RequestMapping("/api/erp-receiving/admin")
@RequiredArgsConstructor
@Slf4j
public class ErpReceivingController {

    private final ErpReceivingService erpReceivingService;

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return handle(erpReceivingService::status);
    }

    @GetMapping("/items")
    public ResponseEntity<?> items(@RequestParam("q") String q) {
        return handle(() -> erpReceivingService.searchItems(q));
    }

    @GetMapping("/items/{code}/recent")
    public ResponseEntity<?> recent(@PathVariable int code) {
        return handle(() -> erpReceivingService.recentTransactions(code));
    }

    @GetMapping("/vendors")
    public ResponseEntity<?> vendors(@RequestParam(value = "q", required = false) String q) {
        return handle(() -> erpReceivingService.vendors(q));
    }

    @GetMapping("/history")
    public ResponseEntity<?> history() {
        return handle(erpReceivingService::history);
    }

    /** 쓰기 없이 ERP 에 들어갈 내용만 계산해 보여준다. 1단계에서는 여기까지만 쓴다. */
    @PostMapping("/preview")
    public ResponseEntity<?> preview(@RequestBody VoucherRequest request) {
        return handle(() -> erpReceivingService.preview(request));
    }

    @PostMapping("/vouchers")
    public ResponseEntity<?> create(@RequestBody VoucherRequest request, Authentication auth) {
        return handle(() -> erpReceivingService.createVoucher(request, actor(auth)));
    }

    @PostMapping("/vouchers/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable Long id, Authentication auth) {
        return handle(() -> erpReceivingService.cancelVoucher(id, actor(auth)));
    }

    private String actor(Authentication auth) {
        return auth != null && auth.getName() != null ? auth.getName() : "unknown";
    }

    /** 사용자가 고칠 수 있는 입력 오류는 400, 나머지는 500 으로 내리고 메시지는 그대로 화면에 띄운다. */
    private ResponseEntity<?> handle(ThrowingSupplier supplier) {
        try {
            return ResponseEntity.ok(supplier.get());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        } catch (Exception e) {
            log.error("ERP 입고 처리 실패", e);
            return ResponseEntity.status(500).body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        Object get() throws Exception;
    }
}
