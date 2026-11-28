package com.example.demo.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.example.demo.entity.Combination;
import com.example.demo.entity.Product;

/**
 * 현재고는 키오스크 DB 에 복사하지 않고 필요할 때 ERP 에서 바로 읽는다.
 * 상품 DB 의 stock 컬럼은 더 이상 갱신되지 않으니 재고가 필요한 곳은 여기를 거친다.
 */
@Service
public class ErpStock {

    private static final String STOCK_QUERY = """
            SELECT i.CODE, COALESCE(s.quantity, 0) AS JEGO
            FROM [ITEM] i
            LEFT JOIN current_stock s ON s.ITEMCODE = i.CODE
            WHERE i.CODE >= 100
            """;

    private final JdbcTemplate erpJdbcTemplate;

    public ErpStock(@Qualifier("erpJdbcTemplate") JdbcTemplate erpJdbcTemplate) {
        this.erpJdbcTemplate = erpJdbcTemplate;
    }

    /** ERP 코드(상품·조합 erpCode 와 같은 표기) → 현재고. */
    public Map<String, Integer> byCode() {
        Map<String, Integer> out = new HashMap<>();
        for (Map<String, Object> row : erpJdbcTemplate.queryForList(ErpStockQuery.withCurrentStock(STOCK_QUERY))) {
            out.put(String.valueOf(row.get("CODE")), ErpValues.toInteger(row.get("JEGO")));
        }
        return out;
    }

    /**
     * ERP 현재고를 채운 상품 사본. 원본은 JPA 관리 엔티티라 건드리면 다음 flush 때 DB 에 써질 수 있어 복사한다.
     * 단순 상품은 자기 erpCode, 복합옵션은 숨기지 않은 조합 재고 합. ERP 에 없는 코드는 0.
     */
    public static Product withStock(Product product, Map<String, Integer> stock) {
        Product copy = new Product();
        BeanUtils.copyProperties(product, copy);
        if (product.getCombinations() == null || product.getCombinations().isEmpty()) {
            copy.setStock(stockOf(product.getErpCode(), stock));
            return copy;
        }
        List<Combination> combos = new ArrayList<>();
        int sum = 0;
        for (Combination c : product.getCombinations()) {
            Combination cc = new Combination();
            BeanUtils.copyProperties(c, cc);
            cc.setProduct(copy);
            cc.setStock(stockOf(c.getErpCode(), stock));
            if (!Boolean.TRUE.equals(c.getDeleted())) sum += cc.getStock();
            combos.add(cc);
        }
        copy.setCombinations(combos);
        copy.setStock(Boolean.TRUE.equals(product.getIsComplexOptions()) ? sum : stockOf(product.getErpCode(), stock));
        return copy;
    }

    private static int stockOf(String erpCode, Map<String, Integer> stock) {
        return erpCode == null ? 0 : stock.getOrDefault(erpCode, 0);
    }
}
