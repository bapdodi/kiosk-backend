package com.example.demo.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.demo.entity.Combination;
import com.example.demo.entity.Product;

class ErpStockTest {

    private static final Map<String, Integer> STOCK = Map.of("100", 5, "101", 7, "102", 9);

    @Test
    void 단순상품은_자기_ERP코드_재고를_쓰고_원본은_그대로다() {
        Product p = Product.builder().erpCode("100").stock(1).build();
        Product out = ErpStock.withStock(p, STOCK);
        assertThat(out.getStock()).isEqualTo(5);
        assertThat(p.getStock()).isEqualTo(1);
    }

    @Test
    void 복합옵션은_숨기지_않은_조합_재고합이고_없는_코드는_0() {
        Combination a = Combination.builder().erpCode("101").stock(0).deleted(false).build();
        Combination hidden = Combination.builder().erpCode("102").stock(0).deleted(true).build();
        Combination manual = Combination.builder().stock(3).deleted(false).build();
        Combination gone = Combination.builder().erpCode("999").stock(4).deleted(false).build();
        Product p = Product.builder().isComplexOptions(true)
                .combinations(new ArrayList<>(List.of(a, hidden, manual, gone))).build();
        Product out = ErpStock.withStock(p, STOCK);
        assertThat(out.getStock()).isEqualTo(7);
        assertThat(out.getCombinations()).extracting(Combination::getStock).containsExactly(7, 9, 0, 0);
        assertThat(a.getStock()).isZero();
        assertThat(out.getCombinations().get(0).getProduct()).isSameAs(out);
    }
}
