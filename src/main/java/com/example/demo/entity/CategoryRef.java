package com.example.demo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상품이 소속된 하나의 (대분류 + 중분류) 쌍.
 * 상품은 여러 개의 CategoryRef 를 가질 수 있어 여러 카테고리에 동시에 노출된다.
 * 카테고리 식별자는 기존 설계와 동일하게 {@link Category#getId()} 문자열을 그대로 저장한다.
 */
@Embeddable
@Getter
@NoArgsConstructor
@EqualsAndHashCode
public class CategoryRef {

    @Column(name = "main_category")
    private String mainCategory;

    @Column(name = "sub_category")
    private String subCategory;

    public CategoryRef(String mainCategory, String subCategory) {
        setMainCategory(mainCategory);
        setSubCategory(subCategory);
    }

    // 관리자 화면은 "중분류 없음"을 빈 문자열로 보낸다. categories FK 는 '' 를 없는 카테고리로
    // 보고 저장을 거부하므로 null 로 바꿔 둔다.
    public void setMainCategory(String mainCategory) {
        this.mainCategory = blankToNull(mainCategory);
    }

    public void setSubCategory(String subCategory) {
        this.subCategory = blankToNull(subCategory);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
