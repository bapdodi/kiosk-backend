package com.example.demo.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.example.demo.entity.ErpReceiptLog;
import com.example.demo.entity.ErpReceiptLogLine;
import com.example.demo.repository.ErpReceiptLogRepository;
import com.example.demo.util.HangulKeyboard;

import lombok.extern.slf4j.Slf4j;

/**
 * 관리자 화면에서 ERP 매입전표(입고)를 직접 넣는 기능.
 *
 * 경영박사(DrNet)는 동시접속 2대 제한이 있어 세 번째 담당자가 ERP 를 띄울 수 없다.
 * 그래서 입고만 이 화면에서 처리하고, ERP 에는 경영박사가 넣는 것과 같은 형태의
 * IL&lt;yy&gt; KIND=4(매입) 전표를 기록한다. 매출 전송(ErpOrderSender)과 같은 구조다.
 *
 * ITEM.JEGO(현재고)는 기본적으로 건드리지 않는다. 운영 데이터에서 확인해 보면
 * 기초이월 + 매입 - 매출 이 JEGO 와 일치하지 않는다(품목 103: 12+15-12=15 인데 JEGO=9).
 * JEGO 는 경영박사가 자체 규칙으로 유지하는 파생값이라, 우리가 더하면 이중가산되거나
 * 경영박사 재계산에 덮인다. 복제본에서 경영박사 입고 1건의 before/after 를 대조해
 * 확정한 뒤에만 erp.receiving.stock-mode 로 켠다.
 *
 * TODO(거래명세서 자동 입력, 이어서 할 작업): 매입처 거래명세서 PDF 를 올리면 읽어서
 * 이 화면의 거래처·품목 줄을 채우고, 사람이 확인한 뒤 기존 preview → createVoucher 로
 * 등록하게 만든다. 2026-09-23 로컬 DR_ERP 에서 확인한 사실:
 * - 매입처 거래는 KIND=13(발주)이 먼저 찍히고 명세서가 오면 KIND=4(매입)로 다시 들어간다.
 *   명세서 줄은 같은 거래처의 최근 KIND=13 발주 줄과 먼저 대조한다.
 * - 명세서 품명은 ERP 품명과 다르다(예: "일반배관용 강관 304 NH (40S u)42.7x1.2x6000" =
 *   "수파이프 KS 3595 40A(42.7*1.2)"). 규격 숫자로 검색하고, 확정한 짝은 거래처별 별칭으로 저장한다.
 * - 파이프 명세서 단가는 M당이다. 수량은 본이고 ERP 단가는 공급가액 ÷ 수량(= M당 × 6)이다.
 * - 명세서 세액은 GUM/10 과 1원씩 다를 수 있다. 막지 말고 경고만 한다.
 * - GURAE.SAUP(사업자번호)는 매입처 172곳 중 6곳만 채워져 있어 거래처는 상호로 맞춘다.
 * 추출은 PDF 텍스트 → CPU OCR → 검산 실패 시에만 LLM(Gemini) 순서로 한다.
 */
@Service
@Slf4j
public class ErpReceivingService {

    /** 우리가 넣은 전표만 골라내기 위한 BIGO2 태그. 매출 전송의 "KIOSK-" 와 구분된다. */
    private static final String TAG_PREFIX = "KIOSK-IN-";
    private static final String ORDER_TAG_PREFIX = "KIOSK-ORD-";

    private static final DateTimeFormatter ERP_DATE = DateTimeFormatter.ofPattern("yy.MM.dd");
    private static final DateTimeFormatter ERP_YEAR = DateTimeFormatter.ofPattern("yy");

    private final JdbcTemplate erpJdbcTemplate;
    private final ErpReceiptLogRepository receiptLogRepository;
    private final ErpReceivingWriter writer;

    @Value("${erp.receiving.write-enabled:false}")
    private boolean writeEnabled;

    /** 월마감된 과거 전표를 건드리지 않도록 입력 가능한 날짜 범위를 오늘 기준으로 제한한다. */
    @Value("${erp.receiving.date-window-days:7}")
    private int dateWindowDays;

    public ErpReceivingService(@Qualifier("erpJdbcTemplate") JdbcTemplate erpJdbcTemplate,
            ErpReceiptLogRepository receiptLogRepository,
            ErpReceivingWriter writer) {
        this.erpJdbcTemplate = erpJdbcTemplate;
        this.receiptLogRepository = receiptLogRepository;
        this.writer = writer;
    }

    // ── 읽기 ────────────────────────────────────────────────────────────────

    public Map<String, Object> status() {
        return Map.of(
                "writeEnabled", writeEnabled,
                "stockMode", writer.stockMode(),
                "dateWindowDays", dateWindowDays,
                "today", LocalDate.now().format(ERP_DATE));
    }

    /**
     * 품목 검색. 담당자가 수량만 넣으면 되도록 직전 매입의 매입처/단가를 같이 붙여 준다.
     * 숫자를 넣으면 품목코드 완전일치를 먼저 보여준다.
     */
    public List<Map<String, Object>> searchItems(String q) {
        String keyword = q == null ? "" : q.trim();
        if (keyword.isEmpty()) {
            throw new IllegalArgumentException("검색어를 입력하세요.");
        }
        List<Map<String, Object>> found = searchItemsExact(keyword);
        // 한영키를 안 누르고 쳤다면("dpfqh") 한글로 바꾼 검색어("엘보")로도 찾아 뒤에 붙인다.
        if (!HangulKeyboard.looksLikeMistypedHangul(keyword)) return found;
        String hangul = HangulKeyboard.toHangul(keyword);
        if (hangul.equals(keyword)) return found;
        Map<Object, Map<String, Object>> merged = new LinkedHashMap<>();
        for (Map<String, Object> row : found) merged.put(row.get("CODE"), row);
        for (Map<String, Object> row : searchItemsExact(hangul)) merged.putIfAbsent(row.get("CODE"), row);
        return new ArrayList<>(merged.values()).subList(0, Math.min(50, merged.size()));
    }

    private List<Map<String, Object>> searchItemsExact(String keyword) {
        String ilTable = ilTableFor(LocalDate.now());
        String like = "%" + keyword + "%";
        String startsWith = keyword + "%";
        Integer code = parseIntOrNull(keyword);

        // 가나다순으로만 정렬하면 "피비"를 쳤을 때 "가위 피비용..." 이 먼저 올라온다.
        // 찾는 물건은 보통 친 글자로 시작하므로, 관련도를 먼저 보고 그 안에서 이름순으로 준다.
        //   0 품목코드 일치 · 1 품명이 그 글자로 시작 · 2 품명에 포함 · 3 규격에만 포함
        String relevance = "CASE"
                + (code != null ? " WHEN i.CODE = ? THEN 0" : "")
                + " WHEN i.ITEM LIKE ? THEN 1"
                + " WHEN i.ITEM LIKE ? THEN 2"
                + " ELSE 3 END";

        String sql = "SELECT TOP 50 i.CODE, i.ITEM, i.GYU, ISNULL(i.JEGO,0) AS JEGO, i.PARTCODE,"
                + " LTRIM(RTRIM(ISNULL(i.DANWI,''))) AS DANWI,"
                // 경영박사 품목 조회 화면과 같은 값들이다(입고가=INPR, 출고A/B/C가=OUTA/OUTB/OUTC).
                + " ISNULL(i.INPR,0) AS INPR, ISNULL(i.OUTA,0) AS OUTA,"
                + " ISNULL(i.OUTB,0) AS OUTB, ISNULL(i.OUTC,0) AS OUTC,"
                + " p.CUST AS lastVendorCode, LTRIM(RTRIM(ISNULL(g.NAME,''))) AS lastVendorName,"
                + " p.PRICE AS lastPrice, p.dDATE AS lastDate"
                + " FROM ITEM i"
                + " OUTER APPLY (SELECT TOP 1 CUST, PRICE, dDATE FROM " + ilTable
                + "              WHERE ITEMCODE = i.CODE AND KIND = " + ErpReceivingWriter.KIND_PURCHASE
                + "              ORDER BY dDATE DESC, dNO DESC, EDITNO DESC) p"
                + " LEFT JOIN GURAE g ON g.CODE = p.CUST"
                + " WHERE i.CODE >= 100 AND (i.ITEM LIKE ? OR i.GYU LIKE ?"
                + (code != null ? " OR i.CODE = ?" : "") + ")"
                + " ORDER BY " + relevance + ", i.ITEM, i.GYU";

        List<Object> args = new ArrayList<>();
        args.add(like);                       // WHERE i.ITEM LIKE
        args.add(like);                       // WHERE i.GYU LIKE
        if (code != null) args.add(code);     // WHERE i.CODE =
        if (code != null) args.add(code);     // ORDER BY CASE WHEN i.CODE =
        args.add(startsWith);                 // ORDER BY CASE WHEN i.ITEM LIKE '키워드%'
        args.add(like);                       // ORDER BY CASE WHEN i.ITEM LIKE '%키워드%'

        return erpJdbcTemplate.queryForList(sql, args.toArray());
    }

    /** 품목의 최근 거래 20건. 매입/매출/발주를 섞어 보여줘야 "왜 재고가 이런지" 판단이 된다. */
    public List<Map<String, Object>> recentTransactions(int itemCode) {
        String ilTable = ilTableFor(LocalDate.now());
        return erpJdbcTemplate.queryForList(
                // GURAE 에도 dDATE 가 있어 접두어 없이 쓰면 ambiguous 로 깨진다.
                "SELECT TOP 20 l.dDATE, l.dNO, l.EDITNO, l.KIND, l.CUST, l.PRICE, l.EA, l.GUM,"
                        + " LTRIM(RTRIM(ISNULL(g.NAME,''))) AS custName, ISNULL(l.BIGO2,'') AS BIGO2"
                        + " FROM " + ilTable + " l LEFT JOIN GURAE g ON g.CODE = l.CUST"
                        + " WHERE l.ITEMCODE = ? ORDER BY l.dDATE DESC, l.dNO DESC, l.EDITNO DESC",
                itemCode);
    }

    /**
     * 발주 추천. 사람이 기준을 넣지 않고 품목별 실제 거래 이력으로 스스로 계산한다(읽기 전용).
     *
     * - 하루 판매량: 최근 30/90/365일 평균을 5:3:2 로 섞어 최근 추세에 무게를 둔다.
     * - 변동: 최근 13주 주간 판매량의 표준편차. 들쭉날쭉한 품목은 안전재고를 더 잡는다.
     * - 입고까지 걸리는 기간: 그 품목의 발주(KIND=13) 뒤 첫 매입(KIND=4)까지 걸린 날의 중앙값(없으면 5일).
     * - 발주 주기: 그 품목 매입일 간격의 중앙값(7~45일로 제한, 없으면 14일).
     * - 이미 넣은 발주: 최근 21일 발주 수량에서 그 뒤 입고된 수량을 뺀 만큼은 곧 들어올 재고로 본다.
     * - 발주점 = 하루 판매량 × 입고 기간 + 안전재고(95% 서비스 수준). 재고가 발주점 이하면 추천한다.
     * - 추천 수량 = 하루 판매량 × (입고 기간 + 발주 주기) + 안전재고 - (재고 + 입고 예정).
     * JEGO 는 경영박사가 유지하는 값이라 읽기만 한다. 음수면 0 으로 보고 표시해 준다.
     */
    public Map<String, Object> reorderSuggestions() {
        final int history = 365;
        final double z = 1.65;
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(history - 1);

        // 판매(KIND=3): 품목별 "며칠 전" 배열
        Map<Integer, double[]> sales = new java.util.HashMap<>();
        // 발주(13)/매입(4): 품목별 (날짜, 종류, 거래처, 수량, 단가)
        Map<Integer, List<Object[]>> flows = new java.util.HashMap<>();
        for (int year = from.getYear(); year <= today.getYear(); year++) {
            String table = "IL" + String.format("%02d", year % 100);
            Integer exists = erpJdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sys.tables WHERE name = ?", Integer.class, table);
            if (exists == null || exists == 0) continue;
            LocalDate partFrom = year == from.getYear() ? from : LocalDate.of(year, 1, 1);
            LocalDate partTo = year == today.getYear() ? today : LocalDate.of(year, 12, 31);
            erpJdbcTemplate.query(
                    "SELECT ITEMCODE, dDATE, KIND, CUST, EA, PRICE FROM " + table
                            + " WHERE KIND IN (3, 4, 13) AND dDATE BETWEEN ? AND ? AND ITEMCODE >= 100",
                    rs -> {
                        int item = rs.getInt("ITEMCODE");
                        LocalDate d;
                        try {
                            d = LocalDate.parse("20" + rs.getString("dDATE").trim().replace('.', '-'));
                        } catch (Exception e) {
                            return;
                        }
                        int ago = (int) java.time.temporal.ChronoUnit.DAYS.between(d, today);
                        if (ago < 0 || ago >= history) return;
                        int kind = rs.getInt("KIND");
                        double ea = rs.getDouble("EA");
                        if (kind == 3) {
                            sales.computeIfAbsent(item, k -> new double[history])[ago] += ea;
                        } else {
                            flows.computeIfAbsent(item, k -> new ArrayList<>())
                                    .add(new Object[] {d, kind, rs.getInt("CUST"), ea, rs.getDouble("PRICE")});
                        }
                    },
                    partFrom.format(ERP_DATE), partTo.format(ERP_DATE));
        }

        Map<Integer, Map<String, Object>> items = new java.util.HashMap<>();
        for (Map<String, Object> r : erpJdbcTemplate.queryForList(
                "SELECT CODE, LTRIM(RTRIM(ITEM)) AS ITEM, LTRIM(RTRIM(ISNULL(GYU,''))) AS GYU,"
                        + " LTRIM(RTRIM(ISNULL(DANWI,''))) AS DANWI, ISNULL(JEGO,0) AS JEGO, ISNULL(INPR,0) AS INPR"
                        + " FROM ITEM WHERE CODE >= 100")) {
            items.put(((Number) r.get("CODE")).intValue(), r);
        }
        Map<Integer, String> vendorNames = new java.util.HashMap<>();
        for (Map<String, Object> r : erpJdbcTemplate.queryForList("SELECT CODE, LTRIM(RTRIM(ISNULL(NAME,''))) AS NAME FROM GURAE")) {
            vendorNames.put(((Number) r.get("CODE")).intValue(), String.valueOf(r.get("NAME")));
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<Integer, double[]> e : sales.entrySet()) {
            Map<String, Object> item = items.get(e.getKey());
            if (item == null) continue;
            double[] day = e.getValue();

            int saleDays = 0;
            int lastSale = -1;
            double s30 = 0, s90 = 0, s365 = 0;
            for (int i = 0; i < history; i++) {
                if (day[i] <= 0) continue;
                saleDays++;
                if (lastSale < 0) lastSale = i;
                s365 += day[i];
                if (i < 90) s90 += day[i];
                if (i < 30) s30 += day[i];
            }
            // 거의 안 팔리거나(연 3일 미만) 석 달 넘게 안 팔린 품목은 추천하지 않는다.
            if (saleDays < 3 || lastSale > 90) continue;
            double rate = 0.5 * (s30 / 30) + 0.3 * (s90 / 90) + 0.2 * (s365 / history);
            if (rate <= 0) continue;

            // 최근 13주 주간 판매량의 표준편차
            double[] week = new double[13];
            for (int i = 0; i < 91; i++) week[i / 7] += day[i];
            double mean = 0;
            for (double w : week) mean += w;
            mean /= week.length;
            double var = 0;
            for (double w : week) var += (w - mean) * (w - mean);
            double sigmaWeek = Math.sqrt(var / week.length);

            // 입고 기간(발주→매입)과 발주 주기(매입 간격), 입고 예정, 주 거래처
            List<Object[]> flow = flows.getOrDefault(e.getKey(), List.of());
            List<LocalDate> orders = new ArrayList<>();
            List<LocalDate> buys = new ArrayList<>();
            Map<Integer, Integer> vendorCount = new java.util.HashMap<>();
            Map<Integer, Object[]> vendorLast = new java.util.HashMap<>();
            double open13 = 0, in4 = 0;
            for (Object[] f : flow) {
                LocalDate d = (LocalDate) f[0];
                int kind = (Integer) f[1];
                boolean recent = d.isAfter(today.minusDays(22));
                if (kind == 13) {
                    orders.add(d);
                    if (recent) open13 += (Double) f[3];
                } else {
                    buys.add(d);
                    if (recent) in4 += (Double) f[3];
                    int cust = (Integer) f[2];
                    vendorCount.merge(cust, 1, Integer::sum);
                    Object[] last = vendorLast.get(cust);
                    if (last == null || ((LocalDate) last[0]).isBefore(d)) vendorLast.put(cust, f);
                }
            }
            java.util.Collections.sort(buys);
            List<Integer> leads = new ArrayList<>();
            for (LocalDate o : orders) {
                for (LocalDate b : buys) {
                    if (!b.isBefore(o)) {
                        long gap = java.time.temporal.ChronoUnit.DAYS.between(o, b);
                        if (gap <= 45) leads.add((int) gap);
                        break;
                    }
                }
            }
            int lead = leads.isEmpty() ? 5 : Math.max(1, Math.min(30, median(leads)));
            List<Integer> gaps = new ArrayList<>();
            List<LocalDate> distinctBuys = new ArrayList<>(new java.util.TreeSet<>(buys));
            for (int i = 1; i < distinctBuys.size(); i++) {
                gaps.add((int) java.time.temporal.ChronoUnit.DAYS.between(distinctBuys.get(i - 1), distinctBuys.get(i)));
            }
            int cycle = gaps.isEmpty() ? 14 : Math.max(7, Math.min(45, median(gaps)));
            double incoming = Math.max(0, open13 - in4);
            // 한 번 발주하는 기간(입고 기간 + 발주 주기) 동안 1개도 안 팔릴 만큼 느린 품목은 추천하지 않는다.
            if (rate * (lead + cycle) < 1) continue;

            double jego = ((Number) item.get("JEGO")).doubleValue();
            double stock = Math.max(jego, 0) + incoming;
            double safety = z * sigmaWeek * Math.sqrt(lead / 7.0);
            double reorderPoint = rate * lead + safety;
            if (stock > reorderPoint) continue;

            double daysLeft = jego <= 0 ? 0 : jego / rate;
            int qty = (int) Math.max(1, Math.ceil(rate * (lead + cycle) + safety - stock));
            int urgency = jego <= 0 ? 0 : (daysLeft < lead ? 1 : 2);

            Integer vendor = vendorCount.entrySet().stream()
                    .max(java.util.Map.Entry.<Integer, Integer>comparingByValue()).map(java.util.Map.Entry::getKey).orElse(null);
            Map<String, Object> row = new LinkedHashMap<>(item);
            row.put("dailyAvg", Math.round(rate * 100) / 100.0);
            row.put("leadDays", lead);
            row.put("reorderPoint", (int) Math.ceil(reorderPoint));
            row.put("daysLeft", Math.round(daysLeft * 10) / 10.0);
            row.put("incoming", (int) Math.round(incoming));
            row.put("suggestQty", qty);
            row.put("urgency", urgency); // 0 품절, 1 긴급(입고 전에 바닥), 2 권장
            row.put("negativeStock", jego < 0);
            if (vendor != null) {
                Object[] last = vendorLast.get(vendor);
                row.put("lastVendorCode", vendor);
                row.put("lastVendorName", vendorNames.getOrDefault(vendor, ""));
                row.put("lastPrice", last == null ? null : last[4]);
            }
            rows.add(row);
        }
        rows.sort(java.util.Comparator
                .comparingInt((Map<String, Object> r) -> (Integer) r.get("urgency"))
                .thenComparingDouble(r -> ((Number) r.get("daysLeft")).doubleValue())
                .thenComparing(r -> -((Number) r.get("dailyAvg")).doubleValue()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", from.toString());
        result.put("to", today.toString());
        result.put("items", rows);
        return result;
    }

    private static int median(List<Integer> values) {
        List<Integer> sorted = new ArrayList<>(values);
        java.util.Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    /** 재고 조회용 전체 품목. 검색 없이 처음부터 다 보여 주고 거르는 것은 화면에서 한다(약 5천 건). */
    public List<Map<String, Object>> stockList() {
        return erpJdbcTemplate.queryForList(
                "SELECT CODE, LTRIM(RTRIM(ISNULL(ITEM,''))) AS ITEM, LTRIM(RTRIM(ISNULL(GYU,''))) AS GYU,"
                        + " LTRIM(RTRIM(ISNULL(DANWI,''))) AS DANWI, ISNULL(JEGO,0) AS JEGO,"
                        + " ISNULL(INPR,0) AS INPR, ISNULL(OUTA,0) AS OUTA, ISNULL(OUTB,0) AS OUTB"
                        + " FROM ITEM WHERE CODE >= 100 ORDER BY ITEM, GYU");
    }

    /** 매입 거래처 목록. DANGA=1 이 입고(매입) 거래처다 - 판매 단가표가 없어 주문 화면에서는 제외된다. */
    public List<Map<String, Object>> vendors(String q) {
        String keyword = q == null ? "" : q.trim();
        if (keyword.isEmpty()) {
            return erpJdbcTemplate.queryForList(
                    "SELECT CODE, LTRIM(RTRIM(NAME)) AS NAME FROM GURAE WHERE DANGA = 1 ORDER BY NAME");
        }
        String sql = "SELECT TOP 50 CODE, LTRIM(RTRIM(NAME)) AS NAME FROM GURAE WHERE DANGA = 1 AND NAME LIKE ? ORDER BY NAME";
        List<Map<String, Object>> found = erpJdbcTemplate.queryForList(sql, "%" + keyword + "%");
        if (!HangulKeyboard.looksLikeMistypedHangul(keyword)) return found;
        Map<Object, Map<String, Object>> merged = new LinkedHashMap<>();
        for (Map<String, Object> row : found) merged.put(row.get("CODE"), row);
        for (Map<String, Object> row : erpJdbcTemplate.queryForList(sql, "%" + HangulKeyboard.toHangul(keyword) + "%")) {
            merged.putIfAbsent(row.get("CODE"), row);
        }
        return new ArrayList<>(merged.values()).subList(0, Math.min(50, merged.size()));
    }

    /** 경영박사 MSSQL의 실제 매입(KIND=4) 전표를 기간별로 조회한다. */
    public List<Map<String, Object>> erpHistory(String from, String to, String q, int kind) {
        List<Map<String, Object>> found = erpHistoryExact(from, to, q, kind);
        String keyword = q == null ? "" : q.trim();
        // 한영키를 안 누르고 친 검색어도 한글로 바꿔 한 번 더 찾는다(품목/거래처 검색과 같은 규칙).
        if (!HangulKeyboard.looksLikeMistypedHangul(keyword)) return found;
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        for (Map<String, Object> row : found) merged.put(historyKey(row), row);
        for (Map<String, Object> row : erpHistoryExact(from, to, HangulKeyboard.toHangul(keyword), kind)) {
            merged.putIfAbsent(historyKey(row), row);
        }
        List<Map<String, Object>> all = new ArrayList<>(merged.values());
        all.sort((a, b) -> {
            int dateCompare = String.valueOf(b.get("erpDate")).compareTo(String.valueOf(a.get("erpDate")));
            if (dateCompare != 0) return dateCompare;
            return Integer.compare(((Number) b.get("voucherNo")).intValue(), ((Number) a.get("voucherNo")).intValue());
        });
        return all;
    }

    private String historyKey(Map<String, Object> row) {
        return row.get("erpDate") + "|" + row.get("voucherNo") + "|" + row.get("vendorCode");
    }

    private List<Map<String, Object>> erpHistoryExact(String from, String to, String q, int kind) {
        if (kind != ErpReceivingWriter.KIND_PURCHASE && kind != ErpReceivingWriter.KIND_ORDER) {
            throw new IllegalArgumentException("조회할 수 없는 전표 종류입니다.");
        }
        LocalDate fromDate = from == null || from.isBlank() ? LocalDate.now().withDayOfMonth(1) : LocalDate.parse(from);
        LocalDate toDate = to == null || to.isBlank() ? LocalDate.now() : LocalDate.parse(to);
        if (fromDate.isAfter(toDate)) throw new IllegalArgumentException("시작일은 종료일보다 늦을 수 없습니다.");
        if (fromDate.plusYears(2).isBefore(toDate)) throw new IllegalArgumentException("조회 기간은 2년 이내로 선택하세요.");
        String keyword = q == null ? "" : q.trim();

        List<Map<String, Object>> result = new ArrayList<>();
        for (int year = fromDate.getYear(); year <= toDate.getYear(); year++) {
            String table = "IL" + String.format("%02d", year % 100);
            Integer exists = erpJdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sys.tables WHERE name = ?", Integer.class, table);
            if (exists == null || exists == 0) continue;
            LocalDate partFrom = year == fromDate.getYear() ? fromDate : LocalDate.of(year, 1, 1);
            LocalDate partTo = year == toDate.getYear() ? toDate : LocalDate.of(year, 12, 31);
            String searchClause = keyword.isEmpty() ? "" :
                    " AND (g.NAME LIKE ? OR EXISTS (SELECT 1 FROM " + table + " sx"
                            + " JOIN ITEM ix ON ix.CODE=sx.ITEMCODE"
                            + " WHERE sx.KIND=? AND sx.dDATE=l.dDATE AND sx.dNO=l.dNO AND sx.CUST=l.CUST"
                            + " AND (ix.ITEM LIKE ? OR ix.GYU LIKE ?)))";
            String sql = "SELECT l.dDATE AS erpDate, l.dNO AS voucherNo, l.CUST AS vendorCode,"
                            + " LTRIM(RTRIM(ISNULL(g.NAME,''))) AS vendorName, COUNT(*) AS lineCount,"
                            + " SUM(l.GUM) AS totalAmount, SUM(ISNULL(l.VAT,0)) AS totalVat,"
                            + " LTRIM(RTRIM(ISNULL(MIN(i.ITEM),''))) AS firstItem, MIN(ISNULL(l.BIGO2,'')) AS tag"
                            + " FROM " + table + " l"
                            + " LEFT JOIN GURAE g ON g.CODE=l.CUST LEFT JOIN ITEM i ON i.CODE=l.ITEMCODE"
                            + " WHERE l.KIND=? AND l.dDATE BETWEEN ? AND ?" + searchClause
                            + " GROUP BY l.dDATE,l.dNO,l.CUST,g.NAME ORDER BY l.dDATE DESC,l.dNO DESC";
            List<Object> args = new ArrayList<>();
            args.add(kind);
            args.add(partFrom.format(ERP_DATE));
            args.add(partTo.format(ERP_DATE));
            if (!keyword.isEmpty()) {
                String like = "%" + keyword + "%";
                args.add(like);
                args.add(kind);
                args.add(like);
                args.add(like);
            }
            result.addAll(erpJdbcTemplate.queryForList(sql, args.toArray()));
        }

        // 우리 화면에서 만든 살아 있는 전표만 취소 버튼을 허용한다.
        Map<String, ErpReceiptLog> local = new LinkedHashMap<>();
        for (ErpReceiptLog logRow : receiptLogRepository.findByErpDateBetweenOrderByCreatedAtDesc(
                fromDate.format(ERP_DATE), toDate.format(ERP_DATE))) {
            local.put(logRow.getErpDate() + "|" + logRow.getVoucherNo() + "|" + logRow.getVendorCode(), logRow);
        }
        for (Map<String, Object> row : result) {
            String key = row.get("erpDate") + "|" + row.get("voucherNo") + "|" + row.get("vendorCode");
            ErpReceiptLog logRow = local.get(key);
            row.put("localLogId", logRow != null && "CREATED".equals(logRow.getStatus()) ? logRow.getId() : null);
            row.put("status", "CREATED");
            // 이 화면에서 만든 발주 전표면 취소할 수 있도록 요청 ID 를 알려 준다.
            String tag = String.valueOf(row.remove("tag"));
            row.put("orderRequestId", kind == ErpReceivingWriter.KIND_ORDER && tag.startsWith(ORDER_TAG_PREFIX)
                    ? tag.substring(ORDER_TAG_PREFIX.length()) : null);
        }
        result.sort((a, b) -> {
            int dateCompare = String.valueOf(b.get("erpDate")).compareTo(String.valueOf(a.get("erpDate")));
            if (dateCompare != 0) return dateCompare;
            return Integer.compare(((Number) b.get("voucherNo")).intValue(), ((Number) a.get("voucherNo")).intValue());
        });
        return result;
    }

    public Map<String, Object> erpHistoryDetail(String date, int voucherNo, int vendorCode, int kind) {
        if (kind != ErpReceivingWriter.KIND_PURCHASE && kind != ErpReceivingWriter.KIND_ORDER) {
            throw new IllegalArgumentException("조회할 수 없는 전표 종류입니다.");
        }
        LocalDate parsed = LocalDate.parse("20" + date.replace('.', '-'));
        String table = ilTableFor(parsed);
        List<Map<String, Object>> lines = erpJdbcTemplate.queryForList(
                "SELECT l.EDITNO AS editNo,l.ITEMCODE AS itemCode,LTRIM(RTRIM(ISNULL(i.ITEM,''))) AS itemName,"
                        + " LTRIM(RTRIM(ISNULL(i.GYU,''))) AS gyu,LTRIM(RTRIM(ISNULL(i.DANWI,''))) AS danwi,"
                        + " l.EA AS ea,l.PRICE AS price,l.GUM AS gum,ISNULL(l.VAT,0) AS vat,"
                        + " LTRIM(RTRIM(ISNULL(l.BIGO,''))) AS remark"
                        + " FROM " + table + " l LEFT JOIN ITEM i ON i.CODE=l.ITEMCODE"
                        + " WHERE l.KIND=? AND l.dDATE=? AND l.dNO=? AND l.CUST=? ORDER BY l.EDITNO",
                kind, date, voucherNo, vendorCode);
        if (lines.isEmpty()) throw new IllegalArgumentException("ERP 전표를 찾을 수 없습니다.");
        if (kind == ErpReceivingWriter.KIND_ORDER) {
            // 발주 줄은 BIGO 가 단위다. 적요는 우리가 BIGO3 에 넣는다.
            Map<Integer, Map<String, Object>> extra = new java.util.HashMap<>();
            for (Map<String, Object> r : erpJdbcTemplate.queryForList(
                    "SELECT EDITNO, LTRIM(RTRIM(ISNULL(BIGO,''))) AS unit, LTRIM(RTRIM(ISNULL(BIGO3,''))) AS memo"
                            + " FROM " + table + " WHERE KIND=? AND dDATE=? AND dNO=? AND CUST=?",
                    kind, date, voucherNo, vendorCode)) {
                extra.put(((Number) r.get("EDITNO")).intValue(), r);
            }
            for (Map<String, Object> line : lines) {
                Map<String, Object> e = extra.get(((Number) line.get("editNo")).intValue());
                if (e == null) continue;
                String unit = String.valueOf(e.get("unit"));
                if (!unit.isEmpty()) line.put("danwi", unit);
                line.put("remark", e.get("memo"));
            }
        }
        String vendorName = vendorName(vendorCode);
        long amount = lines.stream().mapToLong(line -> ((Number) line.get("gum")).longValue()).sum();
        long vat = lines.stream().mapToLong(line -> ((Number) line.get("vat")).longValue()).sum();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("erpDate", date); detail.put("ilTable", table); detail.put("voucherNo", voucherNo);
        detail.put("vendorCode", vendorCode); detail.put("vendorName", vendorName);
        detail.put("status", "CREATED"); detail.put("totalAmount", amount); detail.put("totalVat", vat);
        detail.put("lines", lines);
        return detail;
    }

    /** 이 화면에서 등록한 전표의 머리와 실제 ERP 품목 행을 상세 조회한다. */
    public Map<String, Object> historyDetail(long id) {
        ErpReceiptLog logRow = receiptLogRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("입고 이력을 찾을 수 없습니다: " + id));

        List<Map<String, Object>> lines = erpJdbcTemplate.queryForList(
                "SELECT l.EDITNO AS editNo, l.ITEMCODE AS itemCode,"
                        + " LTRIM(RTRIM(ISNULL(i.ITEM,''))) AS itemName,"
                        + " LTRIM(RTRIM(ISNULL(i.GYU,''))) AS gyu,"
                        + " LTRIM(RTRIM(ISNULL(i.DANWI,''))) AS danwi,"
                        + " l.EA AS ea, l.PRICE AS price, l.GUM AS gum,"
                        + " ISNULL(l.VAT,0) AS vat, LTRIM(RTRIM(ISNULL(l.BIGO,''))) AS remark"
                        + " FROM " + logRow.getIlTable() + " l LEFT JOIN ITEM i ON i.CODE = l.ITEMCODE"
                        + " WHERE l.dDATE = ? AND l.dNO = ? AND l.KIND = ? AND l.CUST = ?"
                        + " AND l.BIGO2 = ? ORDER BY l.EDITNO",
                logRow.getErpDate(), logRow.getVoucherNo(), ErpReceivingWriter.KIND_PURCHASE,
                Integer.valueOf(logRow.getVendorCode()), TAG_PREFIX + logRow.getRequestId());

        // 취소된 전표는 ERP 행이 이미 지워졌으므로 로컬 보조 이력을 사용한다.
        if (lines.isEmpty()) {
            lines = logRow.getLines().stream().map(line -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("editNo", line.getEditNo());
                item.put("itemCode", line.getItemCode());
                item.put("itemName", line.getItemName());
                item.put("gyu", "");
                item.put("danwi", "");
                item.put("ea", line.getEa());
                item.put("price", line.getPrice());
                item.put("gum", line.getGum());
                item.put("vat", line.getVat());
                item.put("remark", logRow.getMemo() == null ? "" : logRow.getMemo());
                return item;
            }).toList();
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("id", logRow.getId());
        detail.put("erpDate", logRow.getErpDate());
        detail.put("ilTable", logRow.getIlTable());
        detail.put("voucherNo", logRow.getVoucherNo());
        detail.put("vendorCode", logRow.getVendorCode());
        detail.put("vendorName", logRow.getVendorName());
        detail.put("memo", logRow.getMemo());
        detail.put("status", logRow.getStatus());
        detail.put("createdBy", logRow.getCreatedBy());
        detail.put("createdAt", logRow.getCreatedAt());
        detail.put("totalAmount", logRow.getTotalAmount());
        detail.put("totalVat", lines.stream().mapToLong(line -> ((Number) line.get("vat")).longValue()).sum());
        detail.put("lines", lines);
        return detail;
    }

    // ── 미리보기 ────────────────────────────────────────────────────────────

    /** 쓰기 없이, 실제로 INSERT 될 IL 행을 그대로 계산해 돌려준다. */
    public Map<String, Object> preview(VoucherRequest request) {
        Voucher voucher = build(request);
        return Map.of(
                "ilTable", voucher.ilTable,
                "dDate", voucher.dDate,
                "dNo", voucher.dNo,
                "vendorCode", voucher.vendorCode,
                "vendorName", voucher.vendorName,
                "totalAmount", voucher.totalAmount,
                "totalVat", voucher.totalVat,
                "tag", voucher.tag,
                "stockMode", writer.stockMode(),
                "lines", voucher.lines);
    }

    // ── 쓰기 ────────────────────────────────────────────────────────────────

    /**
     * 전표 저장. ERP 를 한 트랜잭션으로 묶고, 로컬 이력은 커밋 이후 별도로 남긴다.
     * (두 DB 라 2PC 가 없다. ERP 가 진실의 원천이고 이력은 보조다.)
     */
    public Map<String, Object> createVoucher(VoucherRequest request, String actor) {
        if (!writeEnabled) {
            throw new IllegalStateException("읽기 전용 모드입니다. 관리자에게 문의하세요. (erp.receiving.write-enabled)");
        }
        Voucher voucher = build(request);

        Optional<ErpReceiptLog> already = receiptLogRepository.findByRequestId(voucher.requestId);
        if (already.isPresent() && "CREATED".equals(already.get().getStatus())) {
            log.info("입고 전표 {} 는 이미 처리됨", voucher.requestId);
            return result(already.get(), true);
        }

        // 전표번호는 ERP 트랜잭션 안에서 채번된다. build() 가 미리 계산한 값은 미리보기용 추정치일 뿐이다.
        voucher.dNo = writer.insertVoucher(voucher.requestId, voucher.tag, voucher.ilTable, voucher.dDate,
                voucher.vendorCode, voucher.memo, voucher.lines, actor);
        ErpReceiptLog saved = saveLog(voucher, actor);
        log.info("ERP 입고 전표 저장: {} {} dNO={} {}줄 {}원", voucher.ilTable, voucher.dDate, voucher.dNo,
                voucher.lines.size(), voucher.totalAmount);
        return result(saved, false);
    }

    /** 취소: BIGO2 태그가 붙은 우리 전표만 지운다. 남의 줄은 절대 건드리지 않는다. */
    public Map<String, Object> cancelVoucher(Long logId, String actor) {
        if (!writeEnabled) {
            throw new IllegalStateException("읽기 전용 모드입니다.");
        }
        ErpReceiptLog logRow = receiptLogRepository.findById(logId)
                .orElseThrow(() -> new IllegalArgumentException("입고 이력을 찾을 수 없습니다: " + logId));
        if (!"CREATED".equals(logRow.getStatus())) {
            throw new IllegalStateException("이미 취소된 전표입니다.");
        }
        checkDateWindow(LocalDate.parse("20" + logRow.getErpDate().replace('.', '-')));

        List<Map<String, Object>> lines = logRow.getLines().stream()
                .map(l -> Map.<String, Object>of("itemCode", l.getItemCode(), "ea", l.getEa()))
                .toList();
        writer.deleteVoucher(logRow.getRequestId(), TAG_PREFIX + logRow.getRequestId(), logRow.getIlTable(),
                logRow.getErpDate(), logRow.getVoucherNo(), logRow.getLineCount(), lines);

        logRow.setStatus("CANCELLED");
        logRow.setCancelledAt(LocalDateTime.now());
        logRow.setCancelledBy(actor);
        receiptLogRepository.save(logRow);
        return Map.of("cancelled", true, "id", logRow.getId());
    }

    private ErpReceiptLog saveLog(Voucher voucher, String actor) {
        ErpReceiptLog row = new ErpReceiptLog();
        row.setRequestId(voucher.requestId);
        row.setIlTable(voucher.ilTable);
        row.setErpDate(voucher.dDate);
        row.setVoucherNo(voucher.dNo);
        row.setVendorCode(voucher.vendorCode);
        row.setVendorName(voucher.vendorName);
        row.setTotalAmount(voucher.totalAmount);
        row.setLineCount(voucher.lines.size());
        row.setMemo(voucher.memo);
        row.setCreatedBy(actor);
        row.setCreatedAt(LocalDateTime.now());
        row.setStatus("CREATED");
        for (Map<String, Object> line : voucher.lines) {
            ErpReceiptLogLine l = new ErpReceiptLogLine();
            l.setEditNo((Integer) line.get("editNo"));
            l.setItemCode(String.valueOf(line.get("itemCode")));
            l.setItemName(String.valueOf(line.get("itemName")));
            l.setEa((Integer) line.get("ea"));
            l.setPrice((Integer) line.get("price"));
            l.setGum((Long) line.get("gum"));
            l.setVat((Long) line.get("vat"));
            row.addLine(l);
        }
        return receiptLogRepository.save(row);
    }

    /**
     * 발주서 머리에 찍을 우리 회사 정보. 경영박사 설정 테이블(sqCFGSV)에서 읽는다.
     * 같은 테이블에 라이선스/시리얼 값도 있으므로 필요한 항목만 골라서 준다.
     */
    public Map<String, Object> company() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("COMPANY", "name");
        fields.put("COMPMAN", "ceo");
        fields.put("COMPNO", "bizNo");
        fields.put("COMPJUSO", "address");
        fields.put("COMPUP", "bizType");
        fields.put("COMPJONG", "bizItem");
        fields.put("COMPTEL", "tel");
        fields.put("COMPFAX", "fax");
        Map<String, Object> out = new LinkedHashMap<>();
        fields.values().forEach(k -> out.put(k, ""));
        for (Map<String, Object> row : erpJdbcTemplate.queryForList(
                "SELECT FIELD, DATA FROM sqCFGSV WHERE FIELD IN (" + String.join(",", java.util.Collections.nCopies(fields.size(), "?")) + ")",
                fields.keySet().toArray())) {
            String key = fields.get(String.valueOf(row.get("FIELD")).trim());
            if (key != null && row.get("DATA") != null) out.put(key, String.valueOf(row.get("DATA")).trim());
        }
        return out;
    }

    // ── 발주(KIND=13) ────────────────────────────────────────────────────────

    /** 발주 미리보기. 입고와 같은 검증/계산을 쓰고 태그만 발주용이다. */
    public Map<String, Object> previewOrder(VoucherRequest request) {
        Voucher voucher = build(request, ORDER_TAG_PREFIX);
        return Map.of(
                "ilTable", voucher.ilTable,
                "dDate", voucher.dDate,
                "dNo", voucher.dNo,
                "vendorCode", voucher.vendorCode,
                "vendorName", voucher.vendorName,
                "totalAmount", voucher.totalAmount,
                "totalVat", voucher.totalVat,
                "tag", voucher.tag,
                "stockMode", "NONE", // 발주는 재고를 움직이지 않는다
                "lines", voucher.lines);
    }

    /**
     * 발주 전표 기록(ERP 거래원장 KIND=13). 입고 입력과 같은 형태로 매입처 하나에 전표 하나다.
     * 같은 품목을 다른 회사에도 시키려면 매입처를 바꿔 전표를 하나 더 만든다.
     * 발주는 재고를 바꾸지 않는다 - 물건이 와서 KIND=4 로 입고 처리될 때 반영된다.
     */
    public Map<String, Object> createOrder(VoucherRequest request, String actor) {
        if (!writeEnabled) {
            throw new IllegalStateException("읽기 전용 모드입니다. 관리자에게 문의하세요. (erp.receiving.write-enabled)");
        }
        Voucher voucher = build(request, ORDER_TAG_PREFIX);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requestId", voucher.requestId);
        out.put("ilTable", voucher.ilTable);
        out.put("dDate", voucher.dDate);
        out.put("vendorName", voucher.vendorName);
        out.put("lineCount", voucher.lines.size());
        out.put("totalAmount", voucher.totalAmount);

        // 멱등: 같은 요청을 다시 보내면 이미 저장된 전표를 돌려준다.
        List<Map<String, Object>> existing = erpJdbcTemplate.queryForList(
                "SELECT dNO FROM KIOSK_RECEIPT_VOUCHER WHERE REQUEST_ID = ? AND CANCELLED_AT IS NULL", voucher.requestId);
        if (!existing.isEmpty()) {
            out.put("dNo", existing.get(0).get("dNO"));
            out.put("duplicate", true);
            return out;
        }
        int dNo = writer.insertVoucher(ErpReceivingWriter.KIND_ORDER, voucher.requestId, voucher.tag,
                voucher.ilTable, voucher.dDate, voucher.vendorCode, voucher.memo, voucher.lines, actor);
        log.info("ERP 발주 전표 저장: {} {} dNO={} 거래처={} {}줄 {}원", voucher.ilTable, voucher.dDate, dNo,
                voucher.vendorCode, voucher.lines.size(), voucher.totalAmount);
        out.put("dNo", dNo);
        out.put("duplicate", false);
        return out;
    }

    /** 발주 전표 취소. KIOSK_RECEIPT_VOUCHER 에 남은 우리 전표만, BIGO2 태그가 맞는 줄만 지운다. */
    public Map<String, Object> cancelOrder(String requestId) {
        if (!writeEnabled) {
            throw new IllegalStateException("읽기 전용 모드입니다.");
        }
        List<Map<String, Object>> rows = erpJdbcTemplate.queryForList(
                "SELECT IL_TABLE, dDATE, dNO, LINES, CANCELLED_AT FROM KIOSK_RECEIPT_VOUCHER WHERE REQUEST_ID = ?",
                requestId);
        if (rows.isEmpty()) throw new IllegalArgumentException("발주 이력을 찾을 수 없습니다: " + requestId);
        Map<String, Object> row = rows.get(0);
        if (row.get("CANCELLED_AT") != null) throw new IllegalStateException("이미 취소된 전표입니다.");
        String ilTable = String.valueOf(row.get("IL_TABLE"));
        if (!ilTable.matches("IL\\d{2}")) throw new IllegalStateException("전표 테이블이 올바르지 않습니다.");
        String dDate = String.valueOf(row.get("dDATE"));
        checkDateWindow(LocalDate.parse("20" + dDate.replace('.', '-')));
        writer.deleteVoucher(requestId, ORDER_TAG_PREFIX + requestId, ilTable, dDate,
                ((Number) row.get("dNO")).intValue(), ((Number) row.get("LINES")).intValue(), List.of());
        return Map.of("cancelled", true, "requestId", requestId);
    }

    // ── 조립/검증 ───────────────────────────────────────────────────────────

    /** 요청을 검증해 실제 INSERT 될 값까지 계산한다. preview 와 저장이 같은 계산을 쓴다. */
    private Voucher build(VoucherRequest request) {
        return build(request, TAG_PREFIX);
    }

    private Voucher build(VoucherRequest request, String tagPrefix) {
        if (request == null || request.lines() == null || request.lines().isEmpty()) {
            throw new IllegalArgumentException("입고할 품목이 없습니다.");
        }
        if (request.clientRequestId() == null || request.clientRequestId().isBlank()) {
            throw new IllegalArgumentException("요청 식별자가 없습니다.");
        }

        LocalDate date = request.date() == null || request.date().isBlank()
                ? LocalDate.now()
                : LocalDate.parse(request.date());
        checkDateWindow(date);

        Voucher v = new Voucher();
        v.requestId = request.clientRequestId().trim();
        v.tag = tagPrefix + v.requestId;
        v.dDate = date.format(ERP_DATE);
        v.ilTable = ilTableFor(date);
        v.memo = request.memo() == null ? "" : request.memo().trim();

        v.vendorCode = String.valueOf(requireVendor(request.vendorCode()));
        v.vendorName = vendorName(request.vendorCode());

        List<Map<String, Object>> lines = new ArrayList<>();
        long total = 0;
        long totalVat = 0;
        int editNo = 0;
        for (VoucherLine line : request.lines()) {
            Integer itemCode = line.itemCode();
            if (itemCode == null) {
                throw new IllegalArgumentException("품목 코드가 없습니다.");
            }
            Map<String, Object> item = findItem(itemCode);
            int ea = line.ea() == null ? 0 : line.ea();
            if (ea <= 0) {
                throw new IllegalArgumentException("수량은 1 이상이어야 합니다: " + item.get("ITEM"));
            }
            int price = line.price() == null ? 0 : line.price();
            if (price < 0) {
                throw new IllegalArgumentException("단가가 올바르지 않습니다: " + item.get("ITEM"));
            }
            long gum = (long) price * ea;
            long vat = gum / 10;
            total += gum;
            totalVat += vat;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("editNo", ++editNo);
            row.put("itemCode", itemCode);
            row.put("itemName", String.valueOf(item.get("ITEM")).trim());
            row.put("gyu", String.valueOf(item.getOrDefault("GYU", "")).trim());
            row.put("danwi", String.valueOf(item.getOrDefault("DANWI", "")).trim());
            row.put("jego", item.get("JEGO"));
            row.put("ea", ea);
            row.put("price", price);
            row.put("gum", gum);
            row.put("vat", vat);
            // 적요는 줄마다 다를 수 있다(경영박사도 줄 단위로 적는다). 비면 전표 메모를 쓴다.
            String remark = line.remark() == null || line.remark().isBlank()
                    ? (request.memo() == null ? "" : request.memo().trim())
                    : line.remark().trim();
            row.put("remark", remark);
            if (ORDER_TAG_PREFIX.equals(tagPrefix)) {
                // 경영박사의 발주(KIND=13) 줄은 BIGO 에 단위(개/박스/마대…)를 적는다. 적요는 BIGO3 로 보낸다.
                String unit = line.unit() == null || line.unit().isBlank()
                        ? String.valueOf(item.getOrDefault("DANWI", "")).trim() : line.unit().trim();
                if (unit.length() > 20) {
                    throw new IllegalArgumentException("단위는 20자 이내로 입력하세요: " + item.get("ITEM"));
                }
                row.put("danwi", unit);
                row.put("bigo", unit);
                row.put("bigo3", remark);
            }
            lines.add(row);
        }
        v.lines = lines;
        v.totalAmount = total;
        v.totalVat = totalVat;
        v.dNo = previewDno(v.ilTable, v.dDate); // 저장 시에는 ERP 트랜잭션 안에서 다시 채번한다
        return v;
    }

    private void checkDateWindow(LocalDate date) {
        LocalDate today = LocalDate.now();
        if (date.isAfter(today.plusDays(dateWindowDays)) || date.isBefore(today.minusDays(dateWindowDays))) {
            throw new IllegalArgumentException(
                    "입고일자는 오늘 기준 " + dateWindowDays + "일 이내만 가능합니다. (경영박사 월마감 보호)");
        }
    }

    /** 미리보기용 예상 전표번호. 실제 번호는 저장 트랜잭션 안에서 다시 채번된다. */
    private int previewDno(String ilTable, String dDate) {
        Integer max = erpJdbcTemplate.queryForObject(
                "SELECT ISNULL(MAX(dNO),0) FROM " + ilTable + " WHERE dDATE = ?", Integer.class, dDate);
        return (max == null ? 0 : max) + 1;
    }

    private Map<String, Object> findItem(int itemCode) {
        List<Map<String, Object>> rows = erpJdbcTemplate.queryForList(
                "SELECT CODE, ITEM, GYU, ISNULL(JEGO,0) AS JEGO, LTRIM(RTRIM(ISNULL(DANWI,''))) AS DANWI"
                        + " FROM ITEM WHERE CODE = ?", itemCode);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("ERP 에 없는 품목입니다: " + itemCode);
        }
        return rows.get(0);
    }

    private int requireVendor(Integer vendorCode) {
        if (vendorCode == null) {
            throw new IllegalArgumentException("매입처를 선택하세요.");
        }
        Integer found = erpJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM GURAE WHERE CODE = ?", Integer.class, vendorCode);
        if (found == null || found == 0) {
            throw new IllegalArgumentException("ERP 에 없는 거래처입니다: " + vendorCode);
        }
        return vendorCode;
    }

    private String vendorName(Integer vendorCode) {
        try {
            return erpJdbcTemplate.queryForObject(
                    "SELECT LTRIM(RTRIM(ISNULL(NAME,''))) FROM GURAE WHERE CODE = ?", String.class, vendorCode);
        } catch (Exception e) {
            return "";
        }
    }

    private String ilTableFor(LocalDate date) {
        return "IL" + date.format(ERP_YEAR);
    }

    private Integer parseIntOrNull(String s) {
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Map<String, Object> result(ErpReceiptLog row, boolean duplicate) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.getId());
        out.put("requestId", row.getRequestId());
        out.put("ilTable", row.getIlTable());
        out.put("dDate", row.getErpDate());
        out.put("dNo", row.getVoucherNo());
        out.put("lineCount", row.getLineCount());
        out.put("totalAmount", row.getTotalAmount());
        out.put("duplicate", duplicate);
        return out;
    }

    // ── 요청/내부 타입 ──────────────────────────────────────────────────────

    /** unit 은 발주 전표에서만 쓴다(직접 입력한 단위). 비면 품목의 기본 단위를 쓴다. */
    public record VoucherLine(Integer itemCode, Integer ea, Integer price, String remark, String unit) {
        public VoucherLine(Integer itemCode, Integer ea, Integer price, String remark) {
            this(itemCode, ea, price, remark, null);
        }
    }

    public record VoucherRequest(String clientRequestId, String date, Integer vendorCode, String memo,
            List<VoucherLine> lines) {}

    /** 검증까지 끝난, ERP 에 들어갈 최종 형태. */
    private static class Voucher {
        String requestId;
        String tag;
        String ilTable;
        String dDate;
        int dNo;
        String vendorCode;
        String vendorName;
        String memo;
        long totalAmount;
        long totalVat;
        List<Map<String, Object>> lines = List.of();
    }
}
