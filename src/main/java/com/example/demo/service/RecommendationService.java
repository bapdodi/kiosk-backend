package com.example.demo.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * 집계된 동시구매 페어를 상품 추천으로 바꿔 내보낸다.
 *
 * 페어 테이블은 수만 행이고 하루 한 번만 바뀌므로 통째로 메모리에 들고 있는다. 키오스크는 옵션
 * 모달을 열 때마다 추천을 요청하는데, 매번 조인 질의를 돌릴 이유가 없다.
 */
@Service
@Slf4j
public class RecommendationService {

    private final JdbcTemplate jdbcTemplate;

    /** 상품 정보 스냅샷의 수명. 관리자 수정이 이 시간 안에 반영된다. */
    @Value("${recommendation.cache-ttl-ms:300000}")
    private long cacheTtlMs;

    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();

    public RecommendationService(@Qualifier("jdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 추천 한 건. 사진·가격은 프론트가 이미 들고 있는 상품 목록에서 찾으므로 식별자만 돌려준다. */
    public record Recommendation(Long productId, String erpCode, String name, int coCount, double score) {
    }

    /**
     * 주어진 ERP 코드들과 함께 많이 팔린 상품을 점수순으로 돌려준다.
     *
     * 여러 코드를 받는 이유는 복합옵션 상품 때문이다. 규격마다 ERP 코드가 따로 있어서, 상품 하나를
     * 열었을 때 그 상품에 속한 모든 코드의 추천을 합쳐야 규격 선택 전에도 추천이 보인다.
     */
    public List<Recommendation> recommend(List<String> erpCodes, int limit) {
        if (erpCodes == null || erpCodes.isEmpty() || limit <= 0) return List.of();

        Snapshot current = snapshot();
        Set<String> sources = new HashSet<>();
        for (String code : erpCodes) {
            if (code != null && !code.isBlank()) sources.add(code.trim());
        }
        if (sources.isEmpty()) return List.of();

        // 입력 코드가 속한 상품은 추천에서 뺀다. 지금 보고 있는 상품이 자기 자신을 규격만 바꿔 다시
        // 추천하면 화면만 차지한다.
        Set<Long> excludedProducts = new HashSet<>();
        for (String code : sources) {
            ProductRef ref = current.productByCode.get(code);
            if (ref != null) excludedProducts.add(ref.id());
        }

        // 같은 상품의 여러 규격이 각각 추천에 걸리면 점수를 합쳐 한 줄로 보여준다.
        Map<Long, Recommendation> best = new HashMap<>();
        for (String source : sources) {
            for (Pair pair : current.pairsBySource.getOrDefault(source, List.of())) {
                if (sources.contains(pair.targetCode())) continue;

                ProductRef target = current.productByCode.get(pair.targetCode());
                if (target == null) continue; // 키오스크에 등록되지 않았거나 삭제된 품목
                if (excludedProducts.contains(target.id())) continue;

                Recommendation previous = best.get(target.id());
                if (previous == null) {
                    best.put(target.id(), new Recommendation(target.id(), pair.targetCode(), target.name(),
                            pair.coCount(), pair.score()));
                } else if (pair.score() > previous.score()) {
                    best.put(target.id(), new Recommendation(target.id(), pair.targetCode(), target.name(),
                            Math.max(previous.coCount(), pair.coCount()), pair.score()));
                }
            }
        }

        List<Recommendation> result = new ArrayList<>(best.values());
        result.sort(Comparator.comparingDouble(Recommendation::score).reversed());
        return result.size() > limit ? result.subList(0, limit) : result;
    }

    /** 집계 배치가 페어를 갈아끼운 뒤 호출한다. */
    public void invalidate() {
        snapshot.set(null);
    }

    private Snapshot snapshot() {
        Snapshot current = snapshot.get();
        if (current != null && System.currentTimeMillis() - current.loadedAt() < cacheTtlMs) {
            return current;
        }
        Snapshot loaded = load();
        snapshot.set(loaded);
        return loaded;
    }

    private Snapshot load() {
        Map<String, List<Pair>> pairsBySource = new HashMap<>();
        jdbcTemplate.query(
                "SELECT source_erp_code, target_erp_code, co_count, score FROM reco_item_pair ORDER BY score DESC",
                rs -> {
                    pairsBySource
                            .computeIfAbsent(rs.getString("source_erp_code").trim(), k -> new ArrayList<>())
                            .add(new Pair(rs.getString("target_erp_code").trim(), rs.getInt("co_count"),
                                    rs.getDouble("score")));
                });

        // 단순 상품은 products.erp_code, 복합옵션 상품은 규격마다 combinations.erp_code 를 갖는다.
        // 추천은 ERP 코드로 오가므로 두 경로를 하나의 색인으로 합친다.
        Map<String, ProductRef> productByCode = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT c.erp_code AS code, p.id, p.name FROM combinations c JOIN products p ON p.id = c.product_id "
                        + "WHERE c.erp_code IS NOT NULL AND c.deleted = false AND p.deleted_at IS NULL",
                rs -> {
                    productByCode.put(rs.getString("code").trim(),
                            new ProductRef(rs.getLong("id"), rs.getString("name")));
                });
        jdbcTemplate.query(
                "SELECT erp_code AS code, id, name FROM products WHERE erp_code IS NOT NULL AND deleted_at IS NULL",
                rs -> {
                    productByCode.put(rs.getString("code").trim(),
                            new ProductRef(rs.getLong("id"), rs.getString("name")));
                });

        log.debug("Recommendation snapshot loaded: {} source items, {} known ERP codes",
                pairsBySource.size(), productByCode.size());
        return new Snapshot(pairsBySource, productByCode, System.currentTimeMillis());
    }

    private record Pair(String targetCode, int coCount, double score) {
    }

    private record ProductRef(Long id, String name) {
    }

    private record Snapshot(Map<String, List<Pair>> pairsBySource, Map<String, ProductRef> productByCode,
            long loadedAt) {
    }
}
