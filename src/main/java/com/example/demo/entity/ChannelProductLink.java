package com.example.demo.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 키오스크 상품 ↔ 외부 판매채널(네이버/쿠팡 등) 상품 연결 + 마지막 동기화 스냅샷.
 *
 * (productId, channel) 이 유니크하므로 한 상품이 여러 채널에 각각 링크될 수 있다.
 * lastSynced* 는 "무엇이 바뀌었나" 미리보기(diff)의 기준선이다.
 */
@Entity
@Table(name = "channel_product_links", uniqueConstraints = @UniqueConstraint(columnNames = { "productId", "channel" }))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChannelProductLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 키오스크 products.id */
    @Column(nullable = false)
    private Long productId;

    /** 판매채널 식별자(대문자): NAVER / COUPANG ... */
    @Column(nullable = false)
    private String channel;

    /** 채널 원상품 번호(네이버 originProductNo 등) */
    private Long originProductNo;

    /** 채널 상품 번호(네이버 channelProductNo 등) */
    private Long channelProductNo;

    /** 채널 상품 상태(SALE / SUSPENSION ...) */
    private String naverStatus;

    // ── 마지막으로 채널에 반영한 값(diff 기준선) ──────────────────────────────
    private Integer lastSyncedPrice;
    private Integer lastSyncedStock;
    private String lastSyncedName;

    /** 채널 CDN 이미지 URL 목록(JSON 배열 문자열). 재고/가격만 바뀐 동기화 때 재업로드 없이 재사용. */
    @Column(columnDefinition = "TEXT")
    private String channelImageUrls;

    private LocalDateTime lastSyncedAt;

    /** 마지막 전송/동기화 실패 사유(성공 시 null 로 클리어) */
    @Column(columnDefinition = "TEXT")
    private String lastError;
}
