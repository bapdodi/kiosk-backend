package com.example.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.example.demo.config.NaverProperties;
import com.example.demo.entity.CategoryRef;
import com.example.demo.entity.ChannelCategoryMapping;
import com.example.demo.entity.Product;
import com.example.demo.repository.ChannelCategoryMappingRepository;
import com.example.demo.service.channel.ConnectorResult;
import com.example.demo.service.channel.ImagePart;
import com.example.demo.service.naver.NaverCommerceClient;
import com.example.demo.service.naver.NaverConnector;
import com.example.demo.service.naver.NaverProductMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** NaverConnector 등록 후 판매중지(staging) 동작 검증. */
class NaverConnectorTest {

    private final ObjectMapper om = new ObjectMapper();

    private NaverConnector connector(NaverCommerceClient client, NaverProperties props) {
        ChannelCategoryMappingRepository repo = mock(ChannelCategoryMappingRepository.class);
        ChannelCategoryMapping mapping = ChannelCategoryMapping.builder()
                .channel("NAVER").kioskMainCategory("배관").naverLeafCategoryId(50003288L).build();
        when(repo.findByChannelAndKioskMainCategoryAndKioskSubCategoryIsNull("NAVER", "배관"))
                .thenReturn(Optional.of(mapping));
        NaverProductMapper productMapper = new NaverProductMapper(
                props, om, mock(com.example.demo.repository.CategoryRepository.class));
        return new NaverConnector(client, productMapper, props, repo);
    }

    private NaverProperties baseProps() {
        NaverProperties props = new NaverProperties();
        props.setAsTelephone("010-0000-0000");
        props.setShippingAddressId("111");
        props.setReturnAddressId("222");
        props.setDeliveryCompany("KGB");
        props.setOriginAreaCode("00");
        return props;
    }

    private Product product() {
        return Product.builder().id(1L).name("PVC 티").priceC(1000).stock(1)
                .categories(new LinkedHashSet<>(List.of(new CategoryRef("배관", null))))
                .build();
    }

    private ObjectNode createResp() {
        ObjectNode resp = om.createObjectNode();
        resp.put("originProductNo", 999L);
        resp.put("channelProductNo", 888L);
        return resp;
    }

    private List<ImagePart> images() {
        return List.of(new ImagePart(new byte[] { 1, 2, 3 }, "a.png", MediaType.IMAGE_PNG));
    }

    @Test
    void 등록후_곧바로_판매중지_처리된다() {
        NaverProperties props = baseProps(); // registerAsSuspended 기본 true
        NaverCommerceClient client = mock(NaverCommerceClient.class);
        when(client.uploadImages(anyList())).thenReturn(List.of("https://cdn/x.jpg"));
        when(client.createProduct(any())).thenReturn(createResp());

        ConnectorResult result = connector(client, props).register(product(), images(), null);

        verify(client).changeProductStatus(eq(999L), eq("SUSPENSION"));
        assertEquals("SUSPENSION", result.status());
        assertEquals(999L, result.originProductNo());
    }

    @Test
    void 플래그가_꺼져있으면_판매중_유지되고_상태변경_호출없음() {
        NaverProperties props = baseProps();
        props.setRegisterAsSuspended(false);
        NaverCommerceClient client = mock(NaverCommerceClient.class);
        when(client.uploadImages(anyList())).thenReturn(List.of("https://cdn/x.jpg"));
        when(client.createProduct(any())).thenReturn(createResp());

        ConnectorResult result = connector(client, props).register(product(), images(), null);

        verify(client, never()).changeProductStatus(any(Long.class), any());
        assertEquals("SALE", result.status());
    }
}
