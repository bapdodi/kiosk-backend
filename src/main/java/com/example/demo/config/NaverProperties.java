package com.example.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * 네이버 커머스API 연동 설정. application.yml 의 `naver.commerce.*` 값(대부분 .env 로 주입)을 바인딩한다.
 *
 * clientId/clientSecret 은 커머스API센터에서 발급받은 값이고,
 * shippingAddressId/returnAddressId 는 스마트스토어센터에 미리 등록한 출고지/반품지 주소록 no 다.
 * (백엔드 {@code GET /api/naver/admin/address-books} 로 조회해 채울 수 있다.)
 */
@Component
@ConfigurationProperties(prefix = "naver.commerce")
@Getter
@Setter
public class NaverProperties {

    /** 커머스API 애플리케이션 ID */
    private String clientId;

    /** 커머스API 애플리케이션 시크릿(bcrypt salt 형식). 서명 생성에 사용 */
    private String clientSecret;

    /** 커머스API base URL. 기본 https://api.commerce.naver.com/external */
    private String baseUrl = "https://api.commerce.naver.com/external";

    /** 출고지(배송지) 주소록 no. 미설정 시 빈 문자열 주입에도 기동되도록 String 으로 받는다. */
    private String shippingAddressId;

    /** 반품/교환지 주소록 no */
    private String returnAddressId;

    /** A/S 전화번호 */
    private String asTelephone;

    /** A/S 안내 문구 */
    private String asGuideContent = "구매하신 매장으로 문의 바랍니다.";

    /** 원산지 코드(네이버 원산지 코드 체계). 예: 국산 코드 */
    private String originAreaCode;

    /** 기본 배송비(원) */
    private Integer deliveryBaseFee = 3000;

    /** 반품 배송비(원) */
    private Integer returnDeliveryFee = 3000;

    /** 교환 배송비(원) */
    private Integer exchangeDeliveryFee = 6000;

    /** 설정이 모두 채워져 실제 호출이 가능한 상태인지 */
    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank();
    }

    public Long getShippingAddressIdAsLong() {
        return parseLongOrNull(shippingAddressId);
    }

    public Long getReturnAddressIdAsLong() {
        return parseLongOrNull(returnAddressId);
    }

    private static Long parseLongOrNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
