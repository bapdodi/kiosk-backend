package com.example.demo.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * ERP GURAE(거래처)의 로컬 사본.
 *
 * 주문이 만들어지는 시점에 거래처 단가를 고르려면 DANGA 가 필요한데, 그때마다 ERP 를 조회하면
 * 손님 화면이 사내 MSSQL 에 묶인다. ERP 동기화 때 같이 복사해 두고 주문 생성은 이 사본만 본다.
 */
@Entity
@Table(name = "customers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Customer {

    /** ERP GURAE.CODE. 주문의 erpCustomerCode 와 같은 값이다. */
    @Id
    @Column(name = "erp_code", length = 32)
    private String erpCode;

    private String name;

    /** ERP GURAE.KIND. '1' 이 판매 거래처다. */
    @Column(length = 8)
    private String kind;

    /** ERP GURAE.DANGA. 2=OUTA, 3=OUTB, 4=OUTC. 1 은 매입(입고) 거래처라 판매 단가표가 없다. */
    private Integer danga;

    private LocalDateTime syncedAt;
}
