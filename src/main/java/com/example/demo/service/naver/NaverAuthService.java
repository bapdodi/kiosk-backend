package com.example.demo.service.naver;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import com.example.demo.config.NaverProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 네이버 커머스API OAuth2(client_credentials) 토큰 발급/캐시.
 *
 * 서명(client_secret_sign) 생성 절차 — 네이버 공식:
 *  1. timestamp = 현재시간(ms)
 *  2. password = "{clientId}_{timestamp}"
 *  3. hashed = bcrypt(password, salt = clientSecret)   // clientSecret 이 bcrypt salt 형식
 *  4. sign = base64UrlEncode(hashed)
 *
 * 토큰 수명은 약 3시간. 만료 60초 전까지 캐시를 재사용한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NaverAuthService {

    private final NaverProperties props;
    private final RestClient restClient = RestClient.create();

    private volatile String cachedToken;
    private volatile long expiresAtMillis;

    /** 캐시된(또는 새로 발급한) 액세스 토큰을 반환한다. */
    public synchronized String getAccessToken() {
        long now = System.currentTimeMillis();
        if (cachedToken != null && now < expiresAtMillis - 60_000L) {
            return cachedToken;
        }
        if (!props.isConfigured()) {
            throw new IllegalStateException("네이버 커머스API 자격증명이 설정되지 않았습니다. (.env 의 NAVER_COMMERCE_CLIENT_ID/SECRET)");
        }

        long timestamp = now;
        String sign = createSignature(props.getClientId(), props.getClientSecret(), timestamp);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", props.getClientId());
        form.add("timestamp", String.valueOf(timestamp));
        form.add("client_secret_sign", sign);
        form.add("grant_type", "client_credentials");
        form.add("type", "SELF");

        TokenResponse resp = restClient.post()
                .uri(props.getBaseUrl() + "/v1/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse.class);

        if (resp == null || resp.accessToken == null) {
            throw new IllegalStateException("네이버 토큰 발급 응답이 비어 있습니다.");
        }
        cachedToken = resp.accessToken;
        expiresAtMillis = now + resp.expiresIn * 1000L;
        log.info("네이버 커머스API 토큰 발급 완료 (만료 {}초)", resp.expiresIn);
        return cachedToken;
    }

    /** 401 등으로 토큰이 무효화됐을 때 캐시를 비운다. */
    public synchronized void invalidate() {
        cachedToken = null;
        expiresAtMillis = 0L;
    }

    /**
     * client_secret_sign 생성. 단위 테스트에서 검증 가능하도록 static 으로 노출.
     */
    public static String createSignature(String clientId, String clientSecret, long timestamp) {
        String password = clientId + "_" + timestamp;
        String hashed = BCrypt.hashpw(password, clientSecret);
        return Base64.getUrlEncoder().encodeToString(hashed.getBytes(StandardCharsets.UTF_8));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class TokenResponse {
        @JsonProperty("access_token")
        String accessToken;
        @JsonProperty("expires_in")
        long expiresIn;
        @JsonProperty("token_type")
        String tokenType;
    }
}
