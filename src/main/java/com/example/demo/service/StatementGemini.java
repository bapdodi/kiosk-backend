package com.example.demo.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.extern.slf4j.Slf4j;

/**
 * 거래명세서를 Gemini 로 읽고(read) 품목을 고른다(match). 관리자 도우미와 같은 키를 쓴다.
 *
 * 평가(ERP 정답 대조, 프롬프트가 본 적 없는 41건 290줄): 기존 OCR+점수 자동선택 정답 139/오답 1 → Gemini 267/3.
 * OCR 이 못 읽던 줄(도장·손글씨 겹침, 한글 오독)도 읽고, 명세서 품명과 ERP 품명의 표기 차이를 이해한다.
 * 키가 없거나 호출이 실패하면 ErpStatementService 가 기존 OCR 서비스와 점수 규칙으로 넘어간다.
 */
@Component
@Slf4j
public class StatementGemini {

    static final String READ_PROMPT = """
            첨부 파일은 한국 배관자재 도매상이 받은 거래명세서(공급받는자용) 스캔이다. 쪽마다 아래를 읽어 JSON 으로만 답하라.
            {"pages":[{"page":1부터 쪽 순서,"date":"YYYY-MM-DD" 거래일자(팩스 출력 시각 아님) 또는 null,"supplierName":공급자 상호,"bizNo":공급자 사업자번호 "000-00-00000" 또는 "","part":"(n/m)" 처럼 이 명세서 안의 쪽 번호가 찍혀 있으면 그대로 아니면 "","rows":[{"name":품명과 규격을 인쇄된 그대로,"unit":단위 또는 "","qty":수량,"price":단가,"amount":공급가액,"vat":세액 또는 null}],"totals":[이 쪽에 인쇄된 합계/소계/공급가액 합계/세액 합계/총합계 숫자들]}]}
            규칙:
            - 공급받는자(우리) 칸이 아니라 공급자 칸의 상호와 사업자번호다.
            - 품목 줄만 rows 에 넣는다. 합계·소계·전잔액·입금액 줄, 빈 줄, 손글씨 메모와 체크 표시(V, √, 물결)는 넣지 않는다.
            - 숫자는 쉼표 없는 정수. 인쇄된 값을 그대로 옮기고 계산해서 고치지 않는다. 반품은 음수.
            - 수량이 손글씨에 가려졌으면 공급가액÷단가로 채운다.
            """;

    static final String MATCH_PROMPT = """
            너는 한국 배관자재 도매상의 매입 담당자다. 거래명세서의 각 줄이 우리 ERP 의 어느 품목인지 고른다.
            후보는 이 거래처에서 전에 산 품목뿐이다. 명세서 품명과 ERP 품명은 표기가 다르다(예: 계량기너트 20A = 수도계량기(너트) 20, 400L = 40cm, 닛블 = 닛플, LT = 엘티, 90 ELBOW = 엘보, PLUG = 플러그, TEE/T = 티, VS = 발브소켓, 후렌지 = 플랜지, VN SDR = 파이프 VG1, 콘넥타 = 고무콘넥타). 괄호 안 숫자(T 40(70) 의 70)는 포장 수량일 때가 많다.
            판단 기준: 품목 종류와 크기(15A, 20*15, 40 등)가 맞으면 고른다. 단가는 몇 달 새 30~40% 오르내리기도 하니 단가 차이만으로 버리지 않는다. 다만 종류나 크기가 같은 후보가 여럿인데 가를 근거가 없으면 null.
            JSON 배열만 답하라: [{"line":줄 번호,"itemCode":코드 또는 null}]
            """;

    /** shortcut: 파일을 요청 본문에 그대로 싣는다(Gemini 인라인 한도 약 20MB). 넘는 파일은 OCR 로 간다. 자주 넘으면 File API 로 올린다. */
    static final long MAX_INLINE_BYTES = 15L * 1024 * 1024;

    private final ObjectMapper json;
    private final String apiKey;
    private final String model;
    private final RestClient client;

    public StatementGemini(ObjectMapper json,
            @Value("${assistant.gemini.api-key:}") String apiKey,
            @Value("${assistant.gemini.model:gemini-3.8-flash}") String model,
            @Value("${erp.receiving.statement-ocr-timeout-seconds:600}") int timeoutSeconds) {
        this.json = json;
        this.apiKey = apiKey;
        this.model = model;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.client = RestClient.builder().requestFactory(factory).baseUrl("https://generativelanguage.googleapis.com").build();
    }

    public boolean enabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** 파일(PDF/사진)을 읽어 OCR 서비스 /parse 의 pages 와 같은 모양으로 돌려준다. */
    public List<Map<String, Object>> read(byte[] data, String mimeType) throws Exception {
        ObjectNode file = json.createObjectNode();
        file.putObject("inline_data").put("mime_type", mimeType).put("data", Base64.getEncoder().encodeToString(data));
        JsonNode answer = generate(List.of(json.createObjectNode().put("text", READ_PROMPT), file));
        log.debug("Gemini 읽기 답: {}", answer.toString().length() > 2000 ? answer.toString().substring(0, 2000) : answer);
        return toPages(answer);
    }

    /** OCR 서비스와 같은 모양: header 에 상호와 쪽 번호를 넣어 기존 거래처 매칭(matchVendor)과 쪽 묶기(pageOf)를 그대로 쓴다. */
    static List<Map<String, Object>> toPages(JsonNode answer) {
        List<Map<String, Object>> pages = new ArrayList<>();
        for (JsonNode p : answer.path("pages")) {
            List<Map<String, Object>> rows = new ArrayList<>();
            long sum = 0;
            for (JsonNode r : p.path("rows")) {
                long price = r.path("price").asLong(), amount = r.path("amount").asLong(), qty = r.path("qty").asLong();
                if (price == 0 || amount == 0 || r.path("name").asText().isBlank()) continue;
                // 금액과 단가가 수량보다 덜 틀린다(수량 칸은 손글씨에 잘 가려진다). 나누어떨어지면 수량을 거기에 맞춘다.
                if (qty * price != amount && amount % price == 0) qty = amount / price;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", r.path("name").asText().trim());
                row.put("unit", r.path("unit").asText(""));
                row.put("ea", qty);
                row.put("price", price);
                row.put("amount", amount);
                row.put("vat", r.path("vat").isNumber() ? r.path("vat").asLong() : null);
                rows.add(row);
                sum += amount;
            }
            List<Long> numbers = new ArrayList<>();
            for (JsonNode n : p.path("totals")) if (n.canConvertToLong()) numbers.add(n.asLong());
            String bizNo = p.path("bizNo").asText("").replaceAll("[^0-9-]", "");
            Map<String, Object> page = new LinkedHashMap<>();
            page.put("date", date(p.path("date").asText("")));
            page.put("bizNos", bizNo.replace("-", "").length() == 10 ? List.of(bizNo) : List.of());
            page.put("header", (p.path("supplierName").asText("") + " " + p.path("part").asText("")).trim());
            page.put("rows", rows);
            page.put("sumAmount", sum);
            page.put("numbers", numbers);
            pages.add(page);
        }
        return pages;
    }

    private static final java.util.regex.Pattern DATE = java.util.regex.Pattern.compile("(20\\d\\d)\\D{1,3}(\\d{1,2})\\D{1,3}(\\d{1,2})");

    /** "2026-11-05", "2026.11.05", "2026년 11월 5일" -> "2026-11-05". 못 읽으면 null. */
    static String date(String text) {
        java.util.regex.Matcher m = DATE.matcher(text);
        if (!m.find()) return null;
        int mo = Integer.parseInt(m.group(2)), d = Integer.parseInt(m.group(3));
        return mo >= 1 && mo <= 12 && d >= 1 && d <= 31 ? String.format("%s-%02d-%02d", m.group(1), mo, d) : null;
    }

    /**
     * 줄 번호 → 고른 품목 코드. hist 는 ErpStatementService.history() 의 행(code, name, gyu, price).
     * 후보에 없는 코드는 버린다(지어낸 코드 방지).
     */
    public Map<Integer, Integer> match(List<Map<String, Object>> lines, List<Map<String, Object>> hist) throws Exception {
        Map<Integer, String> names = new LinkedHashMap<>();
        Map<Integer, TreeSet<Long>> prices = new HashMap<>();
        for (Map<String, Object> h : hist) {
            int code = ((Number) h.get("code")).intValue();
            names.putIfAbsent(code, (h.get("name") + " " + h.get("gyu")).trim());
            prices.computeIfAbsent(code, k -> new TreeSet<>()).add(((Number) h.get("price")).longValue());
        }
        if (names.isEmpty() || lines.isEmpty()) return Map.of();
        StringBuilder text = new StringBuilder(MATCH_PROMPT).append("\n[명세서 줄] 번호 | 품명 | 수량 | 단가\n");
        for (int i = 0; i < lines.size(); i++) {
            Map<String, Object> l = lines.get(i);
            text.append(i).append(" | ").append(l.get("name")).append(" | ").append(l.get("ea")).append(" | ").append(l.get("price")).append('\n');
        }
        text.append("\n[이 거래처에서 전에 산 품목] 코드 | 품명 규격 | 지난 단가들\n");
        names.forEach((code, name) -> text.append(code).append(" | ").append(name).append(" | ")
                .append(String.join(",", prices.get(code).stream().map(String::valueOf).toList())).append('\n'));
        Map<Integer, Integer> picks = new HashMap<>();
        JsonNode answer = generate(List.of(json.createObjectNode().put("text", text.toString())));
        log.debug("Gemini 품목 답: {}", answer.toString().length() > 2000 ? answer.toString().substring(0, 2000) : answer);
        for (JsonNode p : answer) {
            // 코드를 "4456" 처럼 문자열로 답하기도 한다. asInt 는 숫자 문자열도 읽고, 못 읽으면 -1.
            int line = p.path("line").asInt(-1), code = p.path("itemCode").asInt(-1);
            if (line >= 0 && line < lines.size() && names.containsKey(code)) picks.put(line, code);
        }
        return picks;
    }

    /** generateContent 한 번, 답의 JSON 을 돌려준다. 테스트에서 가짜 응답으로 바꿔 끼운다. */
    JsonNode generate(List<ObjectNode> parts) throws Exception {
        ObjectNode body = json.createObjectNode();
        ArrayNode p = body.putArray("contents").addObject().put("role", "user").putArray("parts");
        parts.forEach(p::add);
        body.putObject("generationConfig").put("responseMimeType", "application/json").put("temperature", 0);
        JsonNode res = client.post().uri("/v1beta/models/{model}:generateContent", model)
                .header("x-goog-api-key", apiKey).contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(JsonNode.class);
        StringBuilder out = new StringBuilder();
        for (JsonNode part : res.path("candidates").path(0).path("content").path("parts")) {
            if (!part.path("thought").asBoolean(false)) out.append(part.path("text").asText());
        }
        return json.readTree(out.toString());
    }
}
