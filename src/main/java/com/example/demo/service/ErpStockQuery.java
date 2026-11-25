package com.example.demo.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** 경영박사와 같은 기준의 현재고를 품목코드별로 조회한다. */
final class ErpStockQuery {

    private static final ZoneId ERP_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter ERP_YEAR = DateTimeFormatter.ofPattern("yy", Locale.ROOT);
    private static final DateTimeFormatter ERP_DATE = DateTimeFormatter.ofPattern("yy.MM.dd", Locale.ROOT);

    private ErpStockQuery() {
    }

    // 당해 연도 기초이월에 오늘까지의 매입·매출·기타 재고 전표를 합산한다.
    static String withCurrentStock(String query) {
        LocalDate today = LocalDate.now(ERP_ZONE);
        return """
                WITH movement AS (
                    SELECT ITEMCODE, SUM(CASE WHEN KIND = 3 THEN -EA ELSE EA END) AS quantity
                    FROM [IL%1$s]
                    WHERE ITEMCODE >= 100 AND KIND IN (3, 4, 10) AND dDATE <= '%2$s'
                    GROUP BY ITEMCODE
                ), current_stock AS (
                    SELECT COALESCE(o.ITEMCODE, m.ITEMCODE) AS ITEMCODE,
                           COALESCE(o.IWOL1, 0) + COALESCE(m.quantity, 0) AS quantity
                    FROM [IT%1$s] o
                    FULL OUTER JOIN movement m ON m.ITEMCODE = o.ITEMCODE
                )
                """.formatted(today.format(ERP_YEAR), today.format(ERP_DATE)) + query;
    }
}
