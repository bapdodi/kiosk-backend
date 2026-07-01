package com.example.demo.service.channel;

import java.util.List;

/**
 * 채널 커넥터가 등록/수정 후 돌려주는 결과. 상위 오케스트레이션이 이 값을 링크 엔티티에 반영한다.
 *
 * @param originProductNo   채널의 원상품 번호(네이버 originProductNo 등)
 * @param channelProductNo  채널 상품 번호(nullable)
 * @param status            채널 상태(SALE/SUSPENSION 등)
 * @param channelImageUrls  채널 CDN 에 업로드된 이미지 URL(재고/가격 동기화 시 재사용)
 */
public record ConnectorResult(Long originProductNo, Long channelProductNo, String status, List<String> channelImageUrls) {
}
