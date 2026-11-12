package com.example.demo.service;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.databind.ObjectMapper;

class OrderVoiceServiceTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void refusesUnconfiguredAndDisabledSessions() throws Exception {
        var missing = new OrderVoiceService(json, "", "live-model", true, 60);
        assertFalse(missing.available());
        assertEquals(503, assertThrows(ResponseStatusException.class,
                () -> missing.reserveSession(Instant.now())).getStatusCode().value());
        assertFalse(new OrderVoiceService(json, "test-key", "live-model", false, 60).available());
    }

    @Test
    void publicSessionBudgetIsBoundedAndResetsAfterOneHour() throws Exception {
        var service = new OrderVoiceService(json, "test-key", "live-model", true, 2);
        var now = Instant.now();
        service.reserveSession(now);
        service.reserveSession(now);
        assertEquals(429, assertThrows(ResponseStatusException.class,
                () -> service.reserveSession(now)).getStatusCode().value());
        service.reserveSession(now.plusSeconds(3601));
    }

    @Test
    void locksSingleUseTokensToCustomerToolsWithConfirmedCheckout() throws Exception {
        var service = new OrderVoiceService(json, "test-key", "live-model", true, 60);
        var now = Instant.parse("2026-11-12T00:00:00Z");
        var request = json.valueToTree(service.tokenRequest(now));
        assertEquals(1, request.path("uses").asInt());
        assertEquals(now.plusSeconds(60).toString(), request.path("newSessionExpireTime").asText());
        assertEquals(now.plusSeconds(600).toString(), request.path("expireTime").asText());
        assertEquals("models/live-model", request.at("/bidiGenerateContentSetup/model").asText());
        assertEquals("AUDIO", request.at("/bidiGenerateContentSetup/generationConfig/responseModalities/0").asText());
        var tools = request.at("/bidiGenerateContentSetup/tools/0/functionDeclarations");
        assertEquals(11, tools.size());
        boolean hasConfirmation = false;
        for (var tool : tools) {
            if (tool.path("name").asText().equals("confirm_order")) hasConfirmation = true;
            assertFalse(tool.path("name").asText().contains("admin"));
        }
        assertTrue(hasConfirmation);
        assertFalse(request.toString().contains("test-key"));
    }
}
