package com.example.demo.service;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.demo.entity.Product;
import com.example.demo.repository.ProductRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 전체 상품 목록(JSON)을 메모리에 들고 있는다.
 *
 * 상품은 1천 건 남짓이고 ERP 동기화나 관리자 수정 때만 바뀌므로, 키오스크가 화면을 열 때마다
 * 전체를 다시 조회할 이유가 없다. 직렬화까지 끝난 문자열을 캐싱해 JPA 조회와 Jackson 변환을
 * 둘 다 건너뛴다.
 *
 * 엔티티 대신 JSON 문자열을 캐싱하는 이유는, 캐싱된 엔티티는 영속성 컨텍스트 밖에서
 * lazy 컬렉션을 읽을 수 없고 여러 요청이 같은 인스턴스를 공유하게 되기 때문이다.
 *
 * 캐시 값에는 만들 때의 세대 번호를 붙인다. 목록을 읽는 도중 상품이 바뀌면(invalidate 가 세대를
 * 올리면) 그 결과는 이미 낡았을 수 있으므로, 세대가 다른 값은 적중으로 치지 않는다.
 * 예전처럼 null 로만 비우면, 수정 전에 읽기 시작한 요청이 무효화 뒤에 옛 목록을 다시 채워 넣었다.
 */
@Service
public class ProductCatalogCache {

    private record Entry(long generation, String json) {}

    private final ProductRepository productRepository;
    private final ObjectMapper objectMapper;
    private final PublicProductJson publicProductJson;
    private final TransactionTemplate readOnlyTx;

    private final AtomicLong generation = new AtomicLong();
    private final AtomicReference<Entry> cachedJson = new AtomicReference<>();
    private final AtomicReference<Entry> cachedPublicJson = new AtomicReference<>();
    /** 캐시가 비었을 때 여러 키오스크가 동시에 전체 목록을 읽지 않도록 한 번에 하나만 만든다. */
    private final Object loadLock = new Object();

    public ProductCatalogCache(ProductRepository productRepository, ObjectMapper objectMapper,
            PublicProductJson publicProductJson, PlatformTransactionManager transactionManager) {
        this.productRepository = productRepository;
        this.objectMapper = objectMapper;
        this.publicProductJson = publicProductJson;
        this.readOnlyTx = new TransactionTemplate(transactionManager);
        this.readOnlyTx.setReadOnly(true);
    }

    /** 관리자 화면용 원본 목록. 적중하면 DB 커넥션을 잡지 않는다. */
    public String getCatalogJson() {
        return getOrLoad(cachedJson, () -> readOnlyTx.execute(status -> {
            List<Product> products = productRepository.findAllByDeletedAtIsNullOrderBySortOrderAscIdAsc();
            try {
                // 트랜잭션 안에서 직렬화해야 lazy 컬렉션(옵션/조합)이 정상적으로 로딩된다.
                return objectMapper.writeValueAsString(products);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("상품 목록 직렬화에 실패했습니다.", e);
            }
        }));
    }

    /**
     * 손님 화면용 전체 상품 목록. 단가(A/B/C)를 `price` 하나로 바꾼 뒤 그 결과를 따로 캐싱한다.
     * 관리자 목록과 원본이 같으므로, 걷어내는 비용도 상품이 바뀔 때 한 번만 든다.
     */
    public String getPublicCatalogJson() {
        return getOrLoad(cachedPublicJson, () -> publicProductJson.strip(getCatalogJson()));
    }

    private String getOrLoad(AtomicReference<Entry> slot, Supplier<String> loader) {
        Entry entry = slot.get();
        if (entry != null && entry.generation() == generation.get()) {
            return entry.json();
        }
        synchronized (loadLock) {
            long startedAt = generation.get();
            entry = slot.get();
            if (entry != null && entry.generation() == startedAt) {
                return entry.json();
            }
            String json = loader.get();
            // 읽는 동안 상품이 바뀌었으면 응답에는 쓰되 캐시에는 남기지 않는다.
            if (generation.get() == startedAt) {
                slot.set(new Entry(startedAt, json));
            }
            return json;
        }
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
                    clear();
                }
            });
            return;
        }
        clear();
    }

    private void clear() {
        generation.incrementAndGet();
        cachedJson.set(null);
        cachedPublicJson.set(null);
    }
}
