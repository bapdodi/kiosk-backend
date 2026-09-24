package com.example.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.demo.repository.ErpReceiptLogRepository;
import com.example.demo.service.ErpReceivingService;
import com.example.demo.service.ErpReceivingService.VoucherLine;
import com.example.demo.service.ErpReceivingService.VoucherRequest;
import com.example.demo.service.ErpReceivingWriter;

/**
 * 입고 전표 계산/검증 단위 테스트.
 * ERP 에 실제로 쓰는 경로는 ErpReceivingWriter 라, 여기서는 "무엇을 넘기는지"까지만 본다.
 */
class ErpReceivingServiceTest {

    private JdbcTemplate erp;
    private ErpReceiptLogRepository repository;
    private ErpReceivingWriter writer;
    private ErpReceivingService service;

    @BeforeEach
    void setUp() {
        erp = mock(JdbcTemplate.class);
        repository = mock(ErpReceiptLogRepository.class);
        writer = mock(ErpReceivingWriter.class);
        service = new ErpReceivingService(erp, repository, writer);
        ReflectionTestUtils.setField(service, "writeEnabled", true);
        ReflectionTestUtils.setField(service, "dateWindowDays", 7);
        when(writer.stockMode()).thenReturn("NONE");

        // 품목/거래처 조회와 전표번호 채번은 ERP 조회라 전부 스텁으로 대신한다.
        when(erp.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("CODE", 375, "ITEM", "국산볼밸브황동", "GYU", " 15A", "JEGO", 270)));
        when(erp.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
        when(erp.queryForObject(anyString(), eq(String.class), any(Object[].class))).thenReturn("원우배관");
    }

    private VoucherRequest request(List<VoucherLine> lines) {
        return new VoucherRequest("req-1", LocalDate.now().toString(), 930, "메모", lines);
    }

    @Test
    void 미리보기는_공급가와_부가세와_라인번호를_계산한다() {
        Map<String, Object> preview = service.preview(request(List.of(
                new VoucherLine(375, 10, 3600, null),
                new VoucherLine(377, 5, 5376, null))));

        assertEquals(62880L, preview.get("totalAmount"));
        assertEquals(6288L, preview.get("totalVat")); // 라인별 GUM/10 의 합
        assertEquals("KIOSK-IN-req-1", preview.get("tag"));
        assertEquals("IL" + LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yy")),
                preview.get("ilTable"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) preview.get("lines");
        assertEquals(List.of(1, 2), lines.stream().map(l -> l.get("editNo")).toList());
        assertEquals(36000L, lines.get(0).get("gum"));
        assertEquals(3600L, lines.get(0).get("vat"));
    }

    @Test
    void 미리보기는_ERP_에_쓰지_않는다() {
        service.preview(request(List.of(new VoucherLine(375, 1, 100, null))));
        verify(writer, never()).insertVoucher(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString());
    }

    @Test
    void 수량이_0이면_거부한다() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.preview(request(List.of(new VoucherLine(375, 0, 100, null)))));
        assertTrue(e.getMessage().contains("수량"));
    }

    @Test
    void 품목이_비면_거부한다() {
        assertThrows(IllegalArgumentException.class, () -> service.preview(request(List.of())));
    }

    @Test
    void 요청_식별자가_없으면_거부한다() {
        assertThrows(IllegalArgumentException.class, () -> service.preview(
                new VoucherRequest("  ", LocalDate.now().toString(), 930, "", List.of(new VoucherLine(375, 1, 100, null)))));
    }

    @Test
    void 월마감_보호를_위해_오래된_일자는_거부한다() {
        VoucherRequest old = new VoucherRequest("req-1", LocalDate.now().minusDays(30).toString(), 930, "",
                List.of(new VoucherLine(375, 1, 100, null)));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.preview(old));
        assertTrue(e.getMessage().contains("입고일자"));
    }

    @Test
    void 쓰기가_꺼져_있으면_저장을_거부한다() {
        ReflectionTestUtils.setField(service, "writeEnabled", false);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.createVoucher(request(List.of(new VoucherLine(375, 1, 100, null))), "admin"));
        assertTrue(e.getMessage().contains("읽기 전용"));
    }
}
