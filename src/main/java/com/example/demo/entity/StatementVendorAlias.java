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

/** 명세서의 공급자 사업자번호(숫자만)와 사람이 확정한 ERP 거래처의 짝. */
@Entity
@Table(name = "statement_vendor_alias")
@Getter
@Setter
@NoArgsConstructor
public class StatementVendorAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String bizNo;
    private Integer vendorCode;
    private Integer hits;
    private LocalDateTime updatedAt;
}
