package com.example.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.demo.config.NaverProperties;
import com.example.demo.entity.Combination;
import com.example.demo.entity.Product;
import com.example.demo.service.naver.NaverAuthService;
import com.example.demo.service.naver.NaverProductMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 자격증명 없이도 검증 가능한 부분: 서명 생성 + 상품 payload 변환.
 * (네이버 실호출은 실제 client_id/secret + 판매자 승인 후 별도로 확인)
 */
class NaverProductMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private NaverProductMapper mapper() {
        NaverProperties props = new NaverProperties();
        props.setAsTelephone("010-0000-0000");
        props.setShippingAddressId("111");
        props.setReturnAddressId("222");
        return new NaverProductMapper(props, objectMapper);
    }

    @Test
    void 단품_상품_payload_기본필드() {
        Product p = Product.builder()
                .id(1L)
                .name("PVC 배관 100mm")
                .description("튼튼한 배관")
                .price(12000)
                .stock(30)
                .build();

        ObjectNode payload = mapper().toProductPayload(p, 50000840L, List.of("https://cdn.naver/img1.jpg", "https://cdn.naver/img2.jpg"), "SALE");
        ObjectNode origin = (ObjectNode) payload.get("originProduct");

        assertEquals("PVC 배관 100mm", origin.get("name").asText());
        assertEquals(12000, origin.get("salePrice").asInt());
        assertEquals(30, origin.get("stockQuantity").asInt());
        assertEquals(50000840L, origin.get("leafCategoryId").asLong());
        assertEquals("SALE", origin.get("statusType").asText());
        assertEquals("NEW", origin.get("productState").asText());
        assertEquals("https://cdn.naver/img1.jpg",
                origin.get("images").get("representativeImage").get("url").asText());
        assertEquals("https://cdn.naver/img2.jpg",
                origin.get("images").get("optionalImages").get(0).get("url").asText());
        assertEquals("ON", payload.get("smartstoreChannelProduct").get("channelProductDisplayStatusType").asText());
        assertTrue(origin.get("detailContent").asText().contains("PVC 배관"));
    }

    @Test
    void 복합옵션_상품이면_조합재고합과_옵션조합생성() {
        Product p = Product.builder()
                .id(2L)
                .name("호스")
                .price(5000)
                .stock(0)
                .combinations(List.of(
                        Combination.builder().name("색상:빨강 / 굵기:10mm").price(0).stock(3).deleted(false).build(),
                        Combination.builder().name("색상:파랑 / 굵기:20mm").price(500).stock(7).deleted(false).build()))
                .build();

        ObjectNode payload = mapper().toProductPayload(p, 123L, List.of("https://cdn.naver/h.jpg"), "SALE");
        ObjectNode origin = (ObjectNode) payload.get("originProduct");

        // 조합 재고 합 = 3 + 7
        assertEquals(10, origin.get("stockQuantity").asInt());

        ObjectNode optionInfo = (ObjectNode) origin.get("detailAttribute").get("optionInfo");
        assertNotNull(optionInfo);
        assertEquals("색상", optionInfo.get("optionCombinationGroupNames").get("optionGroupName1").asText());
        assertEquals("굵기", optionInfo.get("optionCombinationGroupNames").get("optionGroupName2").asText());
        assertEquals("빨강", optionInfo.get("optionCombinations").get(0).get("optionName1").asText());
        assertEquals(7, optionInfo.get("optionCombinations").get(1).get("stockQuantity").asInt());
        assertEquals(500, optionInfo.get("optionCombinations").get(1).get("price").asInt());
    }

    @Test
    void 검색태그와_SEO정보가_payload에_포함된다() {
        Product p = Product.builder()
                .id(4L)
                .name("스테인리스 엘보")
                .description("부식에 강한   스테인리스\n엘보 이음쇠")
                .price(3000)
                .stock(5)
                .hashtags(List.of("#배관", "엘보", "배관", "스테인리스"))
                .build();

        ObjectNode detail = (ObjectNode) mapper()
                .toProductPayload(p, 1L, List.of("https://cdn.naver/e.jpg"), "SALE")
                .get("originProduct").get("detailAttribute");

        ObjectNode seo = (ObjectNode) detail.get("seoInfo");
        assertNotNull(seo);
        assertEquals("스테인리스 엘보", seo.get("pageTitle").asText());
        // 공백 정규화 + '#' 제거 + 중복(배관) 제거
        assertFalse(seo.get("metaDescription").asText().contains("  "));
        assertEquals("배관", seo.get("sellerTags").get(0).get("text").asText());
        assertEquals(3, seo.get("sellerTags").size());
    }

    @Test
    void 상품정보제공고시_ETC가_필수필드와_함께_구성된다() {
        Product p = Product.builder().id(5L).name("PVC 티").price(1000).stock(1).build();
        ObjectNode notice = (ObjectNode) mapper()
                .toProductPayload(p, 1L, List.of("https://cdn.naver/t.jpg"), "SALE")
                .get("originProduct").get("detailAttribute").get("productInfoProvidedNotice");

        assertNotNull(notice);
        assertEquals("ETC", notice.get("productInfoProvidedNoticeType").asText());
        ObjectNode etc = (ObjectNode) notice.get("etc");
        assertEquals("PVC 티", etc.get("itemName").asText());
        assertEquals("PVC 티", etc.get("modelName").asText());
        assertEquals("상품상세참조", etc.get("returnCostReason").asText());
        assertEquals("010-0000-0000", etc.get("customerServicePhoneNumber").asText());
    }

    @Test
    void 상품별_원산지코드가_있으면_우선사용된다() {
        Product p = Product.builder().id(6L).name("수입 밸브").price(1000).stock(1)
                .originAreaCode("0200037").build();
        ObjectNode detail = (ObjectNode) mapper()
                .toProductPayload(p, 1L, List.of("https://cdn.naver/v.jpg"), "SALE")
                .get("originProduct").get("detailAttribute");
        assertEquals("0200037", detail.get("originAreaInfo").get("originAreaCode").asText());
    }

    @Test
    void 판매중지_상태면_채널노출도_SUSPENSION() {
        Product p = Product.builder().id(3L).name("t").price(1000).stock(1).build();
        ObjectNode payload = mapper().toProductPayload(p, 1L, List.of("https://cdn.naver/a.jpg"), "SUSPENSION");
        assertEquals("SUSPENSION", payload.get("originProduct").get("statusType").asText());
        assertEquals("SUSPENSION", payload.get("smartstoreChannelProduct").get("channelProductDisplayStatusType").asText());
    }

    @Test
    void 서명_생성은_결정적이고_비어있지_않다() {
        String salt = "$2a$10$N9qo8uLOickgx2ZMRZoMye"; // bcrypt salt 형식(테스트용)
        String s1 = NaverAuthService.createSignature("APP_ID", salt, 1_700_000_000_000L);
        String s2 = NaverAuthService.createSignature("APP_ID", salt, 1_700_000_000_000L);
        assertNotNull(s1);
        assertFalse(s1.isEmpty());
        assertEquals(s1, s2); // 동일 입력 → 동일 서명(bcrypt salt 고정)
    }
}
