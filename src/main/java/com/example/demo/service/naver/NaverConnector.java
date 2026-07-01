package com.example.demo.service.naver;

import java.util.List;

import org.springframework.stereotype.Component;

import com.example.demo.config.NaverProperties;
import com.example.demo.entity.CategoryRef;
import com.example.demo.entity.ChannelCategoryMapping;
import com.example.demo.entity.Product;
import com.example.demo.repository.ChannelCategoryMappingRepository;
import com.example.demo.service.channel.ChannelApiException;
import com.example.demo.service.channel.ConnectorResult;
import com.example.demo.service.channel.ImagePart;
import com.example.demo.service.channel.SalesChannelConnector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;

/**
 * 네이버 스마트스토어 채널 커넥터. {@link SalesChannelConnector} 구현체.
 * 채널 API 호출(NaverCommerceClient)과 네이버 고유 매핑(카테고리/payload)에만 관여한다.
 */
@Component
@RequiredArgsConstructor
public class NaverConnector implements SalesChannelConnector {

    public static final String CHANNEL = "NAVER";
    private static final String STATUS_SALE = "SALE";
    private static final String STATUS_SUSPENSION = "SUSPENSION";

    private final NaverCommerceClient client;
    private final NaverProductMapper mapper;
    private final NaverProperties props;
    private final ChannelCategoryMappingRepository categoryMappingRepository;

    @Override
    public String channel() {
        return CHANNEL;
    }

    @Override
    public boolean isConfigured() {
        return props.isConfigured();
    }

    @Override
    public ConnectorResult register(Product product, List<ImagePart> images, Long existingOriginProductNo) {
        if (images == null || images.isEmpty()) {
            throw new ChannelApiException("네이버 등록에는 대표 이미지가 최소 1장 필요합니다: " + product.getName());
        }
        long leafCategoryId = resolveLeafCategoryId(product);
        List<String> imageUrls = client.uploadImages(images);
        ObjectNode payload = mapper.toProductPayload(product, leafCategoryId, imageUrls, STATUS_SALE);

        if (existingOriginProductNo != null) {
            JsonNode resp = client.updateProduct(existingOriginProductNo, payload);
            return new ConnectorResult(existingOriginProductNo, extractLong(resp, "channelProductNo"), STATUS_SALE, imageUrls);
        }
        JsonNode resp = client.createProduct(payload);
        Long originProductNo = extractLong(resp, "originProductNo");
        if (originProductNo == null) {
            throw new ChannelApiException("네이버 등록 응답에서 originProductNo 를 찾지 못했습니다: " + resp);
        }
        return new ConnectorResult(originProductNo, extractLong(resp, "channelProductNo"), STATUS_SALE, imageUrls);
    }

    @Override
    public ConnectorResult update(Product product, List<String> reusableImageUrls, long originProductNo) {
        long leafCategoryId = resolveLeafCategoryId(product);
        ObjectNode payload = mapper.toProductPayload(product, leafCategoryId, reusableImageUrls, STATUS_SALE);
        client.updateProduct(originProductNo, payload);
        return new ConnectorResult(originProductNo, null, STATUS_SALE, reusableImageUrls);
    }

    @Override
    public void suspend(long originProductNo) {
        client.changeProductStatus(originProductNo, STATUS_SUSPENSION);
    }

    /** 상품의 카테고리 중 NAVER 매핑이 있는 첫 카테고리의 리프 카테고리 ID. */
    private long resolveLeafCategoryId(Product product) {
        if (product.getCategories() != null) {
            for (CategoryRef ref : product.getCategories()) {
                String main = ref.getMainCategory();
                String sub = ref.getSubCategory();
                ChannelCategoryMapping mapping = null;
                if (sub != null && !sub.isBlank()) {
                    mapping = categoryMappingRepository
                            .findByChannelAndKioskMainCategoryAndKioskSubCategory(CHANNEL, main, sub)
                            .orElse(null);
                }
                if (mapping == null) {
                    mapping = categoryMappingRepository
                            .findByChannelAndKioskMainCategoryAndKioskSubCategoryIsNull(CHANNEL, main)
                            .orElse(null);
                }
                if (mapping != null) {
                    return mapping.getNaverLeafCategoryId();
                }
            }
        }
        throw new ChannelApiException(
                "네이버 카테고리 매핑이 필요합니다. '" + product.getName() + "' 의 카테고리를 먼저 네이버 카테고리에 매핑하세요.");
    }

    private Long extractLong(JsonNode resp, String field) {
        if (resp == null) {
            return null;
        }
        JsonNode node = resp.get(field);
        if (node != null && node.isNumber()) {
            return node.asLong();
        }
        JsonNode found = resp.findValue(field);
        return (found != null && found.isNumber()) ? found.asLong() : null;
    }
}
