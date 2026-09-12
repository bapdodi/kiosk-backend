package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.demo.entity.Product;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.demo.service.ProductCatalogCache;
import com.example.demo.service.ProductService;

/** 상품 DB 가 떠 있어야 하므로 KIOSK_DB_IT 를 설정한 로컬에서만 돈다. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "KIOSK_DB_IT", matches = "1")
class ProductCatalogCacheIT {

    @Autowired
    ProductCatalogCache productCatalogCache;

    @Autowired
    ProductService productService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ObjectMapper objectMapper;

    private List<Product> readCatalog() {
        try {
            return objectMapper.readValue(productCatalogCache.getCatalogJson(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Product.class));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void 상품을_수정하면_캐시된_전체목록이_갱신된다() {
        // 관리자 화면이 보내는 것과 같은 형태(JSON 으로 오간 detached 엔티티)로 수정 요청을 만든다.
        List<Product> products = readCatalog();
        assertThat(products).isNotEmpty();

        Product target = products.get(0);
        Long id = target.getId();
        String originalName = target.getName();
        String changedName = originalName + " [캐시검증]";

        assertThat(productCatalogCache.getCatalogJson()).contains(originalName);

        try {
            target.setName(changedName);
            productService.updateProduct(id, target);

            // 커밋 이후 캐시가 비워지므로 다음 조회는 바뀐 이름을 보여준다.
            assertThat(productCatalogCache.getCatalogJson()).contains(changedName);
        } finally {
            jdbcTemplate.update("UPDATE products SET name = ? WHERE id = ?", originalName, id);
            productCatalogCache.invalidate();
        }

        assertThat(productCatalogCache.getCatalogJson()).doesNotContain(changedName);
    }
}
