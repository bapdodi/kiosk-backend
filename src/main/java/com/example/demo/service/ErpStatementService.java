package com.example.demo.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import com.example.demo.entity.StatementItemAlias;
import com.example.demo.entity.StatementVendorAlias;
import com.example.demo.repository.StatementItemAliasRepository;
import com.example.demo.repository.StatementVendorAliasRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 거래명세서(PDF/사진)를 읽어 입고 화면 격자에 채울 초안을 만든다.
 *
 * 글자 읽기는 별도 OCR 서비스(statement-ocr/)가 하고, 여기서는 ERP 와 맞추는 일만 한다.
 * - 거래처: 사업자번호(GURAE.SAUP, 매입처 172곳 중 6곳만 있음) 다음에 머리글에 상호가 들어 있는지로 맞춘다.
 * - 품목: 명세서 품명은 ERP 품명과 전혀 다르다. 그 거래처에서 전에 사 온 품목(KIND=4/13)을 후보로 두고
 *   단가가 같은지, 품명/규격의 숫자와 영문이 겹치는지로 점수를 매긴다.
 * 초안은 사람이 고쳐서 기존 미리보기 → 저장 흐름을 탄다. 여기서 ERP 에 쓰지 않는다.
 *
 * 학습: 사람이 저장한 거래처/품명 짝은 learn() 이 별칭 테이블에 쌓고(맞으면 hits+1, 고친 제안은 hits-1),
 * 다음 명세서는 별칭을 이력 점수보다 먼저 믿는다. 별칭 품명은 OCR 잡음 때문에 토큰 집합 유사도로 찾는다.
 *
 * shortcut: 상호 매칭은 부분 문자열이고 안 맞으면 단가가 겹치는 거래처로 추정한다.
 */
@Service
@Slf4j
public class ErpStatementService {

    /** 이 점수 이상이고 2등과 차이가 나면 품목을 미리 골라 둔다. 단가 일치(10)만으로는 부족하다. */
    static final int CONFIDENT_SCORE = 16;
    static final int CONFIDENT_GAP = 3;
    /** 자동 선택은 아니지만 첫 후보를 "추천"으로 강조하는 점수. 이 구간은 평가에서 97% 맞았다. */
    static final int LIKELY_SCORE = 13;
    private static final int PRICE_HIT = 10;
    /** 단가가 정확히 같진 않지만 25% 이내로 달라졌으면 같은 품목의 가격 변동일 수 있다. */
    private static final int PRICE_NEAR = 4;
    private static final double PRICE_NEAR_RATIO = 0.25;
    private static final double CONVERT_TOLERANCE = 0.03;
    private static final double[] BASE_FACTORS = {1, 2, 3, 4, 5, 6, 8, 10, 12, 20, 24, 25, 30, 50, 100};
    /** 별칭이 맞으면 이력 점수(최대 수십 점)를 압도하도록 크게 준다. 횟수가 많은 별칭이 앞서도록 hits 를 더한다. */
    static final int ALIAS_SCORE = 100;
    static final double ALIAS_SIMILARITY = 0.75;
    private static final int MAX_LEARN_PAIRS = 500;
    private static final Pattern TOKEN = Pattern.compile("[a-z]{2,}|[가-힣]{2,}|[0-9]+");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern WORDS = Pattern.compile("[A-Za-z]{2,}|[가-힣]{2,}");

    private final JdbcTemplate erp;
    private final ErpReceivingService receiving;
    private final StatementItemAliasRepository itemAliases;
    private final StatementVendorAliasRepository vendorAliases;
    private final String ocrUrl;
    private final int ocrTimeoutSeconds;

    public ErpStatementService(@Qualifier("erpJdbcTemplate") JdbcTemplate erp, ErpReceivingService receiving,
            StatementItemAliasRepository itemAliases, StatementVendorAliasRepository vendorAliases,
            @Value("${erp.receiving.statement-ocr-url:}") String ocrUrl,
            @Value("${erp.receiving.statement-ocr-timeout-seconds:600}") int ocrTimeoutSeconds) {
        this.erp = erp;
        this.receiving = receiving;
        this.itemAliases = itemAliases;
        this.vendorAliases = vendorAliases;
        this.ocrUrl = ocrUrl;
        this.ocrTimeoutSeconds = ocrTimeoutSeconds;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> parse(MultipartFile file) throws Exception {
        if (ocrUrl == null || ocrUrl.isBlank()) {
            throw new IllegalStateException("명세서 읽기 서비스가 설정되지 않았습니다. (erp.receiving.statement-ocr-url)");
        }
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("파일을 선택하세요.");

        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        String name = file.getOriginalFilename() == null ? "statement" : file.getOriginalFilename();
        parts.add("file", new ByteArrayResource(file.getBytes()) {
            @Override public String getFilename() { return name; }
        });
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(ocrTimeoutSeconds));
        Map<String, Object> ocr;
        try {
            ocr = RestClient.builder().requestFactory(factory).build().post().uri(ocrUrl + "/parse")
                    .contentType(MediaType.MULTIPART_FORM_DATA).body(parts).retrieve().body(Map.class);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            throw new IllegalArgumentException("명세서를 읽지 못했습니다: " + e.getResponseBodyAsString());
        } catch (org.springframework.web.client.RestClientException e) {
            throw new IllegalStateException("명세서 읽기 서비스에 연결하지 못했습니다: " + e.getMessage());
        }
        List<Map<String, Object>> pages = (List<Map<String, Object>>) ocr.get("pages");

        List<Map<String, String>> vendors = new ArrayList<>();
        for (Map<String, Object> v : erp.queryForList(
                "SELECT CODE, LTRIM(RTRIM(NAME)) AS NAME, REPLACE(LTRIM(RTRIM(ISNULL(SAUP,''))),'-','') AS SAUP"
                        + " FROM GURAE WHERE DANGA = 1")) {
            vendors.add(Map.of("code", String.valueOf(v.get("CODE")), "name", String.valueOf(v.get("NAME")),
                    "saup", String.valueOf(v.get("SAUP"))));
        }

        List<Map<String, Object>> drafts = new ArrayList<>();
        Map<String, Object> current = null;
        String currentKey = null;
        for (Map<String, Object> page : pages) {
            List<String> bizNos = (List<String>) page.get("bizNos");
            Map<String, String> vendor = vendorByAlias(vendors, bizNos);
            if (vendor == null) vendor = matchVendor(vendors, bizNos, (String) page.get("header"));
            String date = (String) page.get("date");
            String key = vendor != null ? vendor.get("code") : (bizNos.isEmpty() ? "?" : bizNos.get(0));
            // 같은 거래처가 이어지고 날짜가 어긋나지 않으면 한 명세서의 여러 쪽이다(1/3, 2/3 ...).
            // 날짜를 못 읽은 쪽은 이웃 쪽과 같은 날짜로 본다.
            boolean sameStatement = current != null && key.equals(currentKey)
                    && (date == null || current.get("date") == null || date.equals(current.get("date")));
            // 쪽 번호((1/3) 등)를 읽었으면 그게 우선이다: 1쪽은 항상 새 명세서, 앞 쪽의 다음 번호면 같은 명세서.
            // 날짜를 못 읽은 쪽이 앞 명세서에 딸려 들어가는 것을 막는다.
            int[] part = pageOf((String) page.get("header"));
            if (part != null && part[0] == 1) sameStatement = false;
            else if (part != null && current != null && key.equals(currentKey)
                    && current.get("lastPart") instanceof int[] last && last[1] == part[1]) sameStatement = last[0] + 1 == part[0];
            if (!sameStatement) current = null;
            if (current == null) {
                current = newDraft(vendor, date, bizNos);
                currentKey = key;
                drafts.add(current);
            } else if (current.get("date") == null) {
                current.put("date", date);
            }
            addPage(current, page);
            current.put("lastPart", part);
        }
        Map<String, java.util.Set<Long>> prices = null;
        for (Map<String, Object> draft : drafts) {
            if (draft.get("vendorCode") == null) {
                // 상호도 사업자번호도 안 맞으면(명세서 "JK건축자재" = ERP "제이케이건축자재") 단가가 겹치는 거래처로 추정한다.
                if (prices == null) prices = pricesByVendor();
                List<Long> rowPrices = new ArrayList<>();
                for (Map<String, Object> l : (List<Map<String, Object>>) draft.get("lines")) rowPrices.add(((Number) l.get("price")).longValue());
                String code = vendorByPrices(prices, rowPrices);
                for (Map<String, String> v : vendors) {
                    if (v.get("code").equals(code)) {
                        draft.put("vendorCode", Integer.valueOf(code));
                        draft.put("vendorName", v.get("name"));
                    }
                }
            }
            draft.remove("lastPart");
            matchItems(draft);
            draft.put("totalMatched", totalMatched((List<Map<String, Object>>) draft.get("lines"),
                    (Long) draft.get("sumAmount"), (java.util.Set<Long>) draft.remove("numbers")));
        }
        return Map.of("pageCount", pages.size(), "drafts", drafts);
    }

    /** 거래처별로 올해와 작년의 매입/발주에서 쓴 단가 집합. 상호로 거래처를 못 찾았을 때만 읽는다. */
    private Map<String, java.util.Set<Long>> pricesByVendor() {
        Map<String, java.util.Set<Long>> out = new java.util.HashMap<>();
        int thisYear = LocalDate.now().getYear();
        for (int year = thisYear - 1; year <= thisYear; year++) {
            String table = "IL" + String.format("%02d", year % 100);
            Integer exists = erp.queryForObject("SELECT COUNT(*) FROM sys.tables WHERE name = ?", Integer.class, table);
            if (exists == null || exists == 0) continue;
            erp.query("SELECT DISTINCT CUST, CAST(PRICE AS bigint) AS price FROM " + table
                    + " WHERE KIND IN (4, 13) AND PRICE >= 100",
                    rs -> { out.computeIfAbsent(String.valueOf(rs.getInt("CUST")), k -> new java.util.HashSet<>()).add(rs.getLong("price")); });
        }
        return out;
    }

    /** 명세서 줄 단가가 가장 많이 겹치는 거래처. 3줄 이상 겹치고 2등의 두 배 이상일 때만 믿는다. */
    static String vendorByPrices(Map<String, java.util.Set<Long>> pricesByVendor, List<Long> rowPrices) {
        String best = null;
        int bestHits = 0, secondHits = 0;
        for (Map.Entry<String, java.util.Set<Long>> e : pricesByVendor.entrySet()) {
            int hits = (int) rowPrices.stream().filter(e.getValue()::contains).count();
            if (hits > bestHits) { secondHits = bestHits; bestHits = hits; best = e.getKey(); }
            else if (hits > secondHits) secondHits = hits;
        }
        return bestHits >= 3 && bestHits >= 2 * secondHits ? best : null;
    }

    private static final Pattern PART = Pattern.compile("\\(\\s*(\\d+)\\s*/\\s*(\\d+)\\s*\\)|PAGE\\s*:?\\s*0*(\\d+)\\s*/\\s*0*(\\d+)");

    /** 머리글에서 쪽 번호 "(2/3)" 또는 "PAGE:001/001" 을 {쪽, 전체} 로. 못 읽으면 null. */
    static int[] pageOf(String header) {
        Matcher m = PART.matcher(header == null ? "" : header);
        if (!m.find()) return null;
        int n = Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(3));
        int total = Integer.parseInt(m.group(2) != null ? m.group(2) : m.group(4));
        return n >= 1 && total >= n && total <= 50 ? new int[] {n, total} : null;
    }

    private Map<String, Object> newDraft(Map<String, String> vendor, String date, List<String> bizNos) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("date", date);
        d.put("vendorCode", vendor == null ? null : Integer.valueOf(vendor.get("code")));
        d.put("vendorName", vendor == null ? "" : vendor.get("name"));
        d.put("bizNo", bizNos.isEmpty() ? "" : bizNos.get(0));
        d.put("pageCount", 0);
        d.put("lines", new ArrayList<Map<String, Object>>());
        d.put("sumAmount", 0L);
        d.put("numbers", new java.util.HashSet<Long>());  // 쪽에 찍힌 숫자. 합계 대조 뒤 지운다
        return d;
    }

    @SuppressWarnings("unchecked")
    private void addPage(Map<String, Object> draft, Map<String, Object> page) {
        draft.put("pageCount", (Integer) draft.get("pageCount") + 1);
        ((List<Map<String, Object>>) draft.get("lines")).addAll((List<Map<String, Object>>) page.get("rows"));
        draft.put("sumAmount", (Long) draft.get("sumAmount") + ((Number) page.get("sumAmount")).longValue());
        for (Object n : (List<?>) page.get("numbers")) ((java.util.Set<Long>) draft.get("numbers")).add(((Number) n).longValue());
    }

    /**
     * 읽은 줄의 합이 명세서에 찍힌 숫자(공급가액, 또는 부가세를 더한 합계)와 같은가. 합계가 첫 쪽에만 찍히는 양식이 있어
     * 쪽을 합친 뒤에 본다. 같은 숫자가 없으면 빠진 줄이 있을 수 있다. 부가세는 줄마다 버림이라 1원 어긋날 수 있다.
     */
    @SuppressWarnings("unchecked")
    static boolean totalMatched(List<Map<String, Object>> lines, long sum, java.util.Set<Long> numbers) {
        if (lines.isEmpty()) return false;
        boolean allVat = lines.stream().allMatch(l -> l.get("vat") != null);
        long vat = allVat ? lines.stream().mapToLong(l -> ((Number) l.get("vat")).longValue()).sum() : 0;
        for (long n : numbers) {
            if (Math.abs(n - sum) <= 1 || Math.abs(n - (sum + sum / 10)) <= 1 || (allVat && Math.abs(n - (sum + vat)) <= 1)) return true;
        }
        return false;
    }

    /** 사업자번호가 맞는 거래처가 있으면 그것, 없으면 머리글에 상호가 들어 있는 거래처 중 가장 긴 이름. */
    static Map<String, String> matchVendor(List<Map<String, String>> vendors, List<String> bizNos, String header) {
        for (String biz : bizNos == null ? List.<String>of() : bizNos) {
            String digits = biz.replace("-", "");
            for (Map<String, String> v : vendors) if (!v.get("saup").isEmpty() && v.get("saup").equals(digits)) return v;
        }
        String text = normalize(header);
        return vendors.stream()
                .filter(v -> normalize(v.get("name")).length() >= 3 && text.contains(normalize(v.get("name"))))
                .max(Comparator.comparingInt(v -> normalize(v.get("name")).length()))
                .orElse(null);
    }

    static String normalize(String s) {
        if (s == null) return "";
        return s.replaceAll("\\(주\\)|㈜|주식회사|\\(유\\)|\\(합\\)", "").replaceAll("[\\s()\\[\\].,·\\-_/]", "").toLowerCase();
    }

    /** 이 거래처에서 전에 사 온 품목(올해와 작년의 매입/발주)과 그때 단가. 단가가 바뀐 적이 있으면 단가별로 한 줄씩. */
    private List<Map<String, Object>> history(int vendorCode) {
        List<Map<String, Object>> out = new ArrayList<>();
        int thisYear = LocalDate.now().getYear();
        for (int year = thisYear - 1; year <= thisYear; year++) {
            String table = "IL" + String.format("%02d", year % 100);
            Integer exists = erp.queryForObject("SELECT COUNT(*) FROM sys.tables WHERE name = ?", Integer.class, table);
            if (exists == null || exists == 0) continue;
            out.addAll(erp.queryForList(
                    "SELECT l.ITEMCODE AS code, LTRIM(RTRIM(i.ITEM)) AS name, LTRIM(RTRIM(ISNULL(i.GYU,''))) AS gyu,"
                            + " LTRIM(RTRIM(ISNULL(i.DANWI,''))) AS danwi, ISNULL(i.JEGO,0) AS jego, l.PRICE AS price,"
                            + " MAX(l.dDATE) AS last"
                            + " FROM " + table + " l JOIN ITEM i ON i.CODE = l.ITEMCODE"
                            + " WHERE l.CUST = ? AND l.KIND IN (4, 13) AND l.ITEMCODE >= 100"
                            + " GROUP BY l.ITEMCODE, i.ITEM, i.GYU, i.DANWI, i.JEGO, l.PRICE", vendorCode));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private void matchItems(Map<String, Object> draft) {
        List<Map<String, Object>> lines = (List<Map<String, Object>>) draft.get("lines");
        Integer vendorCode = (Integer) draft.get("vendorCode");
        List<Map<String, Object>> hist = vendorCode == null ? List.of() : history(vendorCode);
        List<StatementItemAlias> aliases = vendorCode == null ? List.of() : itemAliases.findByVendorCode(vendorCode);
        Map<Integer, Map<String, Object>> aliasItems = aliasItemInfo(aliases);
        // 품목별로 가장 최근에 산 단가(환산 계수 추정에 쓴다).
        Map<Object, String> lastDate = new HashMap<>();
        Map<Object, Double> latestPrice = new HashMap<>();
        for (Map<String, Object> h : hist) {
            String d = String.valueOf(h.get("last"));
            if (lastDate.get(h.get("code")) == null || d.compareTo(lastDate.get(h.get("code"))) > 0) {
                lastDate.put(h.get("code"), d);
                latestPrice.put(h.get("code"), ((Number) h.get("price")).doubleValue());
            }
        }
        for (Map<String, Object> line : lines) {
            String name = String.valueOf(line.get("name"));
            double price = ((Number) line.get("price")).doubleValue();
            // 같은 품목이 단가별로 여러 줄이면 점수가 가장 높은 줄 하나만 후보로 남긴다.
            Map<Object, Object[]> best = new LinkedHashMap<>();
            for (Map<String, Object> h : hist) {
                int score = score(name, price, String.valueOf(h.get("name")) + " " + h.get("gyu"),
                        ((Number) h.get("price")).doubleValue());
                if (score <= 0) continue;
                Object[] cur = best.get(h.get("code"));
                if (cur == null || (Integer) cur[0] < score) best.put(h.get("code"), new Object[] {score, h});
            }
            // 사람이 전에 확정한 별칭이면 이력 점수 위에 크게 얹는다(이력에 없는 품목이면 새로 후보로 올린다).
            for (Map.Entry<Integer, Integer> a : aliasScores(aliases, name).entrySet()) {
                Object[] cur = best.get(a.getKey());
                if (cur != null) best.put(a.getKey(), new Object[] {(Integer) cur[0] + a.getValue(), cur[1]});
                else if (aliasItems.containsKey(a.getKey())) best.put(a.getKey(), new Object[] {a.getValue(), aliasItems.get(a.getKey())});
            }
            List<Object[]> ranked = new ArrayList<>(best.values());
            ranked.sort((a, b) -> (Integer) b[0] - (Integer) a[0]);
            List<Map<String, Object>> candidates = new ArrayList<>();
            for (Object[] r : ranked.subList(0, Math.min(5, ranked.size()))) {
                Map<String, Object> h = (Map<String, Object>) r[1];
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("code", h.get("code")); c.put("name", h.get("name")); c.put("gyu", h.get("gyu"));
                c.put("danwi", h.get("danwi")); c.put("jego", h.get("jego")); c.put("score", r[0]);
                convertFor(c, line, latestPrice.get(h.get("code")));
                candidates.add(c);
            }
            line.put("candidates", candidates);
            int top = ranked.isEmpty() ? 0 : (Integer) ranked.get(0)[0];
            boolean gapOk = ranked.size() == 1 || (!ranked.isEmpty() && top - (Integer) ranked.get(1)[0] >= CONFIDENT_GAP);
            boolean confident = !ranked.isEmpty() && top >= CONFIDENT_SCORE && gapOk;
            line.put("itemCode", confident ? candidates.get(0).get("code") : null);
            if (!ranked.isEmpty() && !confident && top >= LIKELY_SCORE && gapOk) candidates.get(0).put("likely", true);
            if (confident && candidates.get(0).get("converted") != null) {
                // 지난 거래 단가에 맞춰 수량·단가를 환산해 채우되, 명세서 원래 값은 남긴다.
                line.put("rawEa", line.get("ea")); line.put("rawPrice", line.get("price"));
                line.put("ea", candidates.get(0).get("ea")); line.put("price", candidates.get(0).get("price"));
                line.put("converted", true);
            }
        }
    }

    /**
     * 명세서 (수량, 단가)와 ERP 의 그 품목 지난 단가로 환산 계수 f 를 추정한다(ERP 수량 = 수량×f, ERP 단가 = 단가÷f).
     * 단가가 이미 3% 안에서 같으면 환산하지 않는다(1). 못 정하면 null. ERP 가 같은 금액을 다른 수량×단가로 입력한 줄이
     * 전체의 6% 였고, 이 방식은 평가에서 환산을 적용한 22줄 중 21줄이 맞았다(틀린 1줄은 하단 문구 줄).
     */
    static Double conversionFactor(double qty, double price, double histPrice) {
        if (histPrice <= 0 || qty <= 0 || price <= 0) return null;
        Double best = null;
        double bestErr = Double.MAX_VALUE;
        for (double base : BASE_FACTORS) {
            for (double f : new double[] {base, 1 / base}) {
                double err = Math.abs(price / f - histPrice) / histPrice;
                if (err > CONVERT_TOLERANCE || Math.abs(qty * f - Math.rint(qty * f)) > 1e-6) continue;
                if (f == 1.0) return 1.0;
                if (err < bestErr) { best = f; bestErr = err; }
            }
        }
        return best;
    }

    /** 후보 하나에 환산 결과(ea, price, converted)를 붙인다. 금액이 새 수량으로 나누어떨어질 때만(ERP 단가는 정수). */
    private static void convertFor(Map<String, Object> candidate, Map<String, Object> line, Double histPrice) {
        if (histPrice == null) return;
        double qty = ((Number) line.get("ea")).doubleValue(), price = ((Number) line.get("price")).doubleValue();
        Double f = conversionFactor(qty, price, histPrice);
        if (f == null || f == 1.0) return;
        long ea = Math.round(qty * f), amount = ((Number) line.get("amount")).longValue();
        if (ea < 1 || amount % ea != 0) return;
        candidate.put("ea", ea); candidate.put("price", amount / ea); candidate.put("converted", true);
    }

    // ── 학습(별칭) ──────────────────────────────────────────────────────────

    /** 품명을 별칭 키로. 영문/한글/숫자 토큰만 남겨 정렬한다(한 글자 "박" 같은 손글씨 잡음은 버려진다). */
    static String nameKey(String name) {
        return String.join(" ", tokens(name));
    }

    static Set<String> tokens(String s) {
        Set<String> out = new java.util.TreeSet<>();
        Matcher m = TOKEN.matcher(s == null ? "" : s.toLowerCase());
        while (m.find()) out.add(m.group());
        return out;
    }

    /** 토큰 집합의 자카드 유사도. 한쪽이라도 비면 0. */
    static double similarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> both = new HashSet<>(a);
        both.retainAll(b);
        return (double) both.size() / (a.size() + b.size() - both.size());
    }

    /** 이 품명과 비슷한 별칭이 가리키는 품목별 점수(ALIAS_SCORE + 확정 횟수). 같은 품목이 여럿이면 큰 쪽. */
    static Map<Integer, Integer> aliasScores(List<StatementItemAlias> aliases, String name) {
        Set<String> mine = tokens(name);
        Map<Integer, Integer> out = new HashMap<>();
        for (StatementItemAlias a : aliases) {
            if (similarity(mine, new java.util.TreeSet<>(List.of(a.getNameKey().split(" ")))) >= ALIAS_SIMILARITY) {
                out.merge(a.getItemCode(), ALIAS_SCORE + Math.min(a.getHits(), 50), Math::max);
            }
        }
        return out;
    }

    /** 별칭이 가리키는 ERP 품목 정보(후보 목록에 이름/규격을 보여 주려고). 이력에 없는 품목도 있다. */
    private Map<Integer, Map<String, Object>> aliasItemInfo(List<StatementItemAlias> aliases) {
        Set<Integer> codes = new HashSet<>();
        aliases.stream().sorted((x, y) -> y.getHits() - x.getHits()).limit(1000).forEach(a -> codes.add(a.getItemCode()));
        Map<Integer, Map<String, Object>> out = new HashMap<>();
        if (codes.isEmpty()) return out;
        String marks = String.join(",", java.util.Collections.nCopies(codes.size(), "?"));
        for (Map<String, Object> r : erp.queryForList(
                "SELECT CODE AS code, LTRIM(RTRIM(ITEM)) AS name, LTRIM(RTRIM(ISNULL(GYU,''))) AS gyu,"
                        + " LTRIM(RTRIM(ISNULL(DANWI,''))) AS danwi, ISNULL(JEGO,0) AS jego FROM ITEM WHERE CODE IN (" + marks + ")",
                codes.toArray())) {
            out.put(((Number) r.get("code")).intValue(), r);
        }
        return out;
    }

    /** 사람이 전에 확정한 사업자번호 → 거래처. 가장 많이 확정한 거래처를 쓴다. */
    private Map<String, String> vendorByAlias(List<Map<String, String>> vendors, List<String> bizNos) {
        for (String biz : bizNos == null ? List.<String>of() : bizNos) {
            for (StatementVendorAlias a : vendorAliases.findByBizNoOrderByHitsDesc(biz.replace("-", ""))) {
                for (Map<String, String> v : vendors) if (v.get("code").equals(String.valueOf(a.getVendorCode()))) return v;
            }
        }
        return null;
    }

    /** 사업자등록번호(숫자 10자리)의 검증 숫자를 확인한다. 가중치 1,3,7,1,3,7,1,3,5 의 합과 아홉째 자리의 5배 몫으로 계산한다. */
    static boolean validBizNo(String digits) {
        if (digits == null || !digits.matches("\\d{10}")) return false;
        int[] w = {1, 3, 7, 1, 3, 7, 1, 3, 5};
        int sum = 0;
        for (int i = 0; i < 9; i++) sum += (digits.charAt(i) - '0') * w[i];
        sum += (digits.charAt(8) - '0') * 5 / 10;
        return (10 - sum % 10) % 10 == digits.charAt(9) - '0';
    }

    /** 사람이 확정한 한 줄. suggested 는 화면이 처음 제안한 품목이고, 다른 품목으로 고쳤다면 그 제안을 깎는다. */
    public record LearnPair(String name, Integer itemCode, Integer suggested) {}

    public record LearnRequest(Integer vendorCode, String bizNo, List<LearnPair> pairs) {}

    /**
     * 저장한 명세서에서 배운다. 맞게 제안한 짝은 hits+1, 고쳐서 저장한 짝은 새 품목 hits+1 과 옛 제안 hits-1(0 이면 삭제).
     * 사업자번호와 거래처도 짝으로 남겨 다음에는 상호가 달라도 거래처를 바로 찾는다.
     */
    @Transactional
    public Map<String, Object> learn(LearnRequest req) {
        if (req == null || req.vendorCode() == null) throw new IllegalArgumentException("거래처가 없습니다.");
        List<LearnPair> pairs = req.pairs() == null ? List.of() : req.pairs();
        if (pairs.size() > MAX_LEARN_PAIRS) throw new IllegalArgumentException("한 번에 " + MAX_LEARN_PAIRS + "줄까지 배웁니다.");
        Integer vendor = erp.queryForObject("SELECT COUNT(*) FROM GURAE WHERE CODE = ?", Integer.class, req.vendorCode());
        if (vendor == null || vendor == 0) throw new IllegalArgumentException("ERP 에 없는 거래처입니다: " + req.vendorCode());

        List<StatementItemAlias> existing = itemAliases.findByVendorCode(req.vendorCode());
        LocalDateTime now = LocalDateTime.now();
        int learned = 0;
        for (LearnPair p : pairs) {
            String key = p == null ? "" : nameKey(p.name());
            if (p == null || p.itemCode() == null || key.isBlank() || key.length() > 200) continue;
            if (p.suggested() != null && !p.suggested().equals(p.itemCode())) {
                Set<String> mine = tokens(p.name());
                for (StatementItemAlias a : new ArrayList<>(existing)) {
                    if (!a.getItemCode().equals(p.suggested())) continue;
                    if (similarity(mine, new java.util.TreeSet<>(List.of(a.getNameKey().split(" ")))) < ALIAS_SIMILARITY) continue;
                    if (a.getHits() <= 1) { itemAliases.delete(a); existing.remove(a); }
                    else { a.setHits(a.getHits() - 1); a.setUpdatedAt(now); itemAliases.save(a); }
                }
            }
            // 같은 품목이고 이름이 충분히 비슷한 별칭이 있으면 거기에 합친다(OCR 잡음마다 변형 행이 생겨 횟수가 쪼개지지 않게).
            Set<String> mine = tokens(p.name());
            StatementItemAlias row = existing.stream()
                    .filter(a -> a.getItemCode().equals(p.itemCode()))
                    .filter(a -> a.getNameKey().equals(key)
                            || similarity(mine, new java.util.TreeSet<>(List.of(a.getNameKey().split(" ")))) >= ALIAS_SIMILARITY)
                    .max(Comparator.comparingInt(StatementItemAlias::getHits)).orElse(null);
            if (row == null) {
                row = new StatementItemAlias();
                row.setVendorCode(req.vendorCode());
                row.setNameKey(key);
                row.setItemCode(p.itemCode());
                row.setHits(0);
                existing.add(row);
            }
            row.setHits(row.getHits() + 1);
            row.setUpdatedAt(now);
            itemAliases.save(row);
            learned++;
        }
        String biz = req.bizNo() == null ? "" : req.bizNo().replace("-", "").trim();
        // 검증 숫자가 안 맞는 번호는 OCR 오독이라 거래처와 짝으로 남기지 않는다.
        if (validBizNo(biz)) {
            StatementVendorAlias v = vendorAliases.findByBizNoAndVendorCode(biz, req.vendorCode()).orElseGet(() -> {
                StatementVendorAlias n = new StatementVendorAlias();
                n.setBizNo(biz); n.setVendorCode(req.vendorCode()); n.setHits(0);
                return n;
            });
            v.setHits(v.getHits() + 1);
            v.setUpdatedAt(now);
            vendorAliases.save(v);
        }
        return Map.of("learned", learned);
    }

    /**
     * 명세서 한 줄과 ERP 품목 하나가 얼마나 닮았는지. 단가가 같으면 +10, 25% 이내로 달라졌으면 +4, 규격 숫자 +2씩,
     * 같은 단어 +3씩, 이름 글자쌍 유사도 최대 +12(스리브/슬리브, "DRF LT"/"디엘티 (DRF LT)" 같은 표기 차이를 흡수한다).
     */
    static int score(String statementName, double statementPrice, String erpText, double erpPrice) {
        double diff = Math.abs(statementPrice - erpPrice);
        int score = diff <= 1 ? PRICE_HIT : (erpPrice > 0 && diff / erpPrice <= PRICE_NEAR_RATIO ? PRICE_NEAR : 0);
        Set<String> a = numbers(statementName), b = numbers(erpText);
        for (String n : a) if (b.contains(n)) score += 2;
        Set<String> wa = words(statementName), wb = words(erpText);
        for (String w : wa) if (wb.contains(w)) score += 3;
        Set<String> ga = grams(statementName), gb = grams(erpText);
        if (!ga.isEmpty() && !gb.isEmpty()) {
            Set<String> both = new HashSet<>(ga);
            both.retainAll(gb);
            score += (int) Math.round(12.0 * both.size() / (ga.size() + gb.size() - both.size()));
        }
        return score;
    }

    /** 영문/숫자/한글만 남긴 글자 두 개씩의 집합. */
    private static Set<String> grams(String s) {
        String t = s.toLowerCase().replaceAll("[^a-z0-9가-힣]", "");
        Set<String> out = new HashSet<>();
        for (int i = 0; i + 1 < t.length(); i++) out.add(t.substring(i, i + 2));
        return out;
    }

    private static Set<String> numbers(String s) {
        Set<String> out = new TreeSet<>();
        Matcher m = DIGITS.matcher(s);
        while (m.find()) out.add(m.group());
        return out;
    }

    private static Set<String> words(String s) {
        Set<String> out = new TreeSet<>();
        Matcher m = WORDS.matcher(s);
        while (m.find()) out.add(m.group().toLowerCase());
        return out;
    }
}
