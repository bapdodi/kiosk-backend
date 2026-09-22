package com.example.demo.entity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 관리자 화면에서 넣은 ERP 매입전표(IL&lt;yy&gt; KIND=4)의 로컬 이력.
 * ERP 가 진실의 원천이고 이 표는 보조 기록이다(두 DB 가 한 트랜잭션으로 묶이지 않는다).
 * 대사(reconcile)는 ERP 의 KIOSK_RECEIPT_VOUCHER 를 기준으로 한다.
 */
@Entity
@Table(name = "erp_receipt_log")
@Getter
@Setter
@NoArgsConstructor
public class ErpReceiptLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 프런트가 폼 1회당 하나 발급하는 UUID. ERP 멱등키와 같은 값이다. */
    @Column(unique = true, nullable = false)
    private String requestId;

    /** IL26 같은 실제 기록 테이블명. 연도가 바뀌어도 어디에 넣었는지 남는다. */
    private String ilTable;

    /** ERP 표기 그대로 'yy.MM.dd'. */
    private String erpDate;

    private Integer voucherNo;

    private String vendorCode;
    private String vendorName;

    private Long totalAmount;
    private Integer lineCount;
    private String memo;

    private String createdBy;
    private LocalDateTime createdAt;

    /** CREATED | CANCELLED | FAILED */
    private String status;

    @Column(length = 500)
    private String lastError;

    private LocalDateTime cancelledAt;
    private String cancelledBy;

    @OneToMany(mappedBy = "log", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ErpReceiptLogLine> lines = new ArrayList<>();

    public void addLine(ErpReceiptLogLine line) {
        line.setLog(this);
        this.lines.add(line);
    }
}
