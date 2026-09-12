package com.example.demo.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

/**
 * "이 상품 주문한 곳에서 이것도 많이 주문했다" 추천의 집계 배치.
 *
 * ERP 거래이력(ILxx)에서 전표 단위 동시구매를 세어 kiosk Postgres 의 reco_item_pair 에 적재한다.
 * 키오스크 요청마다 ERP 를 조회하지 않는 이유는 두 가지다. ERP MSSQL 은 매장 Access 의 단방향
 * 사본이라 부하를 주고 싶지 않고, 13만 건 규모의 페어 계산은 밀리초 단위로 끝나지 않는다.
 * 거래이력은 하루 단위로만 바뀌므로 야간에 한 번 계산해 두면 충분하다.
 */
@Service
@Slf4j
public class RecommendationAggregator {

    private final JdbcTemplate erpJdbcTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final RecommendationService recommendationService;

    /** 집계에 사용할 연도 수. 2 면 올해+작년 ILxx 두 개를 본다. */
    @Value("${recommendation.years:2}")
    private int years;

    /** 이 횟수 미만으로 함께 팔린 조합은 우연으로 보고 버린다. */
    @Value("${recommendation.min-co-count:3}")
    private int minCoCount;

    /** 한 상품이 끌고 다니는 추천 후보 상한. 화면에는 몇 개만 쓰지만 품절·미등록 품목 제외를 감안해 여유를 둔다. */
    @Value("${recommendation.max-per-item:20}")
    private int maxPerItem;

    /** ERP 매출 구분. ErpSyncService 가 키오스크 주문을 넣을 때 쓰는 KIND=3(외상매출)과 같은 값이다. */
    private static final String SALES_KIND = "3";

    public RecommendationAggregator(@Qualifier("erpJdbcTemplate") JdbcTemplate erpJdbcTemplate,
            @Qualifier("jdbcTemplate") JdbcTemplate jdbcTemplate,
            RecommendationService recommendationService) {
        this.erpJdbcTemplate = erpJdbcTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.recommendationService = recommendationService;
    }

    /** 매일 새벽 3시 30분. 영업 시간과 ERP 동기화를 모두 피한 시간대다. */
    @Scheduled(cron = "${recommendation.cron:0 30 3 * * *}")
    public void scheduledRebuild() {
        try {
            rebuild();
        } catch (Exception e) {
            // 추천은 없어도 주문은 되어야 하므로, 실패해도 로그만 남기고 기존 집계를 그대로 쓴다.
            log.error("Recommendation rebuild failed; keeping the previous pair table", e);
        }
    }

    /**
     * ERP 이력을 다시 읽어 페어 테이블을 통째로 교체한다.
     *
     * @return 적재된 행 수(방향별로 세므로 페어 수의 2배)
     */
    @Transactional
    public int rebuild() {
        long startedAt = System.currentTimeMillis();

        List<String> tables = resolveHistoryTables();
        if (tables.isEmpty()) {
            log.warn("No ERP history tables found for the last {} year(s); skipping rebuild", years);
            return 0;
        }

        List<Map<String, Object>> pairs = erpJdbcTemplate.queryForList(buildPairQuery(tables));
        log.info("Aggregated {} co-purchase pairs from {}", pairs.size(), tables);

        List<Object[]> rows = toDirectedRows(pairs);

        jdbcTemplate.update("DELETE FROM reco_item_pair");
        jdbcTemplate.batchUpdate(
                "INSERT INTO reco_item_pair (source_erp_code, target_erp_code, co_count, score) VALUES (?, ?, ?, ?)",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                        Object[] row = rows.get(i);
                        ps.setString(1, (String) row[0]);
                        ps.setString(2, (String) row[1]);
                        ps.setInt(3, (Integer) row[2]);
                        ps.setDouble(4, (Double) row[3]);
                    }

                    @Override
                    public int getBatchSize() {
                        return rows.size();
                    }
                });

        recommendationService.invalidate();
        log.info("Recommendation rebuild done: {} rows in {} ms", rows.size(), System.currentTimeMillis() - startedAt);
        return rows.size();
    }

    /**
     * 페어(A,B) 하나를 A→B, B→A 두 행으로 펼치고 상품별 상위 N 개만 남긴다.
     * 상한을 두지 않으면 잡자재처럼 아무데나 끼는 품목이 수백 개의 꼬리를 달고 들어온다.
     */
    private List<Object[]> toDirectedRows(List<Map<String, Object>> pairs) {
        Map<String, List<Object[]>> bySource = new HashMap<>();
        for (Map<String, Object> pair : pairs) {
            String a = (String) pair.get("codeA");
            String b = (String) pair.get("codeB");
            Integer co = ((Number) pair.get("co")).intValue();
            Double score = ((Number) pair.get("score")).doubleValue();

            bySource.computeIfAbsent(a, k -> new ArrayList<>()).add(new Object[] { a, b, co, score });
            bySource.computeIfAbsent(b, k -> new ArrayList<>()).add(new Object[] { b, a, co, score });
        }

        List<Object[]> rows = new ArrayList<>();
        for (List<Object[]> candidates : bySource.values()) {
            candidates.sort(Comparator.comparingDouble((Object[] r) -> (Double) r[3]).reversed());
            rows.addAll(candidates.subList(0, Math.min(candidates.size(), maxPerItem)));
        }
        return rows;
    }

    /** 최근 N 개 연도의 ILxx 중 실제로 존재하는 테이블만 고른다. 연초에는 올해 테이블이 아직 없을 수 있다. */
    private List<String> resolveHistoryTables() {
        int currentYear = LocalDate.now().getYear();
        List<String> tables = new ArrayList<>();
        for (int i = 0; i < Math.max(1, years); i++) {
            String name = String.format("IL%02d", (currentYear - i) % 100);
            Integer exists = erpJdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sys.tables WHERE name = ?", Integer.class, name);
            if (exists != null && exists > 0) {
                tables.add(name);
            }
        }
        return tables;
    }

    /**
     * 전표 = (dDATE, dNO, CUST). ErpSyncService 가 주문 하나를 같은 dNO 로 묶어 넣으므로,
     * 이 조합이 곧 "한 번에 주문한 묶음"이 된다. 같은 전표에서 같은 품목이 여러 줄로 나뉘어도
     * DISTINCT 로 한 번만 세어, 수량 분할이 동시구매 횟수를 부풀리지 않게 한다.
     *
     * 전표 목록과 품목별 등장 횟수를 임시 테이블로 구체화한 뒤 조인한다. 같은 계산을 CTE 로
     * 묶으면 SQL Server 가 CTE 를 결과로 들고 있지 않고 참조될 때마다 다시 실행해서, 전표 목록을
     * 세 번 만들고 자기조인까지 되풀이한다(측정값: CTE 338초 → 임시 테이블 1초).
     *
     * 최소 동시구매 횟수는 바인딩 파라미터가 아니라 리터럴로 박는다. 설정에서 온 int 라 주입
     * 위험은 없고, 상수여야 옵티마이저가 HAVING 의 선택도를 제대로 추정한다.
     */
    private String buildPairQuery(List<String> tables) {
        StringBuilder union = new StringBuilder();
        for (String table : tables) {
            if (union.length() > 0) union.append(" UNION ALL ");
            union.append("SELECT dDATE, dNO, CUST, ITEMCODE FROM ").append(table)
                    .append(" WHERE KIND = '").append(SALES_KIND).append("'");
        }

        // 임시 테이블은 세션에 남는다. 커넥션 풀이 같은 커넥션을 재사용하면 두 번째 집계에서
        // 이름 충돌이 나므로 매번 먼저 지운다.
        return "SET NOCOUNT ON; "
                + "DROP TABLE IF EXISTS #basket; DROP TABLE IF EXISTS #item_count; DROP TABLE IF EXISTS #pair; "
                + "SELECT DISTINCT "
                + "  CAST(dDATE AS varchar(20)) + '|' + CAST(dNO AS varchar(20)) + '|' + CAST(CUST AS varchar(20)) AS bid, "
                + "  LTRIM(RTRIM(CAST(ITEMCODE AS varchar(20)))) AS code "
                + "INTO #basket FROM (" + union + ") tx WHERE ITEMCODE IS NOT NULL; "
                + "CREATE CLUSTERED INDEX ix_basket ON #basket(bid, code); "
                + "SELECT code, COUNT(*) AS n INTO #item_count FROM #basket GROUP BY code; "
                + "CREATE CLUSTERED INDEX ix_item_count ON #item_count(code); "
                + "SELECT x.code AS codeA, y.code AS codeB, COUNT(*) AS co INTO #pair "
                + "  FROM #basket x JOIN #basket y ON x.bid = y.bid AND x.code < y.code "
                + "  GROUP BY x.code, y.code HAVING COUNT(*) >= " + minCoCount + "; "
                + "SELECT p.codeA, p.codeB, p.co, p.co / SQRT(ca.n * 1.0 * cb.n) AS score "
                + "  FROM #pair p JOIN #item_count ca ON ca.code = p.codeA JOIN #item_count cb ON cb.code = p.codeB;";
    }
}
