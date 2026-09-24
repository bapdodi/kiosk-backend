package com.example.demo.service;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.entity.Order;
import com.example.demo.entity.OrderItem;

import lombok.extern.slf4j.Slf4j;

/**
 * 처리 완료된 키오스크 주문을 ERP 매출 전표(SUJU + IL<yy> KIND=3)로 기록한다.
 * {@link ErpOrderOutboxWorker} 가 outbox 에 쌓인 주문을 하나씩 넘긴다.
 *
 * 전표 금액은 ERP 의 현재 단가로 다시 매기되, 거래처 등급으로 단가를 고르는 규칙은 주문 접수와 같다
 * ({@link ErpPriceTier#price}). 그 값을 주문 품목의 chargedPrice 와 주문 총액에 되돌려 쓴다.
 * 같은 주문의 재전송은 KIOSK_ORDER_RECEIPT 로 막는다.
 */
@Service
@Slf4j
public class ErpOrderSender {

    /** 거래처를 고르지 않은 주문이 ERP 에 들어가는 거래처 코드. */
    private static final String WALK_IN_CUSTOMER = "1";

    private static final DateTimeFormatter ERP_DATE = DateTimeFormatter.ofPattern("yy.MM.dd");
    private static final DateTimeFormatter ERP_YEAR = DateTimeFormatter.ofPattern("yy");

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

        for (OrderItem item : order.getItems()) {
            if (item.getErpCode() == null || item.getErpCode().isEmpty()) {
                throw new IllegalStateException("Order item has no ERP code: " + item.getName());
            }
        }

        String erpDate = order.getTimestamp().format(ERP_DATE);
        String ilTable = "IL" + order.getTimestamp().format(ERP_YEAR);
        String tag = "KIOSK-" + order.getId();
        boolean hasCustomer = order.getErpCustomerCode() != null && !order.getErpCustomerCode().isEmpty();
        String custCode = hasCustomer ? order.getErpCustomerCode() : WALK_IN_CUSTOMER;
        // 거래처를 고르지 않은 주문은 등급이 없다 → 주문 접수 때처럼 소비자가.
        // (예전엔 코드 1 의 등급을 찾다가 없으면 A단가로 넣어, 화면·명세서보다 싸게 청구됐다.)
        Integer danga = hasCustomer ? customerDanga(custCode) : null;

        // 한 주문의 모든 품목은 동일한 전표번호(dNO)로 묶고, 품목별로 EDITNO(라인번호)만 증가시킨다.
        int orderDno = nextVoucherNo(ilTable, erpDate);
        int editNo = 0;
        // 거래처 실청구가(A/B/C단가 반영) 합계. 주문 totalAmount 를 실청구가 기준으로 갱신한다.
        long orderChargedTotal = 0;

        for (OrderItem item : order.getItems()) {
            int actualPrice = chargedPrice(order, item, danga);
            int ea = item.getQuantity() != null ? item.getQuantity() : 1;
            // 거래처 DANGA 반영 실청구가를 주문 품목에 저장(주문상세/매출 표시에 사용).
            item.setChargedPrice(actualPrice);
            orderChargedTotal += (long) actualPrice * ea;
            long gum = (long) actualPrice * ea;
            long vat = gum / 10;

            erpJdbcTemplate.update(
                    "INSERT INTO SUJU (dDATE, ITEMCODE, PRICE, EA, BALJUNO, CUST) VALUES (?, ?, ?, ?, ?, ?)",
                    erpDate, item.getErpCode(), actualPrice, ea, tag, custCode);

            // IL<yy> 에 넣어야 경영박사 '최근 거래'에 보인다. 같은 주문이면 dNO 고정, EDITNO 만 증가.
            editNo++;
            erpJdbcTemplate.update("INSERT INTO " + ilTable
                    + " (dNO, EDITNO, dDATE, ITEMCODE, CUST, KIND, PRICE, EA, GUM, VAT, SA, DAECHE, EA2, BIGO, BIGO2, BIGO3, ORDERCODE, POINT, JIJOM)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    orderDno, // dNO (전표번호: 주문 단위로 동일)
                    editNo, // EDITNO (라인번호: 품목 순서)
                    erpDate, // dDATE (yy.MM.dd)
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
                    tag, // BIGO2 for tracing
                    "", // BIGO3
                    "", // ORDERCODE
                    0, // POINT
                    0 // JIJOM
            );
        }

        // 전표번호 자기검증: 경영박사가 같은 순간 같은 dNO 를 쓰면 한 전표에 남의 줄이 섞인다.
        // 겹쳤으면 롤백하고 outbox 가 다음 주기에 새 번호로 다시 보낸다.
        Integer foreign = erpJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + ilTable + " WHERE dDATE = ? AND dNO = ? AND ISNULL(BIGO2,'') <> ?",
                Integer.class, erpDate, orderDno, tag);
        if (foreign != null && foreign > 0) {
            throw new IllegalStateException("ERP 전표번호 " + orderDno + " 가 다른 전표와 겹쳐 다시 보냅니다.");
        }

        // 주문 총액을 실청구가(거래처 DANGA 반영) 기준으로 갱신. 관리 엔티티라 트랜잭션 커밋 시 반영된다.
        order.setTotalAmount((int) orderChargedTotal);
        log.info("Order #{} sent to ERP as {} dNO {} ({} lines)", order.getId(), ilTable, orderDno, editNo);
    }

    /**
     * 당일 전표번호 채번. 이 트랜잭션 안에서 UPDLOCK/HOLDLOCK 을 잡아야 커밋까지 유지돼
     * 우리 쪽 동시 전송(주문·입고)이 같은 번호를 받지 않는다. 경영박사와의 경합은 자기검증으로 잡는다.
     */
    private int nextVoucherNo(String ilTable, String erpDate) {
        Integer max = erpJdbcTemplate.queryForObject(
                "SELECT ISNULL(MAX(dNO),0) FROM " + ilTable + " WITH (UPDLOCK, HOLDLOCK) WHERE dDATE = ?",
                Integer.class, erpDate);
        return (max != null ? max : 0) + 1;
    }

    /** ERP 거래처 등급. 거래처가 없거나 등급이 비어 있으면 null(= 소비자가). */
    private Integer customerDanga(String custCode) {
        List<Integer> rows;
        try {
            rows = erpJdbcTemplate.queryForList(
                    "SELECT CAST(DANGA AS INT) FROM GURAE WHERE CODE = ?", Integer.class, custCode);
        } catch (Exception e) {
            // 등급을 못 읽었다고 매출 전송을 막지는 않는다.
            log.warn("Failed to read DANGA for ERP customer {}, charging consumer price", custCode, e);
            return null;
        }
        if (rows.isEmpty() || rows.get(0) == null) {
            log.warn("ERP customer {} has no price tier (DANGA), charging consumer price", custCode);
            return null;
        }
        return rows.get(0);
    }

    /**
     * ERP 현재 단가로 매긴 청구가. ERP 에 품목이 없거나 단가가 비어 0 이 되면 주문 접수 때 가격을 그대로 쓴다
     * (0원 전표를 만들지 않기 위해서다).
     */
    private int chargedPrice(Order order, OrderItem item, Integer danga) {
        int orderedPrice = item.getFinalPrice() != null ? item.getFinalPrice() : 0;
        List<Map<String, Object>> rows = erpJdbcTemplate.queryForList(
                "SELECT ISNULL(OUTA,0) as outA, ISNULL(OUTB,0) as outB, ISNULL(OUTC,0) as outC FROM ITEM WHERE CODE = ?",
                item.getErpCode());
        if (rows.isEmpty()) {
            log.warn("Order #{} item {} is not in ERP ITEM, charging ordered price {}",
                    order.getId(), item.getErpCode(), orderedPrice);
            return orderedPrice;
        }
        Map<String, Object> prices = rows.get(0);
        Integer price = ErpPriceTier.price(danga,
                ErpValues.toInteger(prices.get("outA")),
                ErpValues.toInteger(prices.get("outB")),
                ErpValues.toInteger(prices.get("outC")));
        if (price == null || price <= 0) {
            log.warn("Order #{} item {} has no ERP price for DANGA {}, charging ordered price {}",
                    order.getId(), item.getErpCode(), danga, orderedPrice);
            return orderedPrice;
        }
        return price;
    }
}
