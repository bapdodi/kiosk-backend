package com.example.demo.service;

import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;

/**
 * 손님 화면으로 나가는 상품 JSON 에서 단가를 걷어낸다.
 *
 * 화면에서 가격을 감춰도 응답 본문에는 그대로 남아 개발자도구나 curl 로 전부 보인다.
 * 가격은 거래처마다 다른 값(A/B/C단가)이라 손님 단말에 내려갈 이유가 없으므로,
 * 공개 API 는 이 필터를 거친 뒤에만 응답한다. 관리자 API 는 거치지 않는다.
 *
 * 필드 이름 기준으로 전체를 훑는다. 상품 본문뿐 아니라 combinations 안의 단가까지
 * 한 번에 걸리고, 나중에 가격을 품은 필드가 추가돼도 이름만 넣으면 된다.
 */
@Component
@RequiredArgsConstructor
public class PublicProductJson {

    private static final List<String> PRICE_FIELDS = List.of("priceC", "priceA", "priceB");

    private final ObjectMapper objectMapper;

    /** 이미 직렬화된 상품 JSON(배열/객체)에서 단가를 지운 문자열을 돌려준다. */
    public String strip(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            stripNode(root);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("상품 목록에서 단가를 제거하지 못했습니다.", e);
        }
    }

    /** 엔티티(또는 Page 같은 래퍼)를 단가 없는 JSON 트리로 바꾼다. */
    public JsonNode strip(Object value) {
        JsonNode root = objectMapper.valueToTree(value);
        stripNode(root);
        return root;
    }

    private void stripNode(JsonNode node) {
        if (node.isArray()) {
            node.forEach(this::stripNode);
            return;
        }
        if (node.isObject()) {
            ((ObjectNode) node).remove(PRICE_FIELDS);
            node.forEach(this::stripNode);
        }
    }
}
