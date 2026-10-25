package com.example.demo.entity;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 금전출납부 한 줄. 잔고 컬럼은 없다 - 조회 쪽이 일자·id 순으로 누적해 계산한다. */
@Entity
@Table(name = "cash_ledger_entry")
@Getter
@Setter
@NoArgsConstructor
public class CashLedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate entryDate;

    /** 적요 */
    @Column(nullable = false, length = 200)
    private String memo = "";

    @Column(nullable = false)
    private long cashIn;

    @Column(nullable = false)
    private long cardIn;

    @Column(nullable = false)
    private long expense;

    /** 기타 */
    @Column(nullable = false, length = 200)
    private String etc = "";
}
