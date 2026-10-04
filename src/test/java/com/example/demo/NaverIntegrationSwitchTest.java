package com.example.demo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.example.demo.config.NaverProperties;
import com.example.demo.entity.ChannelProductLink;
import com.example.demo.entity.Product;
import com.example.demo.repository.CategoryRepository;
import com.example.demo.repository.ChannelCategoryMappingRepository;
import com.example.demo.repository.ChannelProductLinkRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.service.FileService;
import com.example.demo.service.channel.ChannelApiException;
import com.example.demo.service.channel.ChannelSyncService;
import com.example.demo.service.naver.NaverCommerceClient;
import com.example.demo.service.naver.NaverConnector;
import com.example.demo.service.naver.NaverProductMapper;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * NAVER_INTEGRATION_ENABLED 스위치 검증.
 * 자격증명이 채워져 있어도 연동이 꺼져 있으면 네이버 API 를 부르지 않고 링크도 건드리지 않아야 한다.
 */
class NaverIntegrationSwitchTest {

    private static final long PRODUCT_ID = 8614L;
    private static final long ORIGIN_NO = 9697473866L;

    private final ObjectMapper om = new ObjectMapper();
    private final NaverCommerceClient client = mock(NaverCommerceClient.class);
    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final ChannelProductLinkRepository linkRepository = mock(ChannelProductLinkRepository.class);

    private NaverProperties props(boolean enabled) {
        NaverProperties props = new NaverProperties();
        props.setEnabled(enabled);
        props.setClientId("client-id");
        props.setClientSecret("client-secret");
        return props;
    }

    private ChannelSyncService service(NaverProperties props) {
        NaverProductMapper mapper = new NaverProductMapper(props, om, mock(CategoryRepository.class));
        NaverConnector connector = new NaverConnector(client, mapper, props, mock(ChannelCategoryMappingRepository.class));
        ChannelProductLink link = ChannelProductLink.builder()
                .productId(PRODUCT_ID).channel("NAVER").originProductNo(ORIGIN_NO).naverStatus("SALE").build();
        when(linkRepository.findByProductId(PRODUCT_ID)).thenReturn(List.of(link));
        when(linkRepository.findByProductIdAndChannel(PRODUCT_ID, "NAVER")).thenReturn(Optional.of(link));
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(Product.builder().id(PRODUCT_ID).name("노허브").build()));
        return new ChannelSyncService(productRepository, linkRepository, mock(FileService.class), om, List.of(connector));
    }

    @Test
    void 연동이_꺼져_있으면_자격증명이_있어도_설정되지_않은_것으로_본다() {
        assertFalse(props(false).isConfigured());
        assertTrue(props(true).isConfigured());
    }

    @Test
    void 연동이_꺼져_있으면_키오스크_상품_삭제가_네이버를_판매중지하지_않는다() {
        service(props(false)).suspendEverywhere(PRODUCT_ID);

        verify(client, never()).changeProductStatus(anyLong(), anyString());
        verify(linkRepository, never()).save(any());
    }

    @Test
    void 연동이_켜져_있으면_키오스크_상품_삭제가_네이버를_판매중지한다() {
        service(props(true)).suspendEverywhere(PRODUCT_ID);

        verify(client).changeProductStatus(ORIGIN_NO, "SUSPENSION");
    }

    @Test
    void 연동이_꺼져_있으면_전송과_상태변경과_동기화가_네이버를_부르지_않는다() {
        ChannelSyncService service = service(props(false));

        assertThrows(ChannelApiException.class, () -> service.push("naver", PRODUCT_ID));
        assertThrows(ChannelApiException.class, () -> service.changeStatus("naver", PRODUCT_ID, "SALE"));
        assertThrows(ChannelApiException.class, () -> service.applyChanges("naver", List.of(PRODUCT_ID)));

        verify(client, never()).createProduct(any());
        verify(client, never()).updateProduct(anyLong(), any());
        verify(client, never()).changeProductStatus(anyLong(), anyString());
        verify(linkRepository, never()).save(any());
    }
}
