package com.example.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.demo.config.NaverProperties;
import com.example.demo.entity.Category;
import com.example.demo.entity.CategoryRef;
import com.example.demo.entity.Combination;
import com.example.demo.entity.Product;
import com.example.demo.repository.CategoryRepository;
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
        // 카테고리 없는 테스트 상품은 findById 를 호출하지 않으므로 빈 목(mock)으로 충분.
        return new NaverProductMapper(props, objectMapper, org.mockito.Mockito.mock(CategoryRepository.class));
    }

    @Test
    void 단품_상품_payload_기본필드() {
        Product p = Product.builder()
                .id(1L)
                .name("PVC 배관 100mm")
                .description("튼튼한 배관")
                .priceC(12000)
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
                .priceC(5000)
                .stock(0)
                .combinations(List.of(
                        Combination.builder().name("색상:빨강 / 굵기:10mm").priceC(0).stock(3).deleted(false).build(),
                        Combination.builder().name("색상:파랑 / 굵기:20mm").priceC(500).stock(7).deleted(false).build()))
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
    void 옵션가_스프레드가_넓으면_메인가격은_최저옵션가_추가금은_모두_0이상() {
        // 옵션 1,000 / 5,000 / 9,000 (모두 재고>0). 기본판매가 1,000 로는 +100% 범위(2,000) 초과 → 즉시할인 경로.
        Product p = Product.builder()
                .id(20L)
                .name("연결구")
                .priceC(1000)
                .stock(0)
                .combinations(List.of(
                        Combination.builder().name("굵기:10mm").priceC(1000).stock(5).deleted(false).build(),
                        Combination.builder().name("굵기:20mm").priceC(5000).stock(5).deleted(false).build(),
                        Combination.builder().name("굵기:30mm").priceC(9000).stock(5).deleted(false).build()))
                .build();

        ObjectNode origin = (ObjectNode) mapper()
                .toProductPayload(p, 123L, List.of("https://cdn.naver/c.jpg"), "SALE")
                .get("originProduct");

        int salePrice = origin.get("salePrice").asInt();
        int discount = origin.get("customerBenefit").get("immediateDiscountPolicy")
                .get("discountMethod").get("value").asInt();
        // 메인가격(판매가 - 즉시할인) = 최저 옵션가 1,000 (중간값 5,000 이 아님)
        assertEquals(1000, salePrice - discount);

        // 추가금(옵션가) = combo - 1,000 → 0 / 4,000 / 8,000, 모두 0 이상
        ObjectNode optionInfo = (ObjectNode) origin.get("detailAttribute").get("optionInfo");
        assertEquals(0, optionInfo.get("optionCombinations").get(0).get("price").asInt());
        assertEquals(4000, optionInfo.get("optionCombinations").get(1).get("price").asInt());
        assertEquals(8000, optionInfo.get("optionCombinations").get(2).get("price").asInt());
    }

    @Test
    void 검색태그와_SEO정보가_payload에_포함된다() {
        Product p = Product.builder()
                .id(4L)
                .name("스테인리스 엘보")
                .description("부식에 강한   스테인리스\n엘보 이음쇠")
                .priceC(3000)
                .stock(5)
                .hashtags(List.of("#배관", "엘보", "배관", "스테인리스"))
                .build();

        ObjectNode detail = (ObjectNode) mapper()
                .toProductPayload(p, 1L, List.of("https://cdn.naver/e.jpg"), "SALE")
                .get("originProduct").get("detailAttribute");

        ObjectNode seo = (ObjectNode) detail.get("seoInfo");
        assertNotNull(seo);
        // pageTitle 은 검색 키워드가 보강된 상품명(원래 이름으로 시작)
        assertTrue(seo.get("pageTitle").asText().startsWith("스테인리스 엘보"));
        // 공백 정규화 + '#' 제거 + 중복(배관) 제거
        assertFalse(seo.get("metaDescription").asText().contains("  "));
        assertEquals("배관", seo.get("sellerTags").get(0).get("text").asText());
        assertEquals(3, seo.get("sellerTags").size());
    }

    @Test
    void 검색태그의_내부_특수문자와_공백은_제거된다() {
        // 네이버 판매자태그는 한글/영문/숫자만 허용 → 내부 '/'·공백·'·' 등이 남으면 400(NotAllowedChar).
        Product p = Product.builder()
                .id(5L)
                .name("배관 이음쇠")
                .priceC(1000)
                .stock(1)
                .hashtags(List.of("1/2인치", "20A 배관", "PB·부속", "정상태그"))
                .build();

        var tags = mapper()
                .toProductPayload(p, 1L, List.of("https://cdn.naver/t.jpg"), "SALE")
                .get("originProduct").get("detailAttribute").get("seoInfo").get("sellerTags");

        for (var tag : tags) {
            assertTrue(tag.get("text").asText().matches("[0-9A-Za-z가-힣]+"),
                    "허용되지 않는 문자 포함: " + tag.get("text").asText());
        }
        assertEquals("12인치", tags.get(0).get("text").asText());
        assertEquals("20A배관", tags.get(1).get("text").asText());
        assertEquals("PB부속", tags.get(2).get("text").asText());
        assertEquals("정상태그", tags.get(3).get("text").asText());
    }

    @Test
    void 해시태그가_없으면_상품명과_카테고리로_검색태그_자동생성_및_브랜드제조사_주입() {
        CategoryRepository repo = org.mockito.Mockito.mock(CategoryRepository.class);
        org.mockito.Mockito.when(repo.findById("pipes"))
                .thenReturn(java.util.Optional.of(Category.builder().id("pipes").name("배관용품").build()));
        NaverProperties props = new NaverProperties();
        props.setBrandName("행복철물");
        props.setManufacturerName("자체제작");
        NaverProductMapper mapper = new NaverProductMapper(props, objectMapper, repo);

        Product p = Product.builder()
                .id(9L)
                .name("PVC 배관 100mm")
                .priceC(1000)
                .stock(1)
                .categories(new java.util.LinkedHashSet<>(List.of(new CategoryRef("pipes", null))))
                .build();

        ObjectNode detail = (ObjectNode) mapper
                .toProductPayload(p, 1L, List.of("https://cdn.naver/x.jpg"), "SALE")
                .get("originProduct").get("detailAttribute");

        // 브랜드/제조사/모델 주입
        ObjectNode search = (ObjectNode) detail.get("naverShoppingSearchInfo");
        assertNotNull(search);
        assertEquals("행복철물", search.get("brandName").asText());
        assertEquals("자체제작", search.get("manufacturerName").asText());
        assertEquals("PVC 배관 100mm", search.get("modelName").asText());

        // 상품명 토큰(PVC, 배관, 100mm) + 카테고리 한글명(배관용품)으로 태그 자동생성
        java.util.List<String> texts = new java.util.ArrayList<>();
        detail.get("seoInfo").get("sellerTags").forEach(n -> texts.add(n.get("text").asText()));
        assertTrue(texts.contains("배관"), texts.toString());
        assertTrue(texts.contains("배관용품"), texts.toString());
    }

    @Test
    void 상품명에_도메인_키워드가_보강되고_시드태그가_붙는다() {
        // "피비 아답타 엘보" → stem(피비/아답타/엘보) 시드 키워드가 이름/태그에 반영
        Product p = Product.builder().id(10L).name("피비 아답타 엘보").priceC(1000).stock(1).build();
        ObjectNode origin = (ObjectNode) mapper()
                .toProductPayload(p, 1L, List.of("https://cdn.naver/a.jpg"), "SALE")
                .get("originProduct");

        String name = origin.get("name").asText();
        assertTrue(name.startsWith("피비 아답타 엘보"), name);
        assertTrue(name.contains("배관연결"), name);
        assertTrue(name.length() <= 100, "이름은 100자 이하: " + name.length());

        java.util.List<String> texts = new java.util.ArrayList<>();
        origin.get("detailAttribute").get("seoInfo").get("sellerTags").forEach(n -> texts.add(n.get("text").asText()));
        assertTrue(texts.contains("아답타"), texts.toString());
        assertTrue(texts.contains("엘보"), texts.toString());
    }

    @Test
    void 상품명에_알려진_제조사가_있으면_브랜드제조사로_채택된다() {
        Product p = Product.builder().id(11L).name("이지조인트 엘보 EZ-JOINT").priceC(1000).stock(1).build();
        ObjectNode search = (ObjectNode) mapper()
                .toProductPayload(p, 1L, List.of("https://cdn.naver/e.jpg"), "SALE")
                .get("originProduct").get("detailAttribute").get("naverShoppingSearchInfo");

        assertEquals("조인탑", search.get("brandName").asText());
        assertEquals("조인탑", search.get("manufacturerName").asText());
    }

    @Test
    void 상품정보제공고시_ETC가_필수필드와_함께_구성된다() {
        Product p = Product.builder().id(5L).name("PVC 티").priceC(1000).stock(1).build();
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
        Product p = Product.builder().id(6L).name("수입 밸브").priceC(1000).stock(1)
                .originAreaCode("0200037").build();
        ObjectNode detail = (ObjectNode) mapper()
                .toProductPayload(p, 1L, List.of("https://cdn.naver/v.jpg"), "SALE")
                .get("originProduct").get("detailAttribute");
        assertEquals("0200037", detail.get("originAreaInfo").get("originAreaCode").asText());
    }

    @Test
    void 판매중지_상태면_채널노출도_SUSPENSION() {
        Product p = Product.builder().id(3L).name("t").priceC(1000).stock(1).build();
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
