package com.example.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.example.demo.service.naver.NaverCommerceClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 네이버가 등록불가 sellerTags 로 400 을 낼 때, 문제 단어 추출 + payload 정리 로직 검증.
 * (실측: PUT 시 "태그 항목에 등록불가인 단어(보온,배관자재)가 포함되어 있습니다." 400 발생)
 */
class NaverRestrictedTagTest {

    private final ObjectMapper om = new ObjectMapper();

    private NaverCommerceClient client() {
        // 파싱/정리 메서드는 순수 로직이라 협력자는 null 이어도 무방하다.
        return new NaverCommerceClient(null, null, om);
    }

    @Test
    void 등록불가_태그_단어를_응답메시지에서_추출한다() {
        String msg = "네이버 API 오류 400 BAD_REQUEST: "
                + "{\"code\":\"BAD_REQUEST\",\"message\":\"입력한 데이터가 유효하지 않습니다.\","
                + "\"invalidInputs\":[{\"name\":\"originProduct.detailAttribute.seoInfo.sellerTags\","
                + "\"type\":\"Restricted.sellerTags\","
                + "\"message\":\"태그 항목에 등록불가인 단어(보온,배관자재)가 포함되어 있습니다.\"}]}";
        Set<String> restricted = client().extractRestrictedTags(msg);
        assertEquals(Set.of("보온", "배관자재"), restricted);
    }

    @Test
    void sellerTags_아닌_400은_추출대상이_아니다() {
        String msg = "네이버 API 오류 400: {\"invalidInputs\":[{\"name\":\"originProduct.name\","
                + "\"type\":\"Restricted.name\",\"message\":\"금지어(테스트)가 포함되어 있습니다.\"}]}";
        assertTrue(client().extractRestrictedTags(msg).isEmpty());
        assertTrue(client().extractRestrictedTags("타임아웃").isEmpty());
        assertTrue(client().extractRestrictedTags(null).isEmpty());
    }

    @Test
    void payload에서_지정_태그를_제거한다() {
        ObjectNode payload = om.createObjectNode();
        ArrayNode tags = payload.putObject("originProduct").putObject("detailAttribute")
                .putObject("seoInfo").putArray("sellerTags");
        for (String t : new String[] { "피비파이프", "보온", "PB부속", "배관자재", "배관보수" }) {
            tags.addObject().put("text", t);
        }
        boolean removed = client().stripSellerTags(payload, Set.of("보온", "배관자재"));
        assertTrue(removed);

        ArrayNode kept = (ArrayNode) payload.path("originProduct").path("detailAttribute")
                .path("seoInfo").path("sellerTags");
        assertEquals(3, kept.size());
        assertEquals("피비파이프", kept.get(0).get("text").asText());
        assertEquals("PB부속", kept.get(1).get("text").asText());
        assertEquals("배관보수", kept.get(2).get("text").asText());
    }

    @Test
    void 제거할게_없으면_false() {
        ObjectNode payload = om.createObjectNode();
        payload.putObject("originProduct").putObject("detailAttribute")
                .putObject("seoInfo").putArray("sellerTags").addObject().put("text", "배관보수");
        assertFalse(client().stripSellerTags(payload, Set.of("보온")));
        // seoInfo/sellerTags 자체가 없어도 안전
        assertFalse(client().stripSellerTags(om.createObjectNode(), Set.of("보온")));
    }
}
