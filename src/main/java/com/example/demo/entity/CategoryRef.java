package com.example.demo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 상품이 소속된 하나의 (대분류 + 중분류) 쌍.
 * 상품은 여러 개의 CategoryRef 를 가질 수 있어 여러 카테고리에 동시에 노출된다.
 * 카테고리 식별자는 기존 설계와 동일하게 {@link Category#getId()} 문자열을 그대로 저장한다.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class CategoryRef {

    @Column(name = "main_category")
    private String mainCategory;

    @Column(name = "sub_category")
    private String subCategory;
}
