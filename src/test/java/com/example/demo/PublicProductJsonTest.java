package com.example.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

import com.example.demo.service.PublicProductJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class PublicProductJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final PublicProductJson publicProductJson = new PublicProductJson(mapper);

    @Test
    void 손님용_가격은_A단가_하나뿐이고_원본_단가는_지운다() throws Exception {
        String json = "[{\"id\":1,\"priceA\":2500,\"priceB\":2700,\"priceC\":4000,"
                + "\"combinations\":[{\"id\":\"a\",\"priceA\":3000,\"priceB\":3100,\"priceC\":5000},"
                + "{\"id\":\"b\",\"priceA\":0,\"priceB\":0,\"priceC\":6000}]}]";

        JsonNode product = mapper.readTree(publicProductJson.strip(json)).get(0);

        assertEquals(2500, product.get("price").asInt());
        assertEquals(3000, product.get("combinations").get(0).get("price").asInt());
        // A단가가 비면 소비자가로 대신한다
        assertEquals(6000, product.get("combinations").get(1).get("price").asInt());
        assertFalse(product.has("priceA") || product.has("priceB") || product.has("priceC"));
        assertFalse(product.get("combinations").get(0).has("priceB"));
    }
}
