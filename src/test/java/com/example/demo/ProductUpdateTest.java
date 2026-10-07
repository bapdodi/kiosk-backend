package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.demo.dto.ProductUpdateRequest;
import com.example.demo.entity.CategoryRef;
import com.example.demo.entity.Combination;
import com.example.demo.entity.Product;
import com.example.demo.repository.CombinationRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.service.ErpCustomerSync;
import com.example.demo.service.ErpProductSync;
import com.example.demo.service.FileService;
import com.example.demo.service.ProductCatalogCache;
import com.example.demo.service.ProductService;
import com.example.demo.service.channel.ChannelSyncService;
import com.fasterxml.jackson.databind.ObjectMapper;

class ProductUpdateTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private ProductRepository repository;
    private ProductCatalogCache cache;
    private ProductService service;
    private Product product;
    private Combination spec;

    @BeforeEach
    void setup() {
        repository = mock(ProductRepository.class);
        cache = mock(ProductCatalogCache.class);
        service = new ProductService(repository, mock(CombinationRepository.class),
                mock(FileService.class), mock(ChannelSyncService.class), cache);
        spec = Combination.builder().id_db(10L).id("100").name("15A").erpCode("100")
                .priceA(1000).priceB(2000).priceC(3000).stock(30).sortOrder(0).build();
        product = Product.builder().id(1L).name("ERP 상품").erpCode("100").gyu("15A")
                .priceA(1000).priceB(2000).priceC(3000).stock(30).isComplexOptions(true)
                .brandName("기존 브랜드").originAreaCode("00").description("설명")
                .categories(new LinkedHashSet<>(List.of(new CategoryRef("cat", null))))
                .images(new ArrayList<>(List.of("https://cdn/old.jpg")))
                .combinations(new ArrayList<>(List.of(spec))).build();
        when(repository.findById(1L)).thenReturn(Optional.of(product));
        when(repository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void 상품명은_수정되지만_ERP값과_브랜드_원산지는_수정하지_않는다() throws Exception {
        ProductUpdateRequest request = mapper.readValue("""
                {"name":"변경 이름","erpCode":"999","gyu":"99A","stock":1,
                 "priceA":1,"priceB":2,"priceC":3,"isComplexOptions":false,
                 "brandName":"변경 브랜드","originAreaCode":"99","description":"새 설명",
                 "combinations":[{"id_db":10,"id":"999","name":"99A","erpCode":"999",
                     "stock":1,"priceA":1,"priceB":2,"priceC":3,"deleted":true,"sortOrder":5}]}
                """, ProductUpdateRequest.class);
        service.updateProduct(1L, request);
        assertThat(product.getName()).isEqualTo("변경 이름");
        assertThat(product.getErpCode()).isEqualTo("100");
        assertThat(product.getGyu()).isEqualTo("15A");
        assertThat(product.getPriceA()).isEqualTo(1000);
        assertThat(product.getPriceB()).isEqualTo(2000);
        assertThat(product.getPriceC()).isEqualTo(3000);
        assertThat(product.getStock()).isEqualTo(30);
        assertThat(product.getIsComplexOptions()).isTrue();
        assertThat(product.getBrandName()).isEqualTo("기존 브랜드");
        assertThat(product.getOriginAreaCode()).isEqualTo("00");
        assertThat(product.getDescription()).isEqualTo("새 설명");
        assertThat(spec.getName()).isEqualTo("15A");
        assertThat(spec.getId()).isEqualTo("100");
        assertThat(spec.getErpCode()).isEqualTo("100");
        assertThat(spec.getPriceA()).isEqualTo(1000);
        assertThat(spec.getPriceB()).isEqualTo(2000);
        assertThat(spec.getPriceC()).isEqualTo(3000);
        assertThat(spec.getStock()).isEqualTo(30);
        assertThat(spec.getDeleted()).isTrue();
        assertThat(spec.getSortOrder()).isEqualTo(5);
        verify(cache).invalidate();
    }

    @Test
    void 사진만_저장해도_누락된_분류와_규격은_그대로_유지한다() throws Exception {
        var request = mapper.readValue("{\"images\":[\"https://cdn/new.jpg\"]}", ProductUpdateRequest.class);
        service.updateProduct(1L, request);
        assertThat(product.getImages()).containsExactly("https://cdn/new.jpg");
        assertThat(product.getCategories()).containsExactly(new CategoryRef("cat", null));
        assertThat(product.getDescription()).isEqualTo("설명");
        assertThat(product.getCombinations()).containsExactly(spec);
        assertThat(spec.getDeleted()).isFalse();
    }

    @Test
    void 요청에_없는_새_ERP규격은_숨기지_않고_다른_상품_규격은_수정하지_않는다() throws Exception {
        var extra = Combination.builder().id_db(11L).name("20A").sortOrder(1).build();
        product.getCombinations().add(extra);
        spec.setDeleted(true);
        var request = mapper.readValue("""
                {"combinations":[{"id_db":10,"deleted":false,"sortOrder":2},
                    {"id_db":999,"name":"새 규격","deleted":true}]}
                """, ProductUpdateRequest.class);
        service.updateProduct(1L, request);
        assertThat(product.getCombinations()).containsExactly(spec, extra);
        assertThat(spec.getDeleted()).isFalse();
        assertThat(spec.getSortOrder()).isEqualTo(2);
        assertThat(extra.getDeleted()).isFalse();
        assertThat(extra.getSortOrder()).isEqualTo(1);
    }

    @Test
    void 키오스크_규격이름은_저장하고_빈값이면_ERP규격명으로_되돌린다() throws Exception {
        service.updateProduct(1L, mapper.readValue("""
                {"combinations":[{"id_db":10,"kioskName":"  연결관 15  "}]}
                """, ProductUpdateRequest.class));
        assertThat(spec.getKioskName()).isEqualTo("연결관 15");
        assertThat(spec.getName()).isEqualTo("15A");

        service.updateProduct(1L, mapper.readValue("""
                {"combinations":[{"id_db":10,"deleted":false}]}
                """, ProductUpdateRequest.class));
        assertThat(spec.getKioskName()).isEqualTo("연결관 15");

        service.updateProduct(1L, mapper.readValue("""
                {"combinations":[{"id_db":10,"kioskName":""}]}
                """, ProductUpdateRequest.class));
        assertThat(spec.getKioskName()).isNull();
    }

    @Test
    void 동기화해도_관리자가_바꾼_상품명은_유지한다() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(repository.findAll()).thenReturn(List.of(product));
        when(jdbc.queryForList(any(String.class))).thenReturn(List.of(Map.of(
                "CODE", 100, "ITEM", "  경영박사  상품  ", "GYU", "15A",
                "OUTA", 1000, "OUTB", 2000, "OUTC", 3000, "JEGO", 30)));
        var sync = new ErpProductSync(jdbc, repository, service, mock(ErpCustomerSync.class), cache);
        var result = sync.syncProducts();
        assertThat(result.updated()).isEqualTo(1);
        assertThat(product.getName()).isEqualTo("ERP 상품");
        assertThat(product.getCategories()).containsExactly(new CategoryRef("cat", null));
        assertThat(product.getDescription()).isEqualTo("설명");
    }
}
