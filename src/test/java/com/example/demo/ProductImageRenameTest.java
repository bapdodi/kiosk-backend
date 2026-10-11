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
import com.example.demo.entity.OptionImage;
import com.example.demo.repository.CombinationRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.service.FileService;
import com.example.demo.service.ProductCatalogCache;
import com.example.demo.service.ProductService;
import com.example.demo.service.channel.ChannelSyncService;

/** 새 사진은 고유 URL 을 받고, 순서만 바꾸면 기존 URL 과 파일을 유지한다. */
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
    void 순서를_바꿔도_URL과_파일을_유지한다() throws Exception {
        List<String> saved = save("/uploads/8813-2-0a1b2c3d.png", "/uploads/8813-1-4e5f6a7b.png");

        assertThat(saved).containsExactly("/uploads/8813-2-0a1b2c3d.png", "/uploads/8813-1-4e5f6a7b.png");
        assertThat(save(saved.get(1), saved.get(0)))
                .containsExactly("/uploads/8813-1-4e5f6a7b.png", "/uploads/8813-2-0a1b2c3d.png");
        verify(files, never()).renameFile(anyString(), anyString());
    }

    @Test
    void 원본_파일이_없으면_URL을_그대로_둔다() throws Exception {
        when(files.renameFile(eq("ghost.png"), anyString())).thenReturn("ghost.png");

        assertThat(save("/uploads/ghost.png")).containsExactly("/uploads/ghost.png");
    }

    @Test
    void 순서_변경_후에도_옵션이_공유하는_사진_URL을_유지한다() throws Exception {
        String shared = "/uploads/8813-2-0a1b2c3d.png";
        product.setOptionImages(new ArrayList<>(List.of(new OptionImage("규격", "20mm", shared))));

        assertThat(save(shared, "/uploads/8813-1.jpg"))
                .containsExactly(shared, "/uploads/8813-1.jpg");
        assertThat(product.getOptionImages().get(0).getImageUrl()).isEqualTo(shared);
        verify(files, never()).renameFile(anyString(), anyString());
    }

    @Test
    void 사진_삭제로_순번이_당겨져도_파일을_복사하지_않는다() throws Exception {
        assertThat(save("/uploads/8813-3.jpg")).containsExactly("/uploads/8813-3.jpg");
        verify(files, never()).renameFile(anyString(), anyString());
    }
}
