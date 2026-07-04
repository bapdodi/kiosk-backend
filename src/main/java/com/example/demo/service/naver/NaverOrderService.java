package com.example.demo.service.naver;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.example.demo.config.NaverProperties;
import com.example.demo.entity.ChannelProductLink;
import com.example.demo.entity.Product;
import com.example.demo.repository.ChannelProductLinkRepository;
import com.example.demo.repository.ProductRepository;
import com.fasterxml.jackson.databind.JsonNode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 네이버 판매(상품주문) 조회 서비스 — "상품이 팔렸는지" 확인 기능.
 *
 * 흐름: 변경된 상품주문 내역(last-changed-statuses)으로 최근 주문 ID를 모으고,
 * 상세조회(product-orders/query)로 상품명·수량·금액을 받아, 채널상품번호로 키오스크 상품과 매칭한다.
 * 네이버 조회 범위 제한(24시간)에 맞춰 hours 는 1~24로 클램프한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NaverOrderService {

    private static final String CHANNEL = "NAVER";
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int MAX_DETAIL_IDS = 300;

    private final NaverCommerceClient client;
    private final NaverProperties props;
    private final ChannelProductLinkRepository linkRepository;
    private final ProductRepository productRepository;

    /** 최근 {@code hours} 시간(1~24) 동안 발생한 네이버 판매 목록. 미설정/무주문이면 빈 목록. */
    public List<RecentSale> recentSales(int hours) {
        if (!props.isConfigured()) {
            return List.of();
        }
        int h = Math.max(1, Math.min(hours, 24));
        String fromIso = OffsetDateTime.now(KST).minusHours(h).format(ISO);

        JsonNode changed = client.getLastChangedOrders(fromIso, null);
        List<String> ids = extractProductOrderIds(changed);
        if (ids.isEmpty()) {
            return List.of();
        }
        if (ids.size() > MAX_DETAIL_IDS) {
            log.info("네이버 최근 주문 {}건 중 {}건만 상세조회합니다.", ids.size(), MAX_DETAIL_IDS);
            ids = ids.subList(0, MAX_DETAIL_IDS);
        }
        JsonNode detail = client.queryProductOrders(ids);
        List<RecentSale> sales = parseSales(detail);
        return joinKioskProducts(sales);
    }

    /** last-changed-statuses 응답에서 productOrderId 목록 추출. */
    public static List<String> extractProductOrderIds(JsonNode changed) {
        List<String> ids = new ArrayList<>();
        if (changed == null) {
            return ids;
        }
        JsonNode list = changed.path("data").path("lastChangeStatuses");
        if (list.isArray()) {
            for (JsonNode n : list) {
                String id = n.path("productOrderId").asText(null);
                if (id != null && !id.isBlank()) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    /**
     * product-orders/query 응답 → RecentSale 목록(키오스크 매칭 전).
     * 응답 스키마가 버전에 따라 다를 수 있어 방어적으로 파싱한다.
     */
    public static List<RecentSale> parseSales(JsonNode detailResponse) {
        List<RecentSale> out = new ArrayList<>();
        if (detailResponse == null) {
            return out;
        }
        JsonNode data = detailResponse.path("data");
        if (!data.isArray()) {
            return out;
        }
        for (JsonNode el : data) {
            JsonNode po = el.path("productOrder");
            JsonNode order = el.path("order");
            String productOrderId = po.path("productOrderId").asText(null);
            if (productOrderId == null) {
                productOrderId = order.path("orderId").asText(null);
            }
            Long channelProductNo = asLongOrNull(po.path("channelProductNo"));
            Long originProductNo = asLongOrNull(po.path("originProductNo"));
            String name = firstNonBlank(po.path("productName").asText(null),
                    po.path("originalProductName").asText(null));
            int qty = po.path("quantity").asInt(0);
            long amount = po.path("totalPaymentAmount").asLong(0);
            String status = firstNonBlank(po.path("productOrderStatus").asText(null),
                    po.path("placeOrderStatus").asText(null));
            String orderDate = firstNonBlank(order.path("orderDate").asText(null),
                    order.path("paymentDate").asText(null), po.path("orderDate").asText(null));
            out.add(new RecentSale(productOrderId, order.path("orderId").asText(null),
                    channelProductNo, originProductNo, name, qty, amount, status, orderDate, null, null));
        }
        return out;
    }

    /** 채널상품번호(없으면 원상품번호)로 키오스크 상품명을 붙인다. */
    private List<RecentSale> joinKioskProducts(List<RecentSale> sales) {
        List<ChannelProductLink> links = linkRepository.findByChannel(CHANNEL);
        Map<Long, Long> byChannelNo = new HashMap<>();
        Map<Long, Long> byOriginNo = new HashMap<>();
        for (ChannelProductLink l : links) {
            if (l.getChannelProductNo() != null) {
                byChannelNo.put(l.getChannelProductNo(), l.getProductId());
            }
            if (l.getOriginProductNo() != null) {
                byOriginNo.put(l.getOriginProductNo(), l.getProductId());
            }
        }
        List<RecentSale> result = new ArrayList<>(sales.size());
        for (RecentSale s : sales) {
            Long productId = null;
            if (s.channelProductNo() != null) {
                productId = byChannelNo.get(s.channelProductNo());
            }
            if (productId == null && s.originProductNo() != null) {
                productId = byOriginNo.get(s.originProductNo());
            }
            if (productId != null) {
                String name = productRepository.findById(productId).map(Product::getName).orElse(null);
                result.add(s.withKioskProduct(productId, name));
            } else {
                result.add(s);
            }
        }
        return result;
    }

    private static Long asLongOrNull(JsonNode n) {
        if (n == null) {
            return null;
        }
        if (n.isNumber()) {
            return n.asLong();
        }
        if (n.isTextual() && n.asText().matches("\\d+")) {
            return Long.parseLong(n.asText());
        }
        return null;
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
