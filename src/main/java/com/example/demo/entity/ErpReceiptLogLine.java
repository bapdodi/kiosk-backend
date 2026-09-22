package com.example.demo.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "erp_receipt_log_line")
@Getter
@Setter
@NoArgsConstructor
public class ErpReceiptLogLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "log_id")
    @JsonIgnore
    private ErpReceiptLog log;

    private Integer editNo;
    private String itemCode;
    private String itemName;
    private Integer ea;
    private Integer price;
    private Long gum;
    private Long vat;
}
