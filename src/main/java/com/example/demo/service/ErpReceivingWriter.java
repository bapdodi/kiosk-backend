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
    static final int KIND_ORDER = 13;

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
     * 채번은 반드시 이 트랜잭션 안에서 해야 앱 잠금이 커밋까지 유지된다(밖에서 뽑으면 잠금이 바로 풀린다).
     * 실제로 쓴 전표번호를 돌려준다.
     */
    @Transactional("erpTransactionManager")
    public int insertVoucher(String requestId, String tag, String ilTable, String dDate,
            String vendorCode, String memo, List<Map<String, Object>> lines, String actor) {
        return insertVoucher(KIND_PURCHASE, requestId, tag, ilTable, dDate, vendorCode, memo, lines, actor);
    }

    /**
     * 발주(KIND=13)도 같은 구조라 kind 만 다르다. 발주는 재고를 움직이지 않는다 -
     * 물건이 들어오는 것은 나중에 명세서가 와서 KIND=4 매입으로 입력될 때다.
     */
    @Transactional("erpTransactionManager")
    public int insertVoucher(int kind, String requestId, String tag, String ilTable, String dDate,
            String vendorCode, String memo, List<Map<String, Object>> lines, String actor) {

        int dNo = nextDno(ilTable, dDate);

        // 멱등: 별도 테이블 없이 원장 줄의 추적 태그(BIGO2)가 곧 요청 ID 다.
        // 채번이 앱 잠금으로 직렬화돼 있어, 같은 요청이 겹쳐도 뒤에 온 쪽은 앞 쪽 커밋 뒤에 태그를 보게 된다.
        Integer already = erpJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + ilTable + " WHERE BIGO2 = ?", Integer.class, tag);
        if (already != null && already > 0) {
            throw new IllegalStateException("이미 저장된 요청입니다. 화면을 새로고침하세요.");
        }

        boolean isOrder = (kind == KIND_ORDER);
        String baljuNo = isOrder ? (dDate.replace(".", "") + "-" + String.format("%05d", dNo)) : null;

        String insertIl = "INSERT INTO " + ilTable
                + " (dNO, EDITNO, dDATE, ITEMCODE, CUST, KIND, PRICE, EA, GUM, VAT, SA, DAECHE, EA2,"
                + " BIGO, BIGO2, BIGO3, ORDERCODE, POINT, JIJOM)"
                + (isOrder ? " OUTPUT INSERTED.AUTOKEY" : "")
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        String insertBalju = "INSERT INTO BALJU (dDATE, ITEMCODE, CUST, DATE2, PRICE, EA, EA2, JANEA, FOJANG, BALJUNO, PRINTED, AUTOKEYJ, SA, NABDATE)"
                + " VALUES (?, ?, ?, NULL, ?, ?, 0, ?, ?, ?, NULL, ?, 0, ?)";

        for (Map<String, Object> line : lines) {
            Object bigo = line.getOrDefault("bigo", line.getOrDefault("remark", memo));
            Object[] args = new Object[] {
                    dNo,
                    line.get("editNo"),
                    dDate,
                    line.get("itemCode"),
                    vendorCode,
                    kind,
                    line.get("price"),
                    line.get("ea"),
                    line.get("gum"),
                    line.get("vat"),
                    0, // SA
                    0, // DAECHE
                    0, // EA2
                    bigo, // BIGO - 줄 적요(발주는 단위) (null 불가)
                    tag, // BIGO2 - 우리 전표 추적용
                    line.getOrDefault("bigo3", ""), // BIGO3 - 발주의 줄 적요
                    null, // ORDERCODE - 경영박사는 항상 NULL. 빈 문자열이면 품명·규격 수정이 막힌다
                    0, // POINT
                    0 // JIJOM
            };

            if (isOrder) {
                Number autokey = erpJdbcTemplate.queryForObject(insertIl, Number.class, args);
                if (autokey != null) {
                    Object ea = line.get("ea");
                    Object price = line.get("price");
                    String fojang = String.valueOf(line.getOrDefault("danwi", bigo != null ? bigo : ""));
                    erpJdbcTemplate.update(insertBalju,
                            dDate,
                            line.get("itemCode"),
                            vendorCode,
                            price,
                            ea,
                            ea, // JANEA (초기 잔여 수량 = 발주 수량)
                            fojang,
                            baljuNo,
                            autokey.longValue(),
                            dDate);
                }
            } else {
                erpJdbcTemplate.update(insertIl, args);
            }
        }

        // 전표번호 자기검증: 경영박사가 같은 순간 같은 dNO 를 쓰면 한 전표에 남의 줄이 섞인다.
        Integer foreign = erpJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + ilTable + " WHERE dDATE = ? AND dNO = ? AND ISNULL(BIGO2,'') <> ?",
                Integer.class, dDate, dNo, tag);
        if (foreign != null && foreign > 0) {
            throw new IllegalStateException("전표번호가 다른 전표와 겹쳤습니다. 다시 저장해 주세요.");
        }

        if (kind == KIND_PURCHASE) applyStockSideEffects(lines, 1);
        return dNo;
    }

    /**
     * 당일 전표번호 채번. 우리끼리의 동시 저장은 앱 잠금으로 직렬화된다(주문 전송과 같은 잠금).
     * 경영박사가 미리 뽑아 둔 MAX+1 을 비켜 가려고 MAX+2 를 쓴다. 남은 경합은 INSERT 후 자기검증으로 잡는다.
     */
    private int nextDno(String ilTable, String dDate) {
        ErpVoucherLock.acquire(erpJdbcTemplate, ilTable);
        Integer max = erpJdbcTemplate.queryForObject(
                "SELECT ISNULL(MAX(dNO),0) FROM " + ilTable + " WHERE dDATE = ?", Integer.class, dDate);
        return (max == null ? 0 : max) + 2;
    }

    /** 취소: BIGO2 태그가 붙은 우리 전표만 지운다. 남의 줄은 절대 건드리지 않는다. */
    @Transactional("erpTransactionManager")
    public void deleteVoucher(String requestId, String tag, String ilTable, String dDate, int dNo,
            int expectedLines, List<Map<String, Object>> lines) {
        // 발주 전표인 경우 경영박사 발주관리 테이블(BALJU)에서도 연결된 행을 삭제한다.
        try {
            erpJdbcTemplate.update(
                    "DELETE FROM BALJU WHERE AUTOKEYJ IN (SELECT AUTOKEY FROM " + ilTable + " WHERE dDATE = ? AND dNO = ? AND BIGO2 = ?)",
                    dDate, dNo, tag);
        } catch (Exception e) {
            // BALJU 테이블이 없거나 오류 발생 시에도 IL 테이블 취소는 진행
        }

        int deleted = erpJdbcTemplate.update(
                "DELETE FROM " + ilTable + " WHERE dDATE = ? AND dNO = ? AND BIGO2 = ?", dDate, dNo, tag);
        if (deleted != expectedLines) {
            // 경영박사에서 이미 수정/삭제했다는 뜻이다. 자동으로 맞추려 들지 않고 사람이 보게 한다.
            throw new IllegalStateException(
                    "삭제된 줄 수가 기록과 다릅니다(" + deleted + "/" + expectedLines + "). 경영박사에서 확인하세요.");
        }
        applyStockSideEffects(lines, -1);
    }

    /**
     * IL 테이블에만 들어가 있고 경영박사 BALJU 테이블에 누락된 발주 전표(KIND=13)를 동기화한다.
     * 키오스크에서 작성했으나 BALJU 에 없어 경영박사 [발주관리] 화면에 안 보이던 전표들을 복구한다.
     */
    @Transactional("erpTransactionManager")
    public int syncMissingBaljuRecords(String ilTable) {
        try {
            String syncSql = "INSERT INTO BALJU (dDATE, ITEMCODE, CUST, DATE2, PRICE, EA, EA2, JANEA, FOJANG, BALJUNO, PRINTED, AUTOKEYJ, SA, NABDATE) "
                    + "SELECT l.dDATE, l.ITEMCODE, l.CUST, NULL, l.PRICE, l.EA, 0, l.EA, ISNULL(l.BIGO, ''), "
                    + "REPLACE(l.dDATE, '.', '') + '-' + RIGHT('00000' + CAST(l.dNO AS VARCHAR), 5), "
                    + "NULL, l.AUTOKEY, 0, l.dDATE "
                    + "FROM " + ilTable + " l "
                    + "WHERE l.KIND = 13 "
                    + "AND NOT EXISTS (SELECT 1 FROM BALJU b WHERE b.AUTOKEYJ = l.AUTOKEY)";
            return erpJdbcTemplate.update(syncSql);
        } catch (Exception e) {
            return 0;
        }
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
