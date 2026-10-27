package com.example.demo.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 거래처별로 "명세서에 이렇게 적힌 품명 = 이 ERP 품목"이라고 사람이 확정한 짝. hits 가 클수록 믿는다. */
@Entity
@Table(name = "statement_item_alias")
@Getter
@Setter
@NoArgsConstructor
public class StatementItemAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Integer vendorCode;

    /** 품명 토큰을 정렬해 공백으로 붙인 값. ErpStatementService.nameKey 참고. */
    private String nameKey;

    private Integer itemCode;
    private Integer hits;
    private LocalDateTime updatedAt;
}
