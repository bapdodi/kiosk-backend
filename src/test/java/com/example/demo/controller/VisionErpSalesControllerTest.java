package com.example.demo.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class VisionErpSalesControllerTest {
    @Test
    void requiresSharedToken() {
        var controller = new VisionErpSalesController(mock(JdbcTemplate.class), "secret");
        assertThrows(ResponseStatusException.class,
                () -> controller.sales(LocalDate.of(2026, 9, 25), "wrong"));
    }

    @Test
    void returnsDirectAndKioskSalesFromSameErpQuery() {
        JdbcTemplate erp = mock(JdbcTemplate.class);
        when(erp.queryForObject(anyString(), eq(Integer.class), eq("IL26"))).thenReturn(1);
        List<Map<String, Object>> rows = List.of(
                Map.of("autokey", 11, "traceTag", ""),
                Map.of("autokey", 12, "traceTag", "KIOSK-42"));
        when(erp.queryForList(anyString(), eq("26.09.25"))).thenReturn(rows);
        var controller = new VisionErpSalesController(erp, "secret");

        assertEquals(rows, controller.sales(LocalDate.of(2026, 9, 25), "secret"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(erp).queryForList(sql.capture(), eq("26.09.25"));
        assertTrue(sql.getValue().contains("l.KIND = '3'"));
        assertTrue(!sql.getValue().contains("KIOSK-"));
    }
}
