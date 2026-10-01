package com.example.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.example.demo.service.ErpPriceTier;

class ErpPriceTierTest {

    @Test
    void 모든_손님은_A단가() {
        assertEquals(2500, ErpPriceTier.price(2500, 4000));
    }

    @Test
    void A단가가_비었거나_0이면_소비자가() {
        assertEquals(4000, ErpPriceTier.price(null, 4000));
        assertEquals(4000, ErpPriceTier.price(0, 4000));
    }

    @Test
    void 둘_다_비면_null() {
        assertNull(ErpPriceTier.price(null, null));
    }
}
