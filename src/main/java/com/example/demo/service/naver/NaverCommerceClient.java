package com.example.demo.service.naver;

import java.util.ArrayList;
import java.util.List;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import com.example.demo.config.NaverProperties;
import com.example.demo.service.channel.ChannelApiException;
import com.example.demo.service.channel.ImagePart;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
    private static final String PATH_ADDRESS_BOOKS = "/v1/seller/addressbooks-for-page";
    private static final String PATH_CATEGORIES = "/v1/categories";
    private static final String PATH_PRODUCT_SEARCH = "/v1/products/search";
    private static final String PATH_CHANNEL_PRODUCT = "/v2/products/channel-products/"; // + {channelProductNo}
    private static final String PATH_ORIGIN_PRODUCT_V1_CHANGE_STATUS =
            "/v1/products/origin-products/%d/change-status";
    // 판매(주문) 조회
    private static final String PATH_ORDER_LAST_CHANGED = "/v1/pay-order/seller/product-orders/last-changed-statuses";
    private static final String PATH_ORDER_QUERY = "/v1/pay-order/seller/product-orders/query";
    /** 등록불가 sellerTags 제거 후 재시도 최대 횟수. */
    private static final int MAX_TAG_RETRY = 3;

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
        return authorizedJson(HttpMethod.GET, PATH_ADDRESS_BOOKS + "?page=1", null);
    }

    /** 전체 카테고리 조회(리프 카테고리 매핑 UI 용). */
    public JsonNode getCategories() {
        return authorizedJson(HttpMethod.GET, PATH_CATEGORIES, null);
    }

    /** 판매자 채널상품 목록 조회(페이지). body 예: {"page":1,"size":50}. */
    public JsonNode searchProducts(JsonNode body) {
        return authorizedJson(HttpMethod.POST, PATH_PRODUCT_SEARCH, body);
    }

    /** 채널상품 단건 상세 조회(카테고리/상품정보제공고시 등 확인용). */
    public JsonNode getChannelProduct(long channelProductNo) {
        return authorizedJson(HttpMethod.GET, PATH_CHANNEL_PRODUCT + channelProductNo, null);
    }

    /** 상품 신규 등록. 성공 시 originProductNo/channelProductNo 등을 담은 응답을 반환. */
    public JsonNode createProduct(JsonNode payload) {
        return submitProduct(HttpMethod.POST, PATH_PRODUCTS, payload);
    }

    /** 상품 전체 수정(원상품 기준). */
    public JsonNode updateProduct(long originProductNo, JsonNode payload) {
        return submitProduct(HttpMethod.PUT, PATH_ORIGIN_PRODUCT + originProductNo, payload);
    }

    /**
     * 상품 등록/수정 전송. 네이버가 sellerTags 중 '등록불가 단어'를 이유로 400(Restricted.sellerTags)을
     * 내면 요청 전체가 거부되므로, 문제된 태그만 제거하고 재시도한다(최대 {@value #MAX_TAG_RETRY}회).
     * 자동 생성 태그가 상품 등록 자체를 깨뜨리지 않도록 하는 안전장치.
     */
    private JsonNode submitProduct(HttpMethod method, String path, JsonNode payload) {
        for (int attempt = 0; attempt <= MAX_TAG_RETRY; attempt++) {
            try {
                return authorizedJson(method, path, payload);
            } catch (ChannelApiException e) {
                java.util.Set<String> restricted = extractRestrictedTags(e.getMessage());
                if (restricted.isEmpty() || attempt == MAX_TAG_RETRY || !stripSellerTags(payload, restricted)) {
                    throw e;
                }
                log.warn("네이버 등록불가 태그 제거 후 재시도({}): {}", attempt + 1, restricted);
            }
        }
        throw new ChannelApiException("네이버 상품 전송 재시도 한도 초과");
    }

    /** ChannelApiException 메시지(응답 본문 포함)에서 Restricted.sellerTags 로 지목된 단어들을 추출. */
    public java.util.Set<String> extractRestrictedTags(String message) {
        java.util.Set<String> words = new java.util.LinkedHashSet<>();
        if (message == null) {
            return words;
        }
        int brace = message.indexOf('{');
        if (brace < 0) {
            return words;
        }
        try {
            JsonNode body = objectMapper.readTree(message.substring(brace));
            for (JsonNode inv : body.path("invalidInputs")) {
                String name = inv.path("name").asText("");
                String type = inv.path("type").asText("");
                if (!name.contains("sellerTags") && !type.contains("sellerTags")) {
                    continue;
                }
                java.util.regex.Matcher m =
                        java.util.regex.Pattern.compile("\\(([^)]+)\\)").matcher(inv.path("message").asText(""));
                if (m.find()) {
                    for (String w : m.group(1).split(",")) {
                        String t = w.trim();
                        if (!t.isEmpty()) {
                            words.add(t);
                        }
                    }
                }
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignore) {
            // 메시지가 JSON 이 아니면 재시도 불가 → 빈 집합 반환
        }
        return words;
    }

    /** payload 의 originProduct.detailAttribute.seoInfo.sellerTags 에서 지정 단어들을 제거. 제거된 게 있으면 true. */
    public boolean stripSellerTags(JsonNode payload, java.util.Set<String> restricted) {
        JsonNode seo = payload.path("originProduct").path("detailAttribute").path("seoInfo");
        if (!seo.has("sellerTags") || !seo.get("sellerTags").isArray()) {
            return false;
        }
        ArrayNode tags = (ArrayNode) seo.get("sellerTags");
        ArrayNode kept = objectMapper.createArrayNode();
        boolean removed = false;
        for (JsonNode t : tags) {
            if (restricted.contains(t.path("text").asText())) {
                removed = true;
            } else {
                kept.add(t);
            }
        }
        if (removed) {
            ((ObjectNode) seo).set("sellerTags", kept);
        }
        return removed;
    }

    /** 상품 상태 변경(예: SUSPENSION = 판매중지). 삭제 대신 판매중지 처리에 사용. 경로는 v1. */
    public JsonNode changeProductStatus(long originProductNo, String statusType) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("statusType", statusType);
        return authorizedJson(HttpMethod.PUT,
                PATH_ORIGIN_PRODUCT_V1_CHANGE_STATUS.formatted(originProductNo), body);
    }

    /** 채널상품 삭제(비가역). 잘못 등록/테스트 상품 회수에 사용. */
    public JsonNode deleteChannelProduct(long channelProductNo) {
        return authorizedJson(HttpMethod.DELETE, PATH_CHANNEL_PRODUCT + channelProductNo, null);
    }

    /**
     * 변경된 상품주문 내역 조회(판매 발생 감지용). 조회 범위는 네이버 제한상 최대 24시간.
     * @param fromIso  조회 시작시각(ISO8601, 예: 2026-07-04T00:00:00.000+09:00)
     * @param lastChangedType 상태 필터(예: "PAYED"). null 이면 전체 변경.
     */
    public JsonNode getLastChangedOrders(String fromIso, String lastChangedType) {
        StringBuilder path = new StringBuilder(PATH_ORDER_LAST_CHANGED)
                .append("?lastChangedFrom=")
                .append(java.net.URLEncoder.encode(fromIso, java.nio.charset.StandardCharsets.UTF_8));
        if (lastChangedType != null && !lastChangedType.isBlank()) {
            path.append("&lastChangedType=").append(lastChangedType);
        }
        return authorizedJson(HttpMethod.GET, path.toString(), null);
    }

    /** 상품주문 상세 조회(상품명/수량/결제금액 등). productOrderId 목록으로 조회. */
    public JsonNode queryProductOrders(List<String> productOrderIds) {
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode arr = body.putArray("productOrderIds");
        for (String id : productOrderIds) {
            arr.add(id);
        }
        return authorizedJson(HttpMethod.POST, PATH_ORDER_QUERY, body);
    }

    /**
     * 이미지 여러 장을 네이버에 업로드하고 CDN URL 목록을 반환한다.
     * 네이버 상품 payload 에는 여기서 받은 URL 만 사용할 수 있다(외부 URL 직접 불가).
     */
    public List<String> uploadImages(List<ImagePart> parts) {
        if (parts == null || parts.isEmpty()) {
            return new ArrayList<>();
        }
        // 블로킹 RestClient 용 멀티파트 본문. MultipartBodyBuilder 는 reactive-streams(Publisher)
        // 클래스를 요구하므로(런타임 미포함) 사용하지 않고 MultiValueMap<String, HttpEntity> 로 직접 구성한다.
        MultiValueMap<String, HttpEntity<?>> multipartBody = new LinkedMultiValueMap<>();
        for (ImagePart part : parts) {
            ByteArrayResource resource = new ByteArrayResource(part.bytes()) {
                @Override
                public String getFilename() {
                    return part.filename();
                }
            };
            HttpHeaders partHeaders = new HttpHeaders();
            partHeaders.setContentType(part.mediaType());
            multipartBody.add("imageFiles", new HttpEntity<>(resource, partHeaders));
        }

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
