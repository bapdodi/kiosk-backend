package com.example.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.example.demo.service.ErpPriceTier;

class ErpPriceTierTest {

    @Test
    void 등급_2_3_4_는_A_B_C_단가() {
        assertEquals(2500, ErpPriceTier.select(2, 2500, 2700, 4000));
        assertEquals(2700, ErpPriceTier.select(3, 2500, 2700, 4000));
        assertEquals(4000, ErpPriceTier.select(4, 2500, 2700, 4000));
    }

    @Test
    void 등급이_없거나_모르는_값이면_null() {
        assertNull(ErpPriceTier.select(null, 2500, 2700, 4000));
        assertNull(ErpPriceTier.select(1, 2500, 2700, 4000)); // 매입 거래처
        assertNull(ErpPriceTier.select(6, 2500, 2700, 4000));
    }

    @Test
    void 해당_단가가_비었거나_0이면_null() {
        assertNull(ErpPriceTier.select(2, 0, 2700, 4000));
        assertNull(ErpPriceTier.select(3, 2500, null, 4000));
        assertNull(ErpPriceTier.select(4, 2500, 2700, 0));
    }

    @Test
    void 등급이_없거나_해당_단가가_비면_주문과_전표_모두_소비자가() {
        assertEquals(4000, ErpPriceTier.price(null, 2500, 2700, 4000));
        assertEquals(4000, ErpPriceTier.price(2, 0, 2700, 4000));
        assertEquals(4000, ErpPriceTier.price(6, 2500, 2700, 4000));
    }
}
