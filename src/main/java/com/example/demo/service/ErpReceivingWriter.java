package com.example.demo.service;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

/**
 * ERP 쪽 쓰기만 담당하는 별도 빈.
 *
 * ErpReceivingService 안에 두면 같은 클래스 안에서 호출(self-invocation)하게 되어
 * @Transactional 프록시가 걸리지 않는다. 트랜잭션이 실제로 적용되려면 다른 빈이어야 한다.
 */
@Service
@Slf4j
public class ErpReceivingWriter {

    /** ERP 거래원장의 매입(입고) 구분. 3=매출, 4=매입, 13=발주. */
    static final int KIND_PURCHASE = 4;

    private final JdbcTemplate erpJdbcTemplate;

    /** NONE | JEGO. 검증 전까지는 NONE - JEGO 는 경영박사가 유지하는 파생값이라 함부로 더하면 이중가산된다. */
    @Value("${erp.receiving.stock-mode:NONE}")
    private String stockMode;

    public ErpReceivingWriter(@Qualifier("erpJdbcTemplate") JdbcTemplate erpJdbcTemplate) {
        this.erpJdbcTemplate = erpJdbcTemplate;
    }

    public String stockMode() {
        return stockMode;
    }

    /**
     * 전표번호 채번 → 멱등키 등록 → 라인 INSERT → 자기검증 → (설정 시) 재고 반영을 한 트랜잭션으로 처리.
     * 채번은 반드시 이 트랜잭션 안에서 해야 UPDLOCK 이 커밋까지 유지된다(밖에서 뽑으면 락이 바로 풀린다).
     * 실제로 쓴 전표번호를 돌려준다.
     */
    @Transactional("erpTransactionManager")
    public int insertVoucher(String requestId, String tag, String ilTable, String dDate,
            String vendorCode, String memo, List<Map<String, Object>> lines, String actor) {

        int dNo = nextDno(ilTable, dDate);

        // 멱등: PK 충돌을 판정에 쓴다. SELECT 후 INSERT 는 두 요청이 겹칠 때 둘 다 통과할 수 있다.
        try {
            erpJdbcTemplate.update(
                    "INSERT INTO KIOSK_RECEIPT_VOUCHER (REQUEST_ID, IL_TABLE, dDATE, dNO, LINES, CREATED_AT, CREATED_BY)"
                            + " VALUES (?, ?, ?, ?, ?, SYSDATETIME(), ?)",
                    requestId, ilTable, dDate, dNo, lines.size(), actor);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new IllegalStateException("이미 저장된 요청입니다. 화면을 새로고침하세요.");
        }

        String insert = "INSERT INTO " + ilTable
                + " (dNO, EDITNO, dDATE, ITEMCODE, CUST, KIND, PRICE, EA, GUM, VAT, SA, DAECHE, EA2,"
                + " BIGO, BIGO2, BIGO3, ORDERCODE, POINT, JIJOM)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        for (Map<String, Object> line : lines) {
            erpJdbcTemplate.update(insert,
                    dNo,
                    line.get("editNo"),
                    dDate,
                    line.get("itemCode"),
                    vendorCode,
                    KIND_PURCHASE,
                    line.get("price"),
                    line.get("ea"),
                    line.get("gum"),
                    line.get("vat"),
                    0, // SA
                    0, // DAECHE
                    0, // EA2
                    line.getOrDefault("remark", memo), // BIGO - 줄 적요 (null 불가)
                    tag, // BIGO2 - 우리 전표 추적용
                    "", // BIGO3
                    "", // ORDERCODE
                    0, // POINT
                    0); // JIJOM
        }

        // 전표번호 자기검증: 경영박사가 같은 순간 같은 dNO 를 쓰면 한 전표에 남의 줄이 섞인다.
        Integer foreign = erpJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + ilTable + " WHERE dDATE = ? AND dNO = ? AND ISNULL(BIGO2,'') <> ?",
                Integer.class, dDate, dNo, tag);
        if (foreign != null && foreign > 0) {
            throw new IllegalStateException("전표번호가 다른 전표와 겹쳤습니다. 다시 저장해 주세요.");
        }

        applyStockSideEffects(lines, 1);
        return dNo;
    }

    /**
     * 당일 전표번호 채번. UPDLOCK/HOLDLOCK 으로 우리끼리의 동시 저장은 직렬화된다.
     * 경영박사와의 경합은 제어할 수 없어 INSERT 후 자기검증으로 잡는다.
     */
    private int nextDno(String ilTable, String dDate) {
        Integer max = erpJdbcTemplate.queryForObject(
                "SELECT ISNULL(MAX(dNO),0) FROM " + ilTable + " WITH (UPDLOCK, HOLDLOCK) WHERE dDATE = ?",
                Integer.class, dDate);
        return (max == null ? 0 : max) + 1;
    }

    /** 취소: BIGO2 태그가 붙은 우리 전표만 지운다. 남의 줄은 절대 건드리지 않는다. */
    @Transactional("erpTransactionManager")
    public void deleteVoucher(String requestId, String tag, String ilTable, String dDate, int dNo,
            int expectedLines, List<Map<String, Object>> lines) {
        int deleted = erpJdbcTemplate.update(
                "DELETE FROM " + ilTable + " WHERE dDATE = ? AND dNO = ? AND BIGO2 = ?", dDate, dNo, tag);
        if (deleted != expectedLines) {
            // 경영박사에서 이미 수정/삭제했다는 뜻이다. 자동으로 맞추려 들지 않고 사람이 보게 한다.
            throw new IllegalStateException(
                    "삭제된 줄 수가 기록과 다릅니다(" + deleted + "/" + expectedLines + "). 경영박사에서 확인하세요.");
        }
        erpJdbcTemplate.update(
                "UPDATE KIOSK_RECEIPT_VOUCHER SET CANCELLED_AT = SYSDATETIME() WHERE REQUEST_ID = ?", requestId);
        applyStockSideEffects(lines, -1);
    }

    /**
     * JEGO 반영 훅. 기본(NONE)은 아무것도 하지 않고 경영박사가 자기 규칙대로 반영하게 둔다.
     * 복제본에서 경영박사 입고 1건의 before/after 를 대조해 확정한 뒤에만 켠다.
     * sign 은 저장이면 +1, 취소면 -1.
     */
    private void applyStockSideEffects(List<Map<String, Object>> lines, int sign) {
        if (!"JEGO".equalsIgnoreCase(stockMode)) {
            if (!"NONE".equalsIgnoreCase(stockMode)) {
                throw new IllegalStateException("알 수 없는 erp.receiving.stock-mode: " + stockMode);
            }
            return;
        }
        for (Map<String, Object> line : lines) {
            erpJdbcTemplate.update("UPDATE ITEM SET JEGO = ISNULL(JEGO,0) + ? WHERE CODE = ?",
                    sign * ((Number) line.get("ea")).intValue(), line.get("itemCode"));
        }
    }
}
