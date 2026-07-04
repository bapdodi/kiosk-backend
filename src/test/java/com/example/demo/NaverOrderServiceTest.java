package com.example.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.demo.service.naver.NaverOrderService;
import com.example.demo.service.naver.RecentSale;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 네이버 주문 API 응답 파싱(순수 함수) 검증. 실호출 없이 합성 JSON 으로 확인. */
class NaverOrderServiceTest {

    private final ObjectMapper om = new ObjectMapper();

    private JsonNode json(String s) throws Exception {
        return om.readTree(s);
    }

    @Test
    void last_changed_응답에서_productOrderId_추출() throws Exception {
        JsonNode resp = json("""
            {"timestamp":"2026-07-04T00:00:00+09:00",
             "data":{"lastChangeStatuses":[
                {"productOrderId":"20260101ABC","productOrderStatus":"PAYED"},
                {"productOrderId":"20260101DEF","productOrderStatus":"PAYED"},
                {"productOrderStatus":"PAYED"}
             ]}}
            """);
        List<String> ids = NaverOrderService.extractProductOrderIds(resp);
        assertEquals(List.of("20260101ABC", "20260101DEF"), ids);
    }

    @Test
    void last_changed_비어있으면_빈목록() throws Exception {
        assertEquals(List.of(), NaverOrderService.extractProductOrderIds(json("{\"timestamp\":\"x\"}")));
        assertEquals(List.of(), NaverOrderService.extractProductOrderIds(null));
    }

    @Test
    void product_orders_query_응답을_RecentSale로_파싱() throws Exception {
        JsonNode resp = json("""
            {"timestamp":"2026-07-04T00:00:00+09:00",
             "data":[
               {"order":{"orderId":"O123","orderDate":"2026-07-04T09:30:00.000+09:00"},
                "productOrder":{"productOrderId":"P1","productName":"피비 아답타 엘보",
                   "quantity":2,"totalPaymentAmount":13000,"productOrderStatus":"PAYED",
                   "channelProductNo":12345,"originProductNo":98765}},
               {"order":{"orderId":"O124","paymentDate":"2026-07-04T10:00:00.000+09:00"},
                "productOrder":{"productOrderId":"P2","productName":"멀티조인트 카플링",
                   "quantity":1,"totalPaymentAmount":8000,"productOrderStatus":"DELIVERING"}}
             ]}
            """);
        List<RecentSale> sales = NaverOrderService.parseSales(resp);
        assertEquals(2, sales.size());

        RecentSale s1 = sales.get(0);
        assertEquals("P1", s1.productOrderId());
        assertEquals("O123", s1.orderId());
        assertEquals("피비 아답타 엘보", s1.naverProductName());
        assertEquals(2, s1.quantity());
        assertEquals(13000, s1.totalPaymentAmount());
        assertEquals("PAYED", s1.orderStatus());
        assertEquals(12345L, s1.channelProductNo());
        assertEquals(98765L, s1.originProductNo());
        assertEquals("2026-07-04T09:30:00.000+09:00", s1.orderDate());
        assertNull(s1.kioskProductName()); // 조인 전이므로 null

        RecentSale s2 = sales.get(1);
        assertEquals("멀티조인트 카플링", s2.naverProductName());
        assertEquals("2026-07-04T10:00:00.000+09:00", s2.orderDate()); // paymentDate 폴백
        assertNull(s2.channelProductNo());
    }

    @Test
    void data가_없으면_빈목록() throws Exception {
        assertEquals(List.of(), NaverOrderService.parseSales(json("{\"timestamp\":\"x\"}")));
        assertEquals(List.of(), NaverOrderService.parseSales(null));
    }
}
