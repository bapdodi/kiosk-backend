package com.example.demo.service.channel;

import java.util.List;

import com.example.demo.entity.Product;

/**
 * 판매채널 커넥터. 채널별(네이버/쿠팡/…) 구현체가 이 인터페이스를 구현하고 스프링 빈으로 등록되면,
 * {@code ChannelSyncService} 가 채널명으로 찾아 위임한다. 새 채널 추가 = 이 인터페이스 구현체 1개.
 *
 * DB(링크/스냅샷) 는 상위 오케스트레이션이 관리하므로, 커넥터는 채널 API 호출과 채널 고유 매핑에만 집중한다.
 */
public interface SalesChannelConnector {

    /** 채널 식별자(대문자). 예: "NAVER", "COUPANG". */
    String channel();

    /** 자격증명 등 설정이 채워져 실제 호출 가능한 상태인지. */
    boolean isConfigured();

    /**
     * 신규 등록 또는 이미지 재업로드를 포함한 전체 수정.
     * @param existingOriginProductNo null 이면 신규 등록, 값이 있으면 수정
     */
    ConnectorResult register(Product product, List<ImagePart> images, Long existingOriginProductNo);

    /** 저장된 채널 이미지 URL 을 재사용해 상품명/가격/재고만 반영(이미지 재업로드 없음). */
    ConnectorResult update(Product product, List<String> reusableImageUrls, long originProductNo);

    /** 판매중지로 전환. */
    void suspend(long originProductNo);
}
