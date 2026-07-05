package com.example.demo.entity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column
    private String erpCode;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * 상품이 소속된 카테고리 목록. 하나의 상품이 여러 (대분류+중분류) 쌍에 동시에 들어갈 수 있다.
     * 중복 노출을 막기 위해 Set 으로 관리한다.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "product_categories", joinColumns = @JoinColumn(name = "product_id"))
    @org.hibernate.annotations.BatchSize(size = 500)
    @Builder.Default
    private Set<CategoryRef> categories = new LinkedHashSet<>();

    /**
     * 키오스크·네이버·주문에 실제 사용되는 판매가. ERP OUTC(C단가) 값이 들어온다.
     * DB 컬럼명은 price_c. (A/B단가는 priceA/priceB 에 별도 저장)
     */
    @Column(nullable = false)
    private Integer priceC;

    /**
     * ERP OUTA(A단가). DANGA=2 거래처용. 동기화 시 저장만 해두고 표시/청구엔 사용하지 않는다(추후 활용 대비).
     */
    @Column
    private Integer priceA;

    /**
     * ERP OUTB(B단가). DANGA=3 거래처용. 동기화 시 저장만 해두고 표시/청구엔 사용하지 않는다(추후 활용 대비).
     */
    @Column
    private Integer priceB;

    /**
     * ERP 규격(GYU). 단일 규격(단순상품)의 규격을 보존하기 위한 필드.
     * 복합옵션 상품은 규격이 각 combination.name 에 담기므로 보통 null 이다.
     */
    @Column
    private String gyu;

    /**
     * 원산지 코드(네이버 원산지 코드 체계). 상품마다 다르므로 상품 단위로 보관한다.
     * 비어 있으면 매핑 시 .env 의 NAVER_ORIGIN_AREA_CODE 기본값으로 폴백한다.
     */
    @Column
    private String originAreaCode;

    /**
     * 검색품질용 브랜드명. 상품마다 브랜드가 달라 개별 지정한다(프론트에서 입력).
     * 지정되면 네이버 매핑 시 brandName/manufacturerName 에 우선 적용되고,
     * 비어 있으면 상품명 자동검출 → .env 기본값("기타") 순으로 폴백한다.
     */
    @Column
    private String brandName;

    @Column(nullable = false, columnDefinition = "INT DEFAULT 0")
    @Builder.Default
    private Integer stock = 0;

    @ElementCollection
    @CollectionTable(name = "product_hashtags", joinColumns = @JoinColumn(name = "product_id"))
    @org.hibernate.annotations.BatchSize(size = 500)
    private List<String> hashtags;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "product_images", joinColumns = @JoinColumn(name = "product_id"))
    @Column(name = "image_url")
    @org.hibernate.annotations.BatchSize(size = 500)
    @Builder.Default
    private List<String> images = new ArrayList<>();

    /**
     * 옵션값 단위로 연결되는 사진들. 모달에서 선택된 옵션값에 맞는 사진을 보여주고,
     * 해당 값에 사진이 없으면 메인 images 로 폴백한다.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "product_option_images", joinColumns = @JoinColumn(name = "product_id"))
    @org.hibernate.annotations.BatchSize(size = 500)
    @Builder.Default
    private List<OptionImage> optionImages = new ArrayList<>();

    @Column(columnDefinition = "boolean default false")
    @Builder.Default
    private Boolean isCategoryModified = false;

    private Boolean isComplexOptions;

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @jakarta.persistence.OrderBy("sortOrder ASC")
    @org.hibernate.annotations.BatchSize(size = 500)
    private List<OptionGroup> optionGroups;

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @jakarta.persistence.OrderBy("sortOrder ASC")
    @org.hibernate.annotations.BatchSize(size = 500)
    private List<Combination> combinations;

    @Column(nullable = false, length = 255)
    @Builder.Default
    private String sortOrder = "80000000";
}
