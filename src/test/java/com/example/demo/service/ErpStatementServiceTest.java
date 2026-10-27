package com.example.demo.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.demo.entity.StatementItemAlias;
import com.example.demo.entity.StatementVendorAlias;
import com.example.demo.repository.StatementItemAliasRepository;
import com.example.demo.repository.StatementVendorAliasRepository;
import com.example.demo.service.ErpStatementService.LearnPair;
import com.example.demo.service.ErpStatementService.LearnRequest;

/** 2026-05-20 JK건축자재 명세서와 ERP 매입 전표(dNO 32)에서 가져온 실제 짝으로 매칭 규칙을 본다. */
class ErpStatementServiceTest {

    private static final List<Map<String, String>> VENDORS = List.of(
            Map.of("code", "560", "name", "(주)제이케이건축자재", "saup", ""),
            Map.of("code", "58", "name", "(주)경원종합배관", "saup", ""),
            Map.of("code", "9", "name", "경원", "saup", "1234567890"));

    @Test
    void sameItemWithDifferentNamesIsConfidentWhenPriceAndSpecMatch() {
        // 명세서 "DRF LT 100(LK)(6)" 11,226원 = ERP "디엘티 (DRF LT) 100A" 11,226원
        int hit = ErpStatementService.score("DRF LT 100(LK)(6)", 11226, "디엘티 (DRF LT) 100A", 11226);
        assertTrue(hit >= ErpStatementService.CONFIDENT_SCORE, "score=" + hit);
    }

    @Test
    void priceAloneIsNotConfident() {
        int hit = ErpStatementService.score("DRF LT 100(LK)(6)", 11226, "PVC플랜지 ㅎ250A (맹플랜지)", 11226);
        assertTrue(hit < ErpStatementService.CONFIDENT_SCORE, "score=" + hit);
    }

    @Test
    void differentPriceAndNameScoresZero() {
        assertEquals(0, ErpStatementService.score("DRF LT 100(LK)(6)", 11226, "PVC플랜지 맹플랜지", 89900));
    }

    @Test
    void vendorIsFoundByNameInHeaderAndLongestNameWins() {
        Map<String, String> v = ErpStatementService.matchVendor(VENDORS, List.of(), "2026 05 29 (주) 경원종합배관 귀하");
        assertEquals("58", v.get("code"));
    }

    @Test
    void businessNumberBeatsName() {
        Map<String, String> v = ErpStatementService.matchVendor(VENDORS, List.of("123-45-67890"), "제이케이건축자재");
        assertEquals("9", v.get("code"));
    }

    @Test
    void unknownVendorStaysEmptyForThePersonToPick() {
        assertNull(ErpStatementService.matchVendor(VENDORS, List.of(), "삼성화학 어쩌고"));
    }

    @Test
    void totalMatchesSupplyAmountOrAmountPlusVat() {
        List<Map<String, Object>> lines = List.of(
                Map.of("amount", 72500, "vat", 7250), Map.of("amount", 25810, "vat", 2581));
        long sum = 98310;
        // 공급가액이 찍힌 경우, 부가세 포함 합계가 찍힌 경우(줄별 부가세 합), 1원 어긋난 경우
        assertTrue(ErpStatementService.totalMatched(lines, sum, java.util.Set.of(98310L)));
        assertTrue(ErpStatementService.totalMatched(lines, sum, java.util.Set.of(108141L)));
        assertTrue(ErpStatementService.totalMatched(lines, sum, java.util.Set.of(108142L)));
        // 찍힌 숫자 중 어느 것도 줄의 합과 맞지 않으면 빠진 줄이 있는 것이다
        assertEquals(false, ErpStatementService.totalMatched(lines, sum, java.util.Set.of(72500L, 25810L, 500000L)));
    }

    @Test
    void vendorIsGuessedFromOverlappingPricesWhenNameDoesNotMatch() {
        Map<String, java.util.Set<Long>> prices = Map.of(
                "560", java.util.Set.of(39076L, 25287L, 15335L, 360L),
                "58", java.util.Set.of(22775L, 360L));
        assertEquals("560", ErpStatementService.vendorByPrices(prices, List.of(39076L, 25287L, 15335L, 360L)));
        // 겹치는 단가가 3개 미만이면 추정하지 않는다
        assertNull(ErpStatementService.vendorByPrices(prices, List.of(39076L, 25287L)));
        // 두 거래처가 비슷하게 겹치면 사람이 고르게 둔다
        Map<String, java.util.Set<Long>> tie = Map.of(
                "1", java.util.Set.of(100L, 200L, 300L), "2", java.util.Set.of(100L, 200L, 300L));
        assertNull(ErpStatementService.vendorByPrices(tie, List.of(100L, 200L, 300L)));
    }

    // ── 학습 ────────────────────────────────────────────────────────────────

    private static StatementItemAlias alias(String name, int itemCode, int hits) {
        StatementItemAlias a = new StatementItemAlias();
        a.setVendorCode(560); a.setNameKey(ErpStatementService.nameKey(name)); a.setItemCode(itemCode); a.setHits(hits);
        return a;
    }

    @Test
    void handwritingNoiseDoesNotBreakTheAliasMatch() {
        // OCR 이 같은 줄을 읽을 때마다 "박//", "본본본" 같은 잡음이 달라진다
        List<StatementItemAlias> aliases = List.of(alias("VN SDR 33 125 PPI 박//", 4249, 3));
        Map<Integer, Integer> hit = ErpStatementService.aliasScores(aliases, "VN SDR 33 125 PPI 본본본");
        assertEquals(ErpStatementService.ALIAS_SCORE + 3, hit.get(4249));
        // 규격 숫자가 다르면 다른 품목이다(125 와 100)
        assertTrue(ErpStatementService.aliasScores(aliases, "VN SDR 33 100 PPI(3)").isEmpty());
    }

    private final JdbcTemplate erp = mock(JdbcTemplate.class);
    private final StatementItemAliasRepository itemRepo = mock(StatementItemAliasRepository.class);
    private final StatementVendorAliasRepository vendorRepo = mock(StatementVendorAliasRepository.class);

    private ErpStatementService service() {
        when(erp.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
        return new ErpStatementService(erp, mock(ErpReceivingService.class), itemRepo, vendorRepo, "", 600);
    }

    @Test
    void learnAddsNewPairAndReinforcesAcceptedOne() {
        StatementItemAlias accepted = alias("VN SDR 33 125 PPI", 4249, 2);
        when(itemRepo.findByVendorCode(560)).thenReturn(new ArrayList<>(List.of(accepted)));
        Map<String, Object> out = service().learn(new LearnRequest(560, "123-45-67891", List.of(
                new LearnPair("VN SDR 33 125 PPI 본본본", 4249, 4249), // 제안을 그대로 수락(OCR 잡음이 달라도 같은 별칭에 합쳐진다)
                new LearnPair("스리브고무링 35 vg2용", 2744, null)))); // 사람이 직접 고름
        assertEquals(2, out.get("learned"));
        assertEquals(3, accepted.getHits());
        verify(itemRepo, org.mockito.Mockito.times(2)).save(any(StatementItemAlias.class));
        // 사업자번호도 거래처와 짝으로 남긴다(숫자만)
        org.mockito.ArgumentCaptor<StatementVendorAlias> v = org.mockito.ArgumentCaptor.forClass(StatementVendorAlias.class);
        verify(vendorRepo).save(v.capture());
        assertEquals("1234567891", v.getValue().getBizNo());
        assertEquals(1, v.getValue().getHits());
    }

    @Test
    void learnPenalizesTheSuggestionThePersonCorrected() {
        StatementItemAlias wrong = alias("엘보 100", 111, 1);
        StatementItemAlias strong = alias("엘보 50", 222, 5);
        when(itemRepo.findByVendorCode(560)).thenReturn(new ArrayList<>(List.of(wrong, strong)));
        // 화면은 111 을 제안했지만 사람이 333 으로 고쳐 저장했다
        service().learn(new LearnRequest(560, null, List.of(new LearnPair("엘보 100", 333, 111))));
        verify(itemRepo).delete(wrong);                       // 한 번 맞았던 짝은 0 이 되어 사라진다
        verify(itemRepo, never()).delete(strong);             // 이름이 다른 별칭은 건드리지 않는다
        assertEquals(5, strong.getHits());
    }

    @Test
    void learnRejectsUnknownVendorAndSkipsBlankNames() {
        when(erp.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
        ErpStatementService svc = new ErpStatementService(erp, mock(ErpReceivingService.class), itemRepo, vendorRepo, "", 600);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> svc.learn(new LearnRequest(999, null, List.of())));
        assertFalse(ErpStatementService.nameKey("박//").length() > 0); // 잡음뿐인 품명은 키가 비어 배우지 않는다
    }

    @Test
    void pageNumbersAreReadFromTheHeader() {
        assertEquals(List.of(2, 3), java.util.Arrays.stream(ErpStatementService.pageOf("기업은행 (2/3) 송신 031-855")).boxed().toList());
        assertEquals(List.of(1, 1), java.util.Arrays.stream(ErpStatementService.pageOf("FAOM PAGE:001/001")).boxed().toList());
        assertNull(ErpStatementService.pageOf("2026 05 20 123-45-67891"));      // 사업자번호의 - 는 쪽 번호가 아니다
        assertNull(ErpStatementService.pageOf("(5/3)"));                         // 현재 쪽이 전체보다 크면 오독
    }

    @Test
    void misreadBusinessNumberIsNotLearned() {
        assertTrue(ErpStatementService.validBizNo("1234567891"));
        assertTrue(ErpStatementService.validBizNo("9876543215"));
        assertFalse(ErpStatementService.validBizNo("1234567890"));  // 검증 숫자가 틀린 번호(OCR 오독)
        when(itemRepo.findByVendorCode(560)).thenReturn(new ArrayList<>());
        service().learn(new LearnRequest(560, "123-45-67890", List.of(new LearnPair("엘보 100", 1, null))));
        verify(vendorRepo, never()).save(any(StatementVendorAlias.class));
    }

    @Test
    void conversionFactorComesFromTheLastErpPrice() {
        // 명세서 240 x 2,187 = ERP 60 x 8,748 (ERP 는 4개 묶음 단위로 입력)
        assertEquals(0.25, ErpStatementService.conversionFactor(240, 2187, 8748), 1e-9);
        // 단가가 이미 같으면 환산하지 않는다
        assertEquals(1.0, ErpStatementService.conversionFactor(10, 1359, 1359), 1e-9);
        // 지난 단가와 어떤 계수로도 안 맞으면 건드리지 않는다
        assertNull(ErpStatementService.conversionFactor(7, 5000, 123457));
        // 환산한 수량이 정수가 아니면(3 x 0.25) 쓰지 않는다
        assertNull(ErpStatementService.conversionFactor(3, 2187, 8748));
    }

    @Test
    void priceChangedWithin25PercentStillHelpsAndNameSpellingVariantsMatch() {
        int exact = ErpStatementService.score("엘보 100", 1000, "엘보 100A", 1000);
        int near = ErpStatementService.score("엘보 100", 1100, "엘보 100A", 1000);
        int far = ErpStatementService.score("엘보 100", 2000, "엘보 100A", 1000);
        assertTrue(exact > near && near > far, exact + " " + near + " " + far);
        // 스리브/슬리브 표기가 달라도 글자쌍이 겹쳐 점수가 붙는다
        assertTrue(ErpStatementService.score("스리브고무링 35", 360, "슬리브고무링VG2 35A", 9999) > 0);
    }

    @Test
    void twoLetterVendorNameNeedsACompanyMarkerRightNextToIt() {
        // "주식회사 지엔" 은 법인 표기를 빼면 "지엔" 두 글자라 예전에는 아무 헤더에서도 못 찾았다
        List<Map<String, String>> vendors = List.of(Map.of("code", "1352", "name", "주식회사 지엔", "saup", ""));
        assertEquals("1352", ErpStatementService.matchVendor(vendors, List.of(), "호 주식회사지엔 성명 이명용").get("code"));
        assertEquals("1352", ErpStatementService.matchVendor(vendors, List.of(), "상호 (주) 지엔 대표").get("code"));
        // 법인 표기 없이 "지엔" 만 우연히 들어 있으면 거래처로 보지 않는다
        assertNull(ErpStatementService.matchVendor(vendors, List.of(), "배관 지엔 규격 80A"));
    }

    @Test
    void rematchRejectsUnknownVendorAndTooManyLines() {
        when(erp.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
        ErpStatementService svc = new ErpStatementService(erp, mock(ErpReceivingService.class), itemRepo, vendorRepo, "", 600);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> svc.rematch(new ErpStatementService.RematchRequest(999, List.of())));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> svc.rematch(new ErpStatementService.RematchRequest(null, List.of())));
    }
}
