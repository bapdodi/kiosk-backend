package com.example.demo.entity;

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
 * 키오스크 카테고리(대분류+중분류) → 채널 리프 카테고리 매핑. 채널별로 별도 매핑을 가진다.
 *
 * 대부분의 오픈마켓은 리프(최하위) 카테고리 ID 로만 상품을 등록할 수 있어, 채널마다 매핑이 필요하다.
 * subCategory 는 null 일 수 있다(대분류만으로 매핑).
 */
@Entity
@Table(name = "channel_category_mappings", uniqueConstraints = @UniqueConstraint(columnNames = { "channel", "kioskMainCategory", "kioskSubCategory" }))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChannelCategoryMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 판매채널 식별자(대문자): NAVER / COUPANG ... */
    @Column(nullable = false)
    private String channel;

    @Column(nullable = false)
    private String kioskMainCategory;

    private String kioskSubCategory;

    /** 채널의 리프 카테고리 ID */
    @Column(nullable = false)
    private Long naverLeafCategoryId;

    /** 표시용 채널 카테고리 전체 경로명 */
    private String naverCategoryName;
}
