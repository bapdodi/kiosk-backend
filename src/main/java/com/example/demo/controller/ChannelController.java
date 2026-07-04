package com.example.demo.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.entity.ChannelCategoryMapping;
import com.example.demo.entity.ChannelProductLink;
import com.example.demo.repository.ChannelCategoryMappingRepository;
import com.example.demo.service.channel.ChannelSyncService;
import com.example.demo.service.naver.NaverOrderService;
import com.example.demo.service.naver.RecentSale;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 판매채널(네이버/쿠팡 등) 연동 관리자 API. 채널은 경로변수 {channel} 로 받는다(naver, coupang…).
 *
 * 경로가 "/api/channels/{channel}/admin/..." 이므로 SecurityConfig 의 채널 관리자 규칙으로 보호된다.
 */
@RestController
@RequestMapping("/api/channels/{channel}/admin")
@RequiredArgsConstructor
@Slf4j
public class ChannelController {

    private final ChannelSyncService syncService;
    private final ChannelCategoryMappingRepository categoryMappingRepository;
    private final NaverOrderService naverOrderService;

    // ── 상태 ──────────────────────────────────────────────────────────────────

    @GetMapping("/config-status")
    public Map<String, Object> configStatus(@PathVariable("channel") String channel) {
        try {
            return Map.of("configured", syncService.isConfigured(channel));
        } catch (RuntimeException e) {
            return Map.of("configured", false, "error", String.valueOf(e.getMessage()));
        }
    }

    // ── 카테고리 매핑 CRUD ──────────────────────────────────────────────────────

    @GetMapping("/category-mappings")
    public List<ChannelCategoryMapping> listCategoryMappings(@PathVariable("channel") String channel) {
        return categoryMappingRepository.findByChannel(channel.toUpperCase());
    }

    @PostMapping("/category-mappings")
    public ChannelCategoryMapping saveCategoryMapping(@PathVariable("channel") String channel,
            @RequestBody ChannelCategoryMapping mapping) {
        mapping.setChannel(channel.toUpperCase());
        return categoryMappingRepository.save(mapping);
    }

    @DeleteMapping("/category-mappings/{id}")
    public ResponseEntity<Void> deleteCategoryMapping(@PathVariable("id") Long id) {
        categoryMappingRepository.deleteById(id);
        return ResponseEntity.ok().build();
    }

    // ── 상품 연동 상태(배지용) ──────────────────────────────────────────────────

    @GetMapping("/products/status")
    public List<ChannelProductLink> productStatus(@PathVariable("channel") String channel,
            @RequestParam("ids") List<Long> ids) {
        return syncService.getLinks(channel, ids);
    }

    // ── 전송(등록/수정) ────────────────────────────────────────────────────────

    @PostMapping("/products/{id}/push")
    public ResponseEntity<?> push(@PathVariable("channel") String channel, @PathVariable("id") Long id) {
        try {
            return ResponseEntity.ok(syncService.push(channel, id));
        } catch (RuntimeException e) {
            log.warn("[{}] 전송 실패 productId={}: {}", channel, id, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    @PostMapping("/products/push-bulk")
    public List<ChannelSyncService.PushResult> pushBulk(@PathVariable("channel") String channel,
            @RequestBody List<Long> ids) {
        return syncService.pushBulk(channel, ids);
    }

    @PostMapping("/products/{id}/suspend")
    public ResponseEntity<?> suspend(@PathVariable("channel") String channel, @PathVariable("id") Long id) {
        syncService.suspend(channel, id);
        return ResponseEntity.ok(Map.of("result", "판매중지 요청 완료"));
    }

    /**
     * 판매상태 변경(판매중 ↔ 판매중지). 요청 본문: {"status": "SALE" | "SUSPENSION"}.
     * 성공 시 갱신된 링크(배지 갱신용)를, 실패 시 error 메시지를 반환한다.
     */
    @PostMapping("/products/{id}/status")
    public ResponseEntity<?> changeStatus(@PathVariable("channel") String channel, @PathVariable("id") Long id,
            @RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(syncService.changeStatus(channel, id, body.get("status")));
        } catch (RuntimeException e) {
            log.warn("[{}] 상태 변경 실패 productId={}: {}", channel, id, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    // ── 판매 확인(최근 주문) ────────────────────────────────────────────────────

    /**
     * 최근 판매(상품주문) 목록. 네이버 조회 범위 제한에 맞춰 hours 는 1~24.
     * 현재는 네이버만 지원한다(다른 채널 요청 시 501).
     */
    @GetMapping("/sales/recent")
    public ResponseEntity<?> recentSales(@PathVariable("channel") String channel,
            @RequestParam(name = "hours", defaultValue = "24") int hours) {
        if (!"NAVER".equalsIgnoreCase(channel)) {
            return ResponseEntity.status(501).body(Map.of("error", "지원하지 않는 채널입니다: " + channel));
        }
        try {
            List<RecentSale> sales = naverOrderService.recentSales(hours);
            return ResponseEntity.ok(Map.of("hours", Math.max(1, Math.min(hours, 24)), "count", sales.size(), "sales", sales));
        } catch (RuntimeException e) {
            log.warn("[{}] 최근 판매 조회 실패: {}", channel, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    // ── 동기화(미리보기 → 수락 반영) ────────────────────────────────────────────

    @GetMapping("/sync/preview")
    public List<ChannelSyncService.ChangePreview> syncPreview(@PathVariable("channel") String channel) {
        return syncService.previewChanges(channel);
    }

    @PostMapping("/sync/apply")
    public List<ChannelSyncService.PushResult> syncApply(@PathVariable("channel") String channel,
            @RequestBody List<Long> ids) {
        return syncService.applyChanges(channel, ids);
    }
}
