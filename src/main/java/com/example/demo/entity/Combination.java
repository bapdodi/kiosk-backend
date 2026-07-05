package com.example.demo.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "combinations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Combination {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id_db; // database id

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    @JsonIgnore
    private Product product;

    private String id; // frontend id
    private String name;
    private Integer priceC;  // ERP OUTC(C단가) = 실제 판매가 (DB 컬럼 price_c)
    private Integer priceA;  // ERP OUTA(A단가) — 저장만, 추후 활용 대비
    private Integer priceB;  // ERP OUTB(B단가) — 저장만, 추후 활용 대비
    private String erpCode;
    private Integer stock;
    private Integer sortOrder;

    /**
     * 소프트삭제 플래그. true 면 화면(키오스크/관리자)에서 숨기되 postgres 에는 그대로 보존한다.
     * 실제 row 를 지우지 않으므로 관리자 폼에서 복구할 수 있고, ERP 재동기화로도 되살아나지 않는다.
     */
    @jakarta.persistence.Column(nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private Boolean deleted = false;
}
