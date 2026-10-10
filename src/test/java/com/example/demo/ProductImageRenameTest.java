package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.demo.dto.ProductUpdateRequest;
import com.example.demo.entity.Product;
import com.example.demo.repository.CombinationRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.service.FileService;
import com.example.demo.service.ProductCatalogCache;
import com.example.demo.service.ProductService;
import com.example.demo.service.channel.ChannelSyncService;

/** 사진이 바뀌거나 순서가 바뀌면 URL 도 바뀌어야 CDN/브라우저 캐시가 옛 사진을 내주지 않는다. */
class ProductImageRenameTest {
    private FileService files;
    private ProductService service;
    private Product product;

    @BeforeEach
    void setup() throws Exception {
        ProductRepository repository = mock(ProductRepository.class);
        files = mock(FileService.class);
        service = new ProductService(repository, mock(CombinationRepository.class), files,
                mock(ChannelSyncService.class), mock(ProductCatalogCache.class));
        product = Product.builder().id(8813L).name("소켓").images(new ArrayList<>()).build();
        when(repository.findById(8813L)).thenReturn(Optional.of(product));
        when(repository.save(any(Product.class))).thenAnswer(i -> i.getArgument(0));
        when(files.getFileUrl(anyString())).thenAnswer(i -> "/uploads/" + i.getArgument(0));
        when(files.renameFile(anyString(), anyString())).thenAnswer(i -> i.getArgument(1));
    }

    private List<String> save(String... urls) {
        return service.updateProduct(8813L, request(List.of(urls))).orElseThrow().getImages();
    }

    private ProductUpdateRequest request(List<String> images) {
        return new ProductUpdateRequest(null, null, null, null, null, images, null, null, null);
    }

    @Test
    void 새로_올린_사진은_매번_다른_이름을_받는다() {
        String first = save("/uploads/aaaa_x.png").get(0);
        String second = save("/uploads/bbbb_y.png").get(0);

        assertThat(first).matches("/uploads/8813-1-[0-9a-f]{8}\\.png");
        assertThat(second).matches("/uploads/8813-1-[0-9a-f]{8}\\.png").isNotEqualTo(first);
    }

    @Test
    void 제_자리의_사진은_이름을_바꾸지_않는다() throws Exception {
        List<String> saved = save("/uploads/8813-1-0a1b2c3d.png", "/uploads/8813-2.jpg");

        assertThat(saved).containsExactly("/uploads/8813-1-0a1b2c3d.png", "/uploads/8813-2.jpg");
        verify(files, never()).renameFile(anyString(), anyString());
    }

    @Test
    void 순서를_바꾸면_옮겨진_사진만_새_URL을_받는다() throws Exception {
        List<String> saved = save("/uploads/8813-2-0a1b2c3d.png", "/uploads/8813-1-4e5f6a7b.png");

        assertThat(saved.get(0)).matches("/uploads/8813-1-[0-9a-f]{8}\\.png").isNotEqualTo("/uploads/8813-1-4e5f6a7b.png");
        assertThat(saved.get(1)).matches("/uploads/8813-2-[0-9a-f]{8}\\.png").isNotEqualTo("/uploads/8813-2-0a1b2c3d.png");
    }

    @Test
    void 원본_파일이_없으면_URL을_그대로_둔다() throws Exception {
        when(files.renameFile(eq("ghost.png"), anyString())).thenReturn("ghost.png");

        assertThat(save("/uploads/ghost.png")).containsExactly("/uploads/ghost.png");
    }
}
