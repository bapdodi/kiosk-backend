package com.example.demo.service;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.example.demo.entity.Product;
import com.example.demo.repository.ProductRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/**
 * 전체 상품 목록(JSON)을 메모리에 들고 있는다.
 *
 * 상품은 1천 건 남짓이고 ERP 동기화나 관리자 수정 때만 바뀌므로, 키오스크가 화면을 열 때마다
 * 전체를 다시 조회할 이유가 없다. 직렬화까지 끝난 문자열을 캐싱해 JPA 조회와 Jackson 변환을
 * 둘 다 건너뛴다.
 *
 * 엔티티 대신 JSON 문자열을 캐싱하는 이유는, 캐싱된 엔티티는 영속성 컨텍스트 밖에서
 * lazy 컬렉션을 읽을 수 없고 여러 요청이 같은 인스턴스를 공유하게 되기 때문이다.
 */
@Service
@RequiredArgsConstructor
public class ProductCatalogCache {

    private final ProductRepository productRepository;
    private final ObjectMapper objectMapper;

    private final AtomicReference<String> cachedJson = new AtomicReference<>();

    @Transactional(readOnly = true)
    public String getCatalogJson() {
        String json = cachedJson.get();
        if (json != null) return json;

        List<Product> products = productRepository.findAllByDeletedAtIsNullOrderBySortOrderAscIdAsc();
        try {
            // 트랜잭션 안에서 직렬화해야 lazy 컬렉션(옵션/조합)이 정상적으로 로딩된다.
            json = objectMapper.writeValueAsString(products);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("상품 목록 직렬화에 실패했습니다.", e);
        }

        cachedJson.set(json);
        return json;
    }

    /**
     * 상품이 바뀐 뒤 호출한다.
     *
     * 트랜잭션이 살아 있는 동안 비우면, 아직 커밋되지 않은 변경을 다른 요청이 읽어 캐시를
     * 다시 채울 수 있다. 그래서 커밋 이후로 미룬다.
     */
    public void invalidate() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    cachedJson.set(null);
                }
            });
            return;
        }
        cachedJson.set(null);
    }
}
