package com.example.demo.service;

import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;

/** 공개 주문 생성 응답에서 서버가 계산한 가격을 제거한다. */
@Component
@RequiredArgsConstructor
public class PublicOrderJson {

    private static final List<String> PRICE_FIELDS = List.of("totalAmount", "finalPrice", "chargedPrice");

    private final ObjectMapper objectMapper;

    public JsonNode strip(Object order) {
        JsonNode root = objectMapper.valueToTree(order);
        stripNode(root);
        return root;
    }

    private void stripNode(JsonNode node) {
        if (node.isArray()) {
            node.forEach(this::stripNode);
        } else if (node.isObject()) {
            ((ObjectNode) node).remove(PRICE_FIELDS);
            node.forEach(this::stripNode);
        }
    }
}
