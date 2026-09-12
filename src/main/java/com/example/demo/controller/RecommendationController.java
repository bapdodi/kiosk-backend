package com.example.demo.controller;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.service.RecommendationAggregator;
import com.example.demo.service.RecommendationService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/recommendations")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationService recommendationService;
    private final RecommendationAggregator recommendationAggregator;

    /**
     * 함께 많이 주문된 상품. codes 에는 기준 상품의 ERP 코드를 콤마로 이어 넘긴다
     * (복합옵션 상품이면 규격별 코드를 모두).
     */
    @GetMapping
    public List<RecommendationService.Recommendation> recommend(
            @RequestParam("codes") String codes,
            @RequestParam(value = "limit", defaultValue = "6") int limit) {
        return recommendationService.recommend(Arrays.asList(codes.split(",")), Math.min(limit, 20));
    }

    /** 야간 배치를 기다리지 않고 즉시 다시 집계한다(관리자 전용). */
    @PostMapping("/admin/rebuild")
    public Map<String, Object> rebuild() {
        int rows = recommendationAggregator.rebuild();
        return Map.of("rows", rows);
    }
}
