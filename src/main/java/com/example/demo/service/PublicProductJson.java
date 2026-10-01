package com.example.demo.service;

import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;

/**
 * 손님 화면으로 나가는 상품 JSON 의 가격을 `price` 하나로 바꾼다.
 *
 * 손님은 모두 같은 A단가를 낸다({@link ErpPriceTier#price}). 그래서 화면에는 그 값만
 * `price` 로 내려주고, A/B/C 원본 단가(priceA/priceB/priceC)는 응답 본문에서 지운다.
 * B단가와 소비자가는 손님 단말에 내려갈 이유가 없고, 개발자도구나 curl 로 전부 보이기 때문이다.
 * 관리자 API 는 이 필터를 거치지 않아 원본 단가를 그대로 받는다.
 *
 * 필드 이름 기준으로 전체를 훑는다. 상품 본문뿐 아니라 combinations 안의 단가까지
 * 한 번에 걸린다. 가격이 없는 객체(옵션 이미지 등)는 건드리지 않는다.
 */
@Component
@RequiredArgsConstructor
public class PublicProductJson {

    private static final List<String> RAW_PRICE_FIELDS = List.of("priceC", "priceA", "priceB");

    private final ObjectMapper objectMapper;

    /** 이미 직렬화된 상품 JSON(배열/객체)을 손님용 가격으로 바꾼 문자열을 돌려준다. */
    public String strip(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            stripNode(root);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("상품 목록의 가격을 손님용으로 바꾸지 못했습니다.", e);
        }
    }

    /** 엔티티(또는 Page 같은 래퍼)를 손님용 가격의 JSON 트리로 바꾼다. */
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
            ObjectNode object = (ObjectNode) node;
            if (RAW_PRICE_FIELDS.stream().anyMatch(object::has)) {
                Integer price = ErpPriceTier.price(intOrNull(object, "priceA"), intOrNull(object, "priceC"));
                object.remove(RAW_PRICE_FIELDS);
                if (price != null) {
                    object.put("price", price);
                }
            }
            object.forEach(this::stripNode);
        }
    }

    private Integer intOrNull(ObjectNode object, String field) {
        JsonNode value = object.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }
}
