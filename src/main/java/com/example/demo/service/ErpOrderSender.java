package com.example.demo.service;

import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.entity.Order;

import lombok.extern.slf4j.Slf4j;

/**
 * 처리 완료된 키오스크 주문을 ERP 매출 전표(SUJU + IL<yy> KIND=3)로 기록한다.
 * {@link ErpOrderOutboxWorker} 가 outbox 에 쌓인 주문을 하나씩 넘긴다.
 *
 * 전표 금액은 주문 접수 때 가격이 아니라 ERP 의 현재 단가와 거래처 등급으로 다시 매기고
 * ({@link ErpPriceTier}), 그 값을 주문 품목의 chargedPrice 와 주문 총액에 되돌려 쓴다.
 * 같은 주문의 재전송은 KIOSK_ORDER_RECEIPT 로 막는다.
 */
@Service
@Slf4j
public class ErpOrderSender {

    private final JdbcTemplate erpJdbcTemplate;

    public ErpOrderSender(@Qualifier("erpJdbcTemplate") JdbcTemplate erpJdbcTemplate) {
        this.erpJdbcTemplate = erpJdbcTemplate;
    }

    @Transactional("erpTransactionManager")
    public void sendOrderToErp(Order order) {
        log.info("Sending order #{} to ERP...", order.getId());

        Integer delivered = erpJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM KIOSK_ORDER_RECEIPT WHERE ORDER_ID = ?", Integer.class, order.getId());
        if (delivered != null && delivered > 0) {
            log.info("Order #{} was already delivered to ERP", order.getId());
            return;
        }
        erpJdbcTemplate.update(
                "INSERT INTO KIOSK_ORDER_RECEIPT (ORDER_ID, CREATED_AT) VALUES (?, SYSDATETIME())",
                order.getId());

        String sujuDateStr = order.getTimestamp()
                .format(java.time.format.DateTimeFormatter.ofPattern("yy.MM.dd"));
        String yy = order.getTimestamp().format(java.time.format.DateTimeFormatter.ofPattern("yy"));
        String ilTable = "IL" + yy;

        // 한 주문의 모든 품목은 동일한 전표번호(dNO)로 묶고, 품목별로 EDITNO(라인번호)만 증가시킨다.
        // 기존에는 품목마다 MAX(dNO)+1 을 루프 안에서 새로 계산해, 각 품목이 별도 전표(=별도 구매자 줄)로
        // 분리되어 "구매자1: 물건1 / 구매자1: 물건2" 처럼 보였다.
        Integer maxDnoForOrder = erpJdbcTemplate.queryForObject(
                "SELECT ISNULL(MAX(dNO),0) FROM " + ilTable + " WHERE dDATE = ?", Integer.class, sujuDateStr);
        int orderDno = (maxDnoForOrder != null ? maxDnoForOrder : 0) + 1;
        int editNo = 0;
        // 거래처 실청구가(A/B/C단가 반영) 합계. 주문 totalAmount 를 실청구가 기준으로 갱신한다.
        long orderChargedTotal = 0;

        for (com.example.demo.entity.OrderItem item : order.getItems()) {
                // If erpCode is missing, we can't sync it properly
                if (item.getErpCode() == null || item.getErpCode().isEmpty()) {
                    throw new IllegalStateException("Order item has no ERP code: " + item.getName());
                }

                String custCode = order.getErpCustomerCode() != null && !order.getErpCustomerCode().isEmpty()
                        ? order.getErpCustomerCode()
                        : "1";

                int actualPrice = item.getFinalPrice() != null ? item.getFinalPrice() : 0;
                try {
                    Map<String, Object> prices = erpJdbcTemplate.queryForMap(
                            "SELECT ISNULL(OUTA,0) as outA, ISNULL(OUTB,0) as outB, ISNULL(OUTC,0) as outC FROM ITEM WHERE CODE = ?",
                            item.getErpCode());
                    int itemOutA = ErpValues.toInteger(prices.get("outA"));
                    int itemOutB = ErpValues.toInteger(prices.get("outB"));
                    int itemOutC = ErpValues.toInteger(prices.get("outC"));

                    int danga = 2; // Default OUTA
                    try {
                        danga = erpJdbcTemplate.queryForObject(
                                "SELECT ISNULL(CAST(DANGA AS INT), 2) FROM GURAE WHERE CODE = ?", Integer.class,
                                custCode);
                    } catch (Exception e) {
                        log.warn("Failed to get DANGA for customer {}, defaulting to 2", custCode);
                    }

                    Integer tierPrice = ErpPriceTier.select(danga, itemOutA, itemOutB, itemOutC);
                    if (tierPrice != null)
                        actualPrice = tierPrice;
                    else
                        // 단가표를 못 고른 경우다. actualPrice 는 주문 접수 때 매긴 가격 그대로 나가므로
                        // 조용히 넘어가면 잘못 청구될 수 있다. DANGA=1(매입 거래처)은 애초에 주문 목록에서
                        // 걸러지므로(CustomerController), 여기 걸리면 ERP 거래처 설정을 봐야 한다.
                        log.warn("No price tier for order #{} item {} (customer {}, DANGA {}, OUTA {} OUTB {} OUTC {})"
                                + " - falling back to consumer price {}",
                                order.getId(), item.getErpCode(), custCode, danga,
                                itemOutA, itemOutB, itemOutC, actualPrice);
                } catch (Exception e) {
                    log.warn("Failed to fetch exact price for item {}, using frontend finalPrice", item.getErpCode());
                }

                int ea = item.getQuantity() != null ? item.getQuantity() : 1;
                // 거래처 DANGA 반영 실청구가를 주문 품목에 저장(주문상세/매출 표시에 사용).
                item.setChargedPrice(actualPrice);
                orderChargedTotal += (long) actualPrice * ea;
                long gum = (long) actualPrice * ea;
                long vat = gum / 10;

                // 1) Insert into SUJU
                String insertSuju = "INSERT INTO SUJU (dDATE, ITEMCODE, PRICE, EA, BALJUNO, CUST) VALUES (?, ?, ?, ?, ?, ?)";
                erpJdbcTemplate.update(insertSuju,
                        sujuDateStr,
                        item.getErpCode(),
                        actualPrice,
                        ea,
                        "KIOSK-" + order.getId(),
                        custCode);

                // 2) Insert into ILxx (Transaction History so it shows in '최근 거래')
                // GUM = PRICE * EA

                String insertIl = "INSERT INTO " + ilTable
                        + " (dNO, EDITNO, dDATE, ITEMCODE, CUST, KIND, PRICE, EA, GUM, VAT, SA, DAECHE, EA2, BIGO, BIGO2, BIGO3, ORDERCODE, POINT, JIJOM) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

                // 같은 주문이면 dNO 는 고정(orderDno)이고 EDITNO 만 라인별로 증가시켜 한 전표로 묶는다.
                editNo++;

                erpJdbcTemplate.update(insertIl,
                        orderDno, // dNO (전표번호: 주문 단위로 동일)
                        editNo, // EDITNO (라인번호: 품목 순서)
                        sujuDateStr, // dDATE (yy.MM.dd)
                        item.getErpCode(),
                        custCode, // CUST
                        "3", // KIND = 3 (외상매출/외출)
                        actualPrice, // PRICE
                        ea, // EA
                        gum, // GUM
                        vat, // VAT
                        0, // SA
                        0, // DAECHE
                        0, // EA2
                        "", // BIGO (Cannot be null)
                        "KIOSK-" + order.getId(), // BIGO2 for tracing
                        "", // BIGO3
                        "", // ORDERCODE
                        0, // POINT
                        0 // JIJOM
                );

                log.info("Synced item {} ({}) to ERP SUJU", item.getName(), item.getErpCode());
        }

        // 주문 총액을 실청구가(거래처 DANGA 반영) 기준으로 갱신. 관리 엔티티라 트랜잭션 커밋 시 반영된다.
        order.setTotalAmount((int) orderChargedTotal);
    }
}
