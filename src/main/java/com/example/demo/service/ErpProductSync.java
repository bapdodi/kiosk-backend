package com.example.demo.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.entity.Category;
import com.example.demo.entity.CategoryRef;
import com.example.demo.entity.Combination;
import com.example.demo.entity.Product;
import com.example.demo.repository.ProductRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * ERP 품목(ITEM)을 키오스크 상품으로 반영한다.
 *
 * 같은 품명(공백 정규화)의 ERP 품목들을 한 상품으로 묶고, 품목이 여럿이면 규격(GYU)별 조합으로 만든다.
 * 새 상품은 미분류로 들어가고, 기존 상품은 가격·재고·규격만 갱신한다(이름·분류는 관리자 수정을 존중).
 */
@Service
@Slf4j
public class ErpProductSync {

    private static final String ERP_ITEM_QUERY = "SELECT CODE, ITEM, GYU, OUTA, OUTB, OUTC, PARTCODE, MIDCODE, SMALLCODE, JEGO "
            + "FROM [ITEM] WHERE CODE >= 100 ORDER BY CODE";

    /**
     * ERP 품목이지만 판매 상품이 아닌 비용·회계 항목. 전표에 금액을 싣기 위한 품목이라
     * 키오스크 상품으로 만들지 않는다. 이미 있던 상품은 V2 마이그레이션이 휴지통으로 보냈다.
     */
    static final Set<String> NON_PRODUCT_ITEM_NAMES = Set.of(
            "부가세", "운반비", "용달비", "택배", "택배비", "화물비", "퀵비용",
            "할인액", "현금할인", "차액", "공과잡비", "선수금", "선입금", "증권",
            "배관작업", "전기작업", "절단비용", "배관및펌프철거", "화장실 배관누수공사", "압착기계 임대");

    /**
     * ERP 에서 사라진 코드가 이보다 많으면 휴지통 이동을 멈춘다. ERP 가 덜 복원됐거나 조회가 잘못돼
     * 품목이 한꺼번에 빠져 보일 때 키오스크 상품이 통째로 내려가는 것을 막는다.
     */
    static final int MAX_AUTO_TRASH = 30;

    private final JdbcTemplate erpJdbcTemplate;
    private final ProductRepository productRepository;
    private final ProductService productService;
    private final ErpCustomerSync erpCustomerSync;
    private final ProductCatalogCache productCatalogCache;

    public ErpProductSync(@Qualifier("erpJdbcTemplate") JdbcTemplate erpJdbcTemplate,
            ProductRepository productRepository,
            ProductService productService,
            ErpCustomerSync erpCustomerSync,
            ProductCatalogCache productCatalogCache) {
        this.erpJdbcTemplate = erpJdbcTemplate;
        this.productRepository = productRepository;
        this.productService = productService;
        this.erpCustomerSync = erpCustomerSync;
        this.productCatalogCache = productCatalogCache;
    }

    public record ErpProductPreview(String syncKey, String name, boolean existing) {}

    /**
     * 동기화 결과 요약.
     *
     * @param ambiguous      이름으로만 찾았는데 같은 이름 상품이 여럿이라 건드리지 않은 ERP 품명
     * @param duplicate      다른 ERP 품명과 같은 상품에 걸려 이번엔 건너뛴 ERP 품명
     * @param removedCodes   키오스크엔 있는데 ERP 에서 사라진 품목코드
     * @param removalSkipped 사라진 코드가 너무 많거나 일부만 동기화해서 휴지통 이동을 하지 않았으면 그 이유
     */
    public record SyncResult(int created, int updated, List<String> ambiguous, List<String> duplicate,
            List<String> removedCodes, int trashedProducts, int hiddenOptions, String removalSkipped) {
        public int synced() {
            return created + updated;
        }
    }

    /** ERP 상품을 상품 단위로 미리 보여준다. syncKey 는 선택 반영 시 해당 ERP 상품 묶음을 식별한다. */
    @Transactional(readOnly = true)
    public List<ErpProductPreview> previewProducts() {
        ProductIndex index = new ProductIndex(productRepository.findAll());
        List<ErpProductPreview> previews = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : groupedErpItems().entrySet()) {
            List<Map<String, Object>> rows = entry.getValue();
            Product existing = index.find(rows, entry.getKey()).product();
            previews.add(new ErpProductPreview(
                    syncKey(rows),
                    existing == null ? entry.getKey() : existing.getName(),
                    existing != null));
        }
        return previews;
    }

    @Transactional
    public SyncResult syncProducts() {
        return syncProducts(null);
    }

    /**
     * selectedSyncKeys 가 null 이면 전체, 아니면 해당 ERP 상품 묶음만 반영한다.
     * 전체를 반영할 때만(선택이 미리보기 전체를 덮을 때 포함) ERP 에서 사라진 품목을 휴지통으로 보낸다.
     */
    @Transactional
    public SyncResult syncProducts(Set<String> selectedSyncKeys) {
        long startedAt = System.currentTimeMillis();
        productCatalogCache.invalidate();
        // 거래처 단가(DANGA)도 같이 받아 둔다. 실패해도 상품 동기화는 계속한다.
        try {
            erpCustomerSync.syncCustomers();
        } catch (Exception e) {
            log.warn("ERP customer sync failed, keeping previous copy", e);
        }

        List<Map<String, Object>> erpItems = erpJdbcTemplate.queryForList(ERP_ITEM_QUERY);
        Map<String, List<Map<String, Object>>> groupedItems = group(erpItems);
        ProductIndex index = new ProductIndex(productRepository.findAll());
        List<Product> syncedProducts = new ArrayList<>();
        Set<Product> claimed = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        List<String> ambiguous = new ArrayList<>();
        List<String> duplicate = new ArrayList<>();
        int created = 0;
        boolean coversAll = true;

        for (Map.Entry<String, List<Map<String, Object>>> entry : groupedItems.entrySet()) {
            String name = entry.getKey();
            List<Map<String, Object>> rows = entry.getValue();
            if (selectedSyncKeys != null && !selectedSyncKeys.contains(syncKey(rows))) {
                coversAll = false;
                continue;
            }

            Match match = index.find(rows, name);
            if (match.ambiguous()) {
                // 코드로는 못 찾고 같은 이름 상품만 여럿이다. 아무거나 골라 붙이면 엉뚱한 상품의
                // 가격·규격이 바뀐다. 관리자가 정리할 때까지 건드리지 않는다.
                ambiguous.add(name);
                continue;
            }
            Product product = match.product();
            if (product != null && !claimed.add(product)) {
                // 앞선 ERP 품명이 이미 이 상품을 갱신했다. 두 번째가 덮어쓰면 규격·가격이 뒤섞인다.
                duplicate.add(name);
                continue;
            }
            boolean isNew = product == null;
            if (isNew) {
                product = newProduct(name, rows);
                created++;
            } else {
                applyBasePrices(product, rows.get(0));
                // 기존 상품의 분류는 건드리지 않는다. 예전엔 수동 변경 안 한 상품을 매번 erp-N-0-0 으로
                // 되돌렸는데, 그 카테고리는 더 이상 만들지 않아 상품이 화면에서 사라졌다.
            }

            List<Combination> combinations = toCombinations(product, rows);
            if (rows.size() == 1) {
                mergeIntoSimpleProduct(product, combinations.get(0), rows.get(0));
            } else {
                mergeIntoComplexProduct(product, combinations);
            }

            if (!isNew) {
                // 이 실행의 뒤쪽 품목이 바뀐 ERP 코드·조합으로 이 상품을 찾을 수 있게 한다.
                // (예전 코드는 품목마다 DB 를 조회했고, 그때마다 여기까지의 변경이 먼저 반영됐다.)
                index.reindex(product);
            }
            syncedProducts.add(product);
        }

        productRepository.saveAll(syncedProducts);

        List<String> removedCodes = removedCodes(erpItems);
        String removalSkipped = null;
        ProductService.ErpRemovalResult removal = new ProductService.ErpRemovalResult(0, 0);
        if (!coversAll) {
            removalSkipped = "일부 상품만 동기화해서 ERP 에서 사라진 품목은 정리하지 않았습니다.";
        } else if (removedCodes.size() > MAX_AUTO_TRASH) {
            removalSkipped = "ERP 에서 사라진 품목코드가 " + removedCodes.size() + "개로 너무 많아 정리하지 않았습니다."
                    + " ERP 가 제대로 복원됐는지 확인하세요.";
        } else if (!removedCodes.isEmpty()) {
            removal = productService.trashByErpCodes(removedCodes);
        }

        SyncResult result = new SyncResult(created, syncedProducts.size() - created, ambiguous, duplicate,
                removedCodes, removal.trashedProducts(), removal.hiddenOptions(), removalSkipped);
        log.info("ERP product sync done: {} new, {} updated, {} ambiguous, {} duplicate, {} codes gone from ERP"
                + " ({} products trashed, {} options hidden{}) from {} ERP item groups{} in {} ms",
                result.created(), result.updated(), ambiguous.size(), duplicate.size(), removedCodes.size(),
                result.trashedProducts(), result.hiddenOptions(), removalSkipped == null ? "" : ", skipped",
                groupedItems.size(), selectedSyncKeys == null ? "" : " (" + selectedSyncKeys.size() + " selected)",
                System.currentTimeMillis() - startedAt);
        if (!ambiguous.isEmpty() || !duplicate.isEmpty()) {
            log.warn("ERP sync left untouched - ambiguous name: {}, duplicate: {}", ambiguous, duplicate);
        }
        if (removalSkipped != null && !removedCodes.isEmpty()) {
            log.warn("ERP sync removal skipped: {} codes {}", removalSkipped, removedCodes);
        }
        return result;
    }

    /**
     * 키오스크 상품(휴지통 제외)과 살아 있는 옵션이 쓰는 ERP 코드 중 ERP ITEM 에 없는 것.
     * 비용 품목처럼 동기화에서 빼는 품명의 코드도 ERP 에는 있으므로 여기서는 사라진 것으로 보지 않는다.
     */
    private List<String> removedCodes(List<Map<String, Object>> erpItems) {
        Set<String> erpCodes = new HashSet<>();
        for (Map<String, Object> row : erpItems) {
            erpCodes.add(String.valueOf(row.get("CODE")));
        }
        Set<String> removed = new java.util.TreeSet<>();
        if (erpCodes.isEmpty()) {
            return List.of(); // ERP 조회가 비었으면 판단하지 않는다.
        }
        for (Product product : productRepository.findAll()) {
            if (product.getDeletedAt() != null) continue;
            if (product.getErpCode() != null && !erpCodes.contains(product.getErpCode())) {
                removed.add(product.getErpCode());
            }
            if (product.getCombinations() != null) {
                for (Combination c : product.getCombinations()) {
                    if (c.getErpCode() != null && !Boolean.TRUE.equals(c.getDeleted())
                            && !erpCodes.contains(c.getErpCode())) {
                        removed.add(c.getErpCode());
                    }
                }
            }
        }
        return new ArrayList<>(removed);
    }

    private Product newProduct(String name, List<Map<String, Object>> rows) {
        Map<String, Object> firstRow = rows.get(0);
        Set<CategoryRef> categories = new LinkedHashSet<>();
        categories.add(new CategoryRef(Category.UNCATEGORIZED_ID, null));
        return Product.builder()
                .name(name)
                .priceC(ErpValues.toInteger(firstRow.get("OUTC")))
                .priceA(ErpValues.toInteger(firstRow.get("OUTA")))
                .priceB(ErpValues.toInteger(firstRow.get("OUTB")))
                .categories(categories)
                .hashtags(new ArrayList<>())
                .images(new ArrayList<>())
                .optionGroups(new ArrayList<>())
                .combinations(new ArrayList<>())
                .isCategoryModified(false)
                .isComplexOptions(rows.size() > 1) // Multiple rows mean choices
                .build();
    }

    /** 상품 단가는 묶음의 첫 ERP 품목 기준이다. */
    private void applyBasePrices(Product product, Map<String, Object> firstRow) {
        product.setPriceC(ErpValues.toInteger(firstRow.get("OUTC")));
        product.setPriceA(ErpValues.toInteger(firstRow.get("OUTA")));
        product.setPriceB(ErpValues.toInteger(firstRow.get("OUTB")));
    }

    private List<Combination> toCombinations(Product product, List<Map<String, Object>> rows) {
        List<Combination> combinations = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String erpCode = String.valueOf(row.get("CODE"));
            String gyu = (String) row.get("GYU");
            String comboName = (gyu != null && !gyu.trim().isEmpty()) ? gyu.trim() : ("옵션 " + erpCode);
            combinations.add(Combination.builder()
                    .product(product)
                    .id(erpCode)
                    .name(comboName)
                    .priceC(ErpValues.toInteger(row.get("OUTC")))
                    .priceA(ErpValues.toInteger(row.get("OUTA")))
                    .priceB(ErpValues.toInteger(row.get("OUTB")))
                    .erpCode(erpCode)
                    .stock(ErpValues.toInteger(row.get("JEGO")))
                    .build());
        }
        return combinations;
    }

    /** ERP 품목이 하나면 단순 상품: ERP 코드·재고·규격을 상품이 직접 가진다. */
    private void mergeIntoSimpleProduct(Product product, Combination single, Map<String, Object> row) {
        product.setErpCode(single.getErpCode());
        product.setStock(single.getStock());
        product.setIsComplexOptions(false);
        // 단일 규격(GYU)을 product 에 보존 → 단순상품도 규격이 표시되도록 한다.
        String singleGyu = (String) row.get("GYU");
        product.setGyu((singleGyu != null && !singleGyu.trim().isEmpty()) ? singleGyu.trim() : null);
        // 규격이 하나로 줄어도 기존 조합을 물리 삭제하지 않는다.
        // combinations 는 orphanRemoval = true 라서 clear() 가 곧 DB row 삭제였고,
        // 그렇게 사라진 규격은 휴지통에도 남지 않아 되살릴 방법이 없었다.
        if (product.getCombinations() == null) {
            product.setCombinations(new ArrayList<>());
            return;
        }
        List<Combination> kept = new ArrayList<>();
        for (Combination old : product.getCombinations()) {
            // 살아남은 코드는 이제 상품 자신의 erpCode 라 조합으로 중복 보관하지 않는다.
            if (single.getErpCode() != null && single.getErpCode().equals(old.getErpCode())) {
                continue;
            }
            old.setDeleted(true);
            kept.add(old);
        }
        product.getCombinations().clear();
        product.getCombinations().addAll(kept);
    }

    /** ERP 품목이 여럿이면 복합옵션 상품: ERP 코드·재고·규격은 조합마다 있다. */
    private void mergeIntoComplexProduct(Product product, List<Combination> combinations) {
        product.setErpCode(null);
        product.setStock(combinations.stream().mapToInt(Combination::getStock).sum());
        product.setIsComplexOptions(true);
        // 복합옵션 상품의 규격은 각 combination.name 에 담기므로 단일 규격 필드는 비운다.
        product.setGyu(null);
        if (product.getCombinations() == null) {
            product.setCombinations(new ArrayList<>());
        }

        Map<String, Combination> existingMap = new HashMap<>();
        // ERP 코드가 없는 조합(대시보드에서 수동으로 만든 옵션)은 ERP 쪽에 짝이 없으므로
        // 아래 재구성에서 누락되지 않도록 따로 모아 둔다.
        List<Combination> manualCombinations = new ArrayList<>();
        for (Combination c : product.getCombinations()) {
            if (c.getErpCode() != null) {
                existingMap.put(c.getErpCode(), c);
            } else {
                manualCombinations.add(c);
            }
        }

        List<Combination> next = new ArrayList<>();
        Set<String> erpCodesNow = new HashSet<>();
        for (Combination newC : combinations) {
            erpCodesNow.add(newC.getErpCode());
            Combination existing = existingMap.get(newC.getErpCode());
            if (existing != null) {
                existing.setName(newC.getName());
                existing.setPriceC(newC.getPriceC());
                existing.setPriceA(newC.getPriceA());
                existing.setPriceB(newC.getPriceB());
                existing.setStock(newC.getStock());
                existing.setId(newC.getId());
                // deleted 는 일부러 건드리지 않는다.
                // 관리자가 숨긴 규격을 동기화가 멋대로 되살리지 않기 위함이다.
                next.add(existing);
            } else {
                next.add(newC);
            }
        }

        // ERP 에서 빠진 기존 조합은 지우지 않고 숨김 처리한다.
        // 예전에는 여기서 re-add 되지 않아 orphanRemoval 로 조용히 물리 삭제됐다.
        for (Combination old : existingMap.values()) {
            if (!erpCodesNow.contains(old.getErpCode())) {
                old.setDeleted(true);
                next.add(old);
            }
        }
        next.addAll(manualCombinations);

        product.getCombinations().clear();
        product.getCombinations().addAll(next);
    }

    private Map<String, List<Map<String, Object>>> groupedErpItems() {
        return group(erpJdbcTemplate.queryForList(ERP_ITEM_QUERY));
    }

    private static Map<String, List<Map<String, Object>>> group(List<Map<String, Object>> erpItems) {
        Map<String, List<Map<String, Object>>> groupedItems = new LinkedHashMap<>();
        for (Map<String, Object> itemRow : erpItems) {
            String name = normalizeName((String) itemRow.get("ITEM"));
            if (name == null || name.isEmpty() || NON_PRODUCT_ITEM_NAMES.contains(name)) continue;
            groupedItems.computeIfAbsent(name, k -> new ArrayList<>()).add(itemRow);
        }
        return groupedItems;
    }

    private static String syncKey(List<Map<String, Object>> rows) {
        return String.valueOf(rows.get(0).get("CODE"));
    }

    /**
     * 상품명 정규화: 앞뒤 공백 제거 + 내부 연속 공백을 하나로 축약.
     * ERP 의 "피비볼밸브  (M) 레바"(이중공백) 같은 표기 차이로 같은 상품이 중복 그룹되는 것을 막는다.
     */
    private static String normalizeName(String raw) {
        if (raw == null)
            return null;
        return raw.trim().replaceAll("\\s+", " ");
    }

    /**
     * 기존 상품을 한 번에 불러와 ERP 코드·이름으로 찾는 색인. 예전엔 ERP 품목마다 DB 를 최대 3번
     * 조회해(약 5천 품목 × 3) 동기화 한 번에 30초 가까이 걸렸다.
     *
     * 찾는 순서: ① 묶음 안 ERP 코드마다 상품 erpCode → 조합 erpCode(숨긴 조합 포함) →
     * ② 정규화된 이름. 휴지통 상품도 대상이다. 같은 코드에 상품이 여럿이면 id 가 가장 작은 상품을
     * 고르고, 이름으로만 찾는데 같은 이름이 여럿이면 고르지 않는다(ambiguous).
     * 매칭된 상품의 이름은 수동 변경을 존중해 덮어쓰지 않는다.
     */
    /** 찾은 상품. ambiguous 면 이름만 같은 상품이 여럿이라 고르지 않았다. */
    private record Match(Product product, boolean ambiguous) {}

    private static final class ProductIndex {
        private final Map<String, List<Product>> byErpCode = new HashMap<>();
        private final Map<String, List<Product>> byComboErpCode = new HashMap<>();
        private final Map<String, List<Product>> byName = new HashMap<>();

        ProductIndex(List<Product> products) {
            products.forEach(this::add);
        }

        Match find(List<Map<String, Object>> rows, String normalizedName) {
            for (Map<String, Object> row : rows) {
                String code = String.valueOf(row.get("CODE"));
                if (code.isEmpty())
                    continue;
                Product byErp = first(byErpCode, code);
                if (byErp != null)
                    return new Match(byErp, false);
                Product byCombo = first(byComboErpCode, code);
                if (byCombo != null)
                    return new Match(byCombo, false);
            }
            List<Product> sameName = byName.getOrDefault(normalizedName, List.of());
            if (sameName.size() > 1) {
                return new Match(null, true);
            }
            return new Match(sameName.isEmpty() ? null : sameName.get(0), false);
        }

        /** 이 상품의 ERP 코드·조합이 바뀐 뒤 색인을 다시 맞춘다. */
        void reindex(Product product) {
            for (Map<String, List<Product>> map : List.of(byErpCode, byComboErpCode, byName)) {
                map.values().forEach(list -> list.removeIf(p -> p == product));
            }
            add(product);
        }

        private void add(Product product) {
            put(byErpCode, product.getErpCode(), product);
            if (product.getCombinations() != null) {
                for (Combination c : product.getCombinations()) {
                    put(byComboErpCode, c.getErpCode(), product);
                }
            }
            put(byName, product.getName(), product);
        }

        private static void put(Map<String, List<Product>> map, String key, Product product) {
            if (key == null)
                return;
            List<Product> list = map.computeIfAbsent(key, k -> new ArrayList<>());
            if (!list.contains(product))
                list.add(product);
        }

        private static Product first(Map<String, List<Product>> map, String key) {
            List<Product> list = map.get(key);
            if (list == null || list.isEmpty())
                return null;
            return list.stream().min(Comparator.comparing(Product::getId)).orElse(null);
        }
    }
}
