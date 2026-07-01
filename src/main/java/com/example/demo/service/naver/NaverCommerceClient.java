package com.example.demo.service.naver;

import java.util.ArrayList;
import java.util.List;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import com.example.demo.config.NaverProperties;
import com.example.demo.service.channel.ChannelApiException;
import com.example.demo.service.channel.ImagePart;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 네이버 커머스API 저수준 클라이언트. 인증 헤더 부착 + 401 시 토큰 재발급 후 1회 재시도.
 *
 * ⚠ 일부 엔드포인트 경로/응답 스키마는 커머스API 버전에 따라 달라질 수 있어 상단 상수로 모아 두었다.
 *    실제 배포 전 공식 문서(apicenter.commerce.naver.com)로 확정할 것.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NaverCommerceClient {

    // ── 엔드포인트 경로(공식 문서로 확정 필요) ─────────────────────────────────
    private static final String PATH_PRODUCTS = "/v2/products";
    private static final String PATH_ORIGIN_PRODUCT = "/v2/products/origin-products/"; // + {originProductNo}
    private static final String PATH_IMAGE_UPLOAD = "/v1/product-images/upload";
    private static final String PATH_ADDRESS_BOOKS = "/v1/seller/addressbooks";
    private static final String PATH_CATEGORIES = "/v1/categories";

    private final NaverProperties props;
    private final NaverAuthService authService;
    private final ObjectMapper objectMapper;

    private RestClient restClient;

    private RestClient client() {
        if (restClient == null) {
            restClient = RestClient.builder().baseUrl(props.getBaseUrl()).build();
        }
        return restClient;
    }

    /** 등록된 출고지/반품지 주소록 조회. 관리자가 shipping/return addressId 를 확인하는 용도. */
    public JsonNode getAddressBooks() {
        return authorizedJson(HttpMethod.GET, PATH_ADDRESS_BOOKS, null);
    }

    /** 전체 카테고리 조회(리프 카테고리 매핑 UI 용). */
    public JsonNode getCategories() {
        return authorizedJson(HttpMethod.GET, PATH_CATEGORIES, null);
    }

    /** 상품 신규 등록. 성공 시 originProductNo/channelProductNo 등을 담은 응답을 반환. */
    public JsonNode createProduct(JsonNode payload) {
        return authorizedJson(HttpMethod.POST, PATH_PRODUCTS, payload);
    }

    /** 상품 전체 수정(원상품 기준). */
    public JsonNode updateProduct(long originProductNo, JsonNode payload) {
        return authorizedJson(HttpMethod.PUT, PATH_ORIGIN_PRODUCT + originProductNo, payload);
    }

    /** 상품 상태 변경(예: SUSPENSION = 판매중지). 삭제 대신 판매중지 처리에 사용. */
    public JsonNode changeProductStatus(long originProductNo, String statusType) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("statusType", statusType);
        return authorizedJson(HttpMethod.PUT, PATH_ORIGIN_PRODUCT + originProductNo + "/change-status", body);
    }

    /**
     * 이미지 여러 장을 네이버에 업로드하고 CDN URL 목록을 반환한다.
     * 네이버 상품 payload 에는 여기서 받은 URL 만 사용할 수 있다(외부 URL 직접 불가).
     */
    public List<String> uploadImages(List<ImagePart> parts) {
        if (parts == null || parts.isEmpty()) {
            return new ArrayList<>();
        }
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        for (ImagePart part : parts) {
            builder.part("imageFiles", new ByteArrayResource(part.bytes()) {
                @Override
                public String getFilename() {
                    return part.filename();
                }
            }).contentType(part.mediaType());
        }
        Object multipartBody = builder.build();

        JsonNode resp;
        try {
            resp = postMultipart(multipartBody, authService.getAccessToken());
        } catch (HttpClientErrorException.Unauthorized e) {
            authService.invalidate();
            resp = postMultipart(multipartBody, authService.getAccessToken());
        } catch (HttpClientErrorException e) {
            throw new ChannelApiException("네이버 이미지 업로드 실패 " + e.getStatusCode() + ": " + e.getResponseBodyAsString(), e);
        }

        List<String> urls = new ArrayList<>();
        if (resp != null && resp.has("images")) {
            for (JsonNode img : resp.get("images")) {
                if (img.hasNonNull("url")) {
                    urls.add(img.get("url").asText());
                }
            }
        }
        if (urls.isEmpty()) {
            throw new ChannelApiException("네이버 이미지 업로드 응답에 URL 이 없습니다: " + resp);
        }
        return urls;
    }

    private JsonNode postMultipart(Object multipartBody, String token) {
        return client().post()
                .uri(PATH_IMAGE_UPLOAD)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(multipartBody)
                .retrieve()
                .body(JsonNode.class);
    }

    private JsonNode authorizedJson(HttpMethod method, String path, JsonNode body) {
        try {
            return doJson(method, path, body, authService.getAccessToken());
        } catch (HttpClientErrorException.Unauthorized e) {
            authService.invalidate();
            return doJson(method, path, body, authService.getAccessToken());
        } catch (HttpClientErrorException e) {
            throw new ChannelApiException("네이버 API 오류 " + e.getStatusCode() + ": " + e.getResponseBodyAsString(), e);
        }
    }

    private JsonNode doJson(HttpMethod method, String path, JsonNode body, String token) {
        RestClient.RequestBodySpec spec = client().method(method)
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.retrieve().body(JsonNode.class);
    }
}
