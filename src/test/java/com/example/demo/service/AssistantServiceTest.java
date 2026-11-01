package com.example.demo.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.demo.entity.AssistantLog;
import com.example.demo.entity.CashLedgerEntry;
import com.example.demo.entity.Order;
import com.example.demo.repository.AssistantLogRepository;
import com.example.demo.repository.CashLedgerEntryRepository;
import com.example.demo.repository.OrderRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.service.ErpReceivingService.VoucherRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class AssistantServiceTest {

    private final ObjectMapper json = new ObjectMapper();
    private final JdbcTemplate erp = mock(JdbcTemplate.class);
    private final ErpReceivingService receiving = mock(ErpReceivingService.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final OrderService orderService = mock(OrderService.class);
    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final ProductCatalogCache productCatalogCache = mock(ProductCatalogCache.class);
    private final CashLedgerEntryRepository cashLedgerRepository = mock(CashLedgerEntryRepository.class);
    private final ErpProductSync erpProductSync = mock(ErpProductSync.class);
    private final AssistantLogRepository logs = mock(AssistantLogRepository.class);

    /** Gemini 대신 정해 둔 응답을 차례로 돌려주고, 보낸 요청을 모아 둔다. */
    private AssistantService service(String key, List<String> replies, List<ObjectNode> sent) throws Exception {
        return new AssistantService(erp, receiving, orders, orderService, productRepository,
                productCatalogCache, cashLedgerRepository, erpProductSync, logs, json, key, "test-model") {
            @Override
            JsonNode generate(ObjectNode body) {
                sent.add(body.deepCopy());
                try {
                    return json.readTree(replies.remove(0));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    private static String call(String name, String args) {
        return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"functionCall\":{\"id\":\"c1\",\"name\":\""
                + name + "\",\"args\":" + args + "},\"thoughtSignature\":\"sig\"}]}}]}";
    }

    private static String text(String t) {
        return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"생각\",\"thought\":true},{\"text\":\"" + t + "\"}]}}]}";
    }

    @Test
    void runsReadToolAndAnswersWithItsResult() throws Exception {
        when(receiving.searchItems("동관")).thenReturn(List.of(Map.of("CODE", 1234, "ITEM", "동관 ", "GYU", "15A",
                "DANWI", "M", "JEGO", 12, "INPR", 1000, "OUTA", 1500)));
        List<ObjectNode> sent = new ArrayList<>();
        var svc = service("key", new ArrayList<>(List.of(call("search_items", "{\"keyword\":\"동관\"}"), text("재고 12개입니다."))), sent);

        Map<String, Object> result = svc.chat(List.of(new AssistantService.Message("user", "동관 재고?")));

        assertEquals("재고 12개입니다.", result.get("reply"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> used = (List<Map<String, Object>>) result.get("tools");
        assertEquals("search_items", used.get(0).get("name"));
        JsonNode contents = sent.get(1).get("contents");
        assertEquals("sig", contents.get(1).at("/parts/0/thoughtSignature").asText());
        JsonNode response = contents.get(2).at("/parts/0/functionResponse");
        assertEquals("c1", response.get("id").asText());
        assertEquals(12, response.at("/response/result/품목/0/현재고").asInt());
        verify(logs).save(any(AssistantLog.class));
    }

    @Test
    void generatesActionCardForProposeReceivingVoucher() throws Exception {
        when(receiving.preview(any(VoucherRequest.class))).thenReturn(Map.of(
                "vendorName", "고려메탈", "totalAmount", 68750L, "totalVat", 6875L,
                "lines", List.of(Map.of("itemCode", 731, "ea", 10, "price", 6875))));

        List<ObjectNode> sent = new ArrayList<>();
        String callJson = call("propose_receiving_voucher",
                "{\"vendorCode\":10,\"lines\":[{\"itemCode\":731,\"ea\":10,\"price\":6875}]}");
        var svc = service("key", new ArrayList<>(List.of(callJson, text("입고 초안을 작성했습니다."))), sent);

        Map<String, Object> result = svc.chat(List.of(new AssistantService.Message("user", "고려메탈 동관 입고해줘")));

        assertEquals("입고 초안을 작성했습니다.", result.get("reply"));
        @SuppressWarnings("unchecked")
        List<AssistantService.ActionCard> actions = (List<AssistantService.ActionCard>) result.get("actions");
        assertNotNull(actions);
        assertEquals(1, actions.size());
        assertEquals("RECEIVING_VOUCHER", actions.get(0).type());
        assertTrue(actions.get(0).summary().contains("고려메탈"));
        assertTrue(actions.get(0).summary().contains("68,750원"));
    }

    @Test
    void executesActionSafelyUponUserApproval() throws Exception {
        when(receiving.createVoucher(any(VoucherRequest.class), eq("admin")))
                .thenReturn(Map.of("voucherNo", "IL26-001", "totalAmount", 68750L));

        var svc = service("key", new ArrayList<>(), new ArrayList<>());

        JsonNode payload = json.readTree("{\"clientRequestId\":\"r1\",\"date\":\"2026-10-31\",\"vendorCode\":10,\"memo\":\"메모\",\"lines\":[{\"itemCode\":731,\"ea\":10,\"price\":6875}]}");
        Map<String, Object> execResult = svc.executeAction("RECEIVING_VOUCHER", payload, "admin");

        assertTrue((Boolean) execResult.get("success"));
        verify(receiving).createVoucher(any(VoucherRequest.class), eq("admin"));
    }

    @Test
    void executesCashLedgerAction() throws Exception {
        when(cashLedgerRepository.save(any(CashLedgerEntry.class))).thenAnswer(i -> i.getArgument(0));

        var svc = service("key", new ArrayList<>(), new ArrayList<>());

        JsonNode payload = json.readTree("{\"entryDate\":\"2026-10-31\",\"memo\":\"식대\",\"cashIn\":0,\"cardIn\":0,\"expense\":30000,\"etc\":\"점심\"}");
        Map<String, Object> execResult = svc.executeAction("CASH_LEDGER_ENTRY", payload, "admin");

        assertTrue((Boolean) execResult.get("success"));
        verify(cashLedgerRepository).save(any(CashLedgerEntry.class));
    }

    @Test
    void executesOrderStatusAction() throws Exception {
        Order mockOrder = new Order();
        mockOrder.setId(100L);
        mockOrder.setStatus("완료");
        when(orderService.updateOrderStatus(100L, "완료")).thenReturn(Optional.of(mockOrder));

        var svc = service("key", new ArrayList<>(), new ArrayList<>());

        JsonNode payload = json.readTree("{\"orderId\":100,\"status\":\"완료\"}");
        Map<String, Object> execResult = svc.executeAction("ORDER_STATUS", payload, "admin");

        assertTrue((Boolean) execResult.get("success"));
        verify(orderService).updateOrderStatus(100L, "완료");
    }

    @Test
    void supportsImageAttachmentInMessages() throws Exception {
        List<ObjectNode> sent = new ArrayList<>();
        var svc = service("key", new ArrayList<>(List.of(text("사진을 확인했습니다."))), sent);

        List<AssistantService.ImageAttachment> imgs = List.of(new AssistantService.ImageAttachment("image/jpeg", "base64data=="));
        svc.chat(List.of(new AssistantService.Message("user", "이 사진 봐줘", imgs)));

        JsonNode contents = sent.get(0).get("contents");
        JsonNode parts = contents.get(0).get("parts");
        assertEquals("이 사진 봐줘", parts.get(0).get("text").asText());
        assertEquals("image/jpeg", parts.get(1).at("/inlineData/mimeType").asText());
        assertEquals("base64data==", parts.get(1).at("/inlineData/data").asText());
    }

    @Test
    void refusesToolsOutsideTheReadOnlyList() throws Exception {
        List<ObjectNode> sent = new ArrayList<>();
        var svc = service("key", new ArrayList<>(List.of(call("delete_order", "{\"id\":1}"), text("할 수 없습니다."))), sent);

        svc.chat(List.of(new AssistantService.Message("user", "주문 1번 지워줘")));

        JsonNode result = sent.get(1).get("contents").get(2).at("/parts/0/functionResponse/response/result");
        assertTrue(result.get("오류").asText().contains("없는 도구"));
        verifyNoInteractions(erp, orders);
    }

    @Test
    void declaresOnlyKnownTools() throws Exception {
        List<ObjectNode> sent = new ArrayList<>();
        service("key", new ArrayList<>(List.of(text("안녕하세요"))), sent)
                .chat(List.of(new AssistantService.Message("user", "안녕")));
        List<String> declared = new ArrayList<>();
        sent.get(0).at("/tools/0/functionDeclarations").forEach(d -> declared.add(d.get("name").asText()));
        assertEquals(AssistantService.TOOL_LABELS.keySet(), new java.util.HashSet<>(declared));
    }

    @Test
    void rejectsMissingKeyAndBadRange() throws Exception {
        var noKey = service("", new ArrayList<>(), new ArrayList<>());
        assertThrows(IllegalStateException.class, () -> noKey.chat(List.of(new AssistantService.Message("user", "안녕"))));
        assertThrows(IllegalArgumentException.class, () -> AssistantService.checkRange(
                java.time.LocalDate.of(2025, 1, 1), java.time.LocalDate.of(2026, 6, 1)));
    }

    @Test
    void compactHistorySummarizesOlderTurnsWhenLong() throws Exception {
        var svc = service("key", new ArrayList<>(), new ArrayList<>());
        List<AssistantService.Message> history = new ArrayList<>();
        history.add(new AssistantService.Message("user", "동관 15A 재고 얼마야?"));
        history.add(new AssistantService.Message("model", "현재고 0개입니다."));
        history.add(new AssistantService.Message("user", "미도금속에 발주서 작성해줘"));
        history.add(new AssistantService.Message("model", "발주서 초안을 작성했습니다."));
        history.add(new AssistantService.Message("user", "태은플라자도 추천해줘"));
        history.add(new AssistantService.Message("model", "추천 품목 8건입니다."));
        history.add(new AssistantService.Message("user", "태은 발주서 잡아줘"));
        history.add(new AssistantService.Message("model", "승인 카드를 띄웠습니다."));

        List<AssistantService.Message> compacted = svc.compactHistory(history);
        assertEquals(6, compacted.size());
        assertTrue(compacted.get(0).text().contains("[이전 대화 요약 및 맥락]"));
        assertTrue(compacted.get(0).text().contains("동관 15A"));
        assertEquals("태은 발주서 잡아줘", compacted.get(4).text());
        assertEquals("승인 카드를 띄웠습니다.", compacted.get(5).text());
    }

    @Test
    void getRecentLogsReturnsFormattedEntries() throws Exception {
        var svc = service("key", new ArrayList<>(), new ArrayList<>());
        when(logs.findTop50ByOrderByCreatedAtDesc()).thenReturn(List.of(
                AssistantLog.builder()
                        .id(1L)
                        .createdAt(java.time.LocalDateTime.of(2026, 11, 1, 10, 0))
                        .question("동관 재고")
                        .tools("search_items")
                        .answer("0개입니다")
                        .build()
        ));
        var list = svc.getRecentLogs(10);
        assertEquals(1, list.size());
        assertEquals("동관 재고", list.get(0).get("question"));
        assertEquals("search_items", list.get(0).get("tools"));
        assertEquals("0개입니다", list.get(0).get("answer"));
    }
}
