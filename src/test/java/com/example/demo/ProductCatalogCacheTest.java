package com.example.demo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import com.example.demo.entity.Product;
import com.example.demo.repository.ProductRepository;
import com.example.demo.service.ProductCatalogCache;
import com.example.demo.service.PublicProductJson;
import com.fasterxml.jackson.databind.ObjectMapper;

class ProductCatalogCacheTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private ProductRepository repository;
    private ProductCatalogCache cache;

    @BeforeEach
    void setUp() {
        repository = mock(ProductRepository.class);
        cache = new ProductCatalogCache(repository, mapper, new PublicProductJson(mapper),
                mock(PlatformTransactionManager.class));
    }

    private static List<Product> catalog(String name) {
        return List.of(Product.builder().id(1L).name(name).priceA(1000).priceC(1500).build());
    }

    @Test
    void 캐시가_차_있으면_DB를_다시_읽지_않는다() {
        when(repository.findAllByDeletedAtIsNullOrderBySortOrderAscIdAsc()).thenReturn(catalog("볼밸브"));

        cache.getCatalogJson();
        cache.getCatalogJson();
        cache.getPublicCatalogJson();

        verify(repository, times(1)).findAllByDeletedAtIsNullOrderBySortOrderAscIdAsc();
    }

    @Test
    void 무효화하면_다음_조회가_새로_읽는다() {
        when(repository.findAllByDeletedAtIsNullOrderBySortOrderAscIdAsc())
                .thenReturn(catalog("볼밸브"), catalog("게이트밸브"));

        assertTrue(cache.getPublicCatalogJson().contains("볼밸브"));
        cache.invalidate();
        assertTrue(cache.getPublicCatalogJson().contains("게이트밸브"));
    }

    @Test
    void 읽는_도중_상품이_바뀌면_그_결과를_캐시에_남기지_않는다() {
        // 목록을 읽는 사이 관리자 수정이 커밋돼 무효화가 끼어든 상황.
        when(repository.findAllByDeletedAtIsNullOrderBySortOrderAscIdAsc())
                .thenAnswer(inv -> {
                    cache.invalidate();
                    return catalog("옛 이름");
                })
                .thenReturn(catalog("새 이름"));

        assertTrue(cache.getCatalogJson().contains("옛 이름"));
        String next = cache.getCatalogJson();
        assertTrue(next.contains("새 이름"));
        assertFalse(cache.getPublicCatalogJson().contains("옛 이름"));
        verify(repository, times(2)).findAllByDeletedAtIsNullOrderBySortOrderAscIdAsc();
    }
}
