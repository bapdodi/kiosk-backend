package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import java.util.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import com.example.demo.entity.*;
import com.example.demo.repository.ProductRepository;
import com.example.demo.service.*;

class ErpCaseGroupingTest {
    private Map<String,Object> row(int code, String name, String size) {
        return Map.of("CODE",code,"ITEM",name,"GYU",size,"OUTA",100,"OUTB",200,"OUTC",300,"JEGO",2);
    }
    @Test
    void mixedCaseSpecsRemainTogetherAcrossRepeatedSyncs() {
        for (String[] names : List.of(new String[]{"백장닛플(K/S)","백장닛플(k/s)"},
                new String[]{"백관파이프(k/S)","백관파이프(k/s)"},
                new String[]{"스텐플랜지(20K)","스텐플랜지(20k)"},
                new String[]{"철플랜지20K","철플랜지20k"},
                new String[]{"SP캡","sp캡"})) {
            JdbcTemplate jdbc=mock(JdbcTemplate.class);
            ProductRepository repo=mock(ProductRepository.class);
            ProductService service=mock(ProductService.class);
            Product target=Product.builder().id(10L).name(names[0]).isComplexOptions(true)
                    .combinations(new ArrayList<>()).build();
            Combination first=Combination.builder().id("100").erpCode("100").name("40A")
                    .product(target).deleted(false).stock(2).build();
            Combination second=Combination.builder().id("101").erpCode("101").name("50A")
                    .product(target).deleted(false).stock(2).build();
            target.getCombinations().addAll(List.of(first,second));
            Product old=Product.builder().id(1L).name(names[1]).erpCode("101")
                    .deletedAt(Instant.now()).combinations(new ArrayList<>()).build();
            when(repo.findAll()).thenReturn(List.of(target,old));
            when(jdbc.queryForList(anyString())).thenReturn(List.of(row(101,names[1],"50A"),row(100,names[0],"40A")));
            ErpProductSync sync=new ErpProductSync(jdbc,repo,service,mock(ErpCustomerSync.class),mock(ProductCatalogCache.class));
            for(int i=0;i<3;i++) {
                var result=sync.syncProducts();
                assertThat(result.created()).isZero();
                assertThat(result.updated()).isEqualTo(1);
                assertThat(result.duplicate()).isEmpty();
                assertThat(target.getCombinations()).hasSize(2).allMatch(c -> !c.getDeleted());
                assertThat(target.getErpCode()).isNull();
                assertThat(target.getIsComplexOptions()).isTrue();
                assertThat(old.getDeletedAt()).isNotNull();
            }
        }
    }
    @Test
    void nameFallbackUsesTheSameCaseInsensitiveKey() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        ProductRepository repo=mock(ProductRepository.class);
        Product target=Product.builder().id(10L).name("SP캡").combinations(new ArrayList<>()).build();
        when(repo.findAll()).thenReturn(List.of(target));
        when(jdbc.queryForList(anyString())).thenReturn(List.of(row(100," sp캡 ","80A")));
        var sync=new ErpProductSync(jdbc,repo,mock(ProductService.class),mock(ErpCustomerSync.class),mock(ProductCatalogCache.class));
        assertThat(sync.previewProducts()).singleElement().satisfies(p -> assertThat(p.existing()).isTrue());
    }
}
