package com.example.demo.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "order_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String erpCode; // For syncing back to ERP
    private String selectedOption;
    private Integer finalPrice;
    /**
     * ERP 실청구가. 거래처 DANGA(A/B/C단가)를 반영해 ERP에 실제 등록된 단가.
     * ERP 전송(sendOrderToErp) 시 채워지며, null이면 표시는 finalPrice(소비자가)로 대체한다.
     */
    private Integer chargedPrice;
    private Integer quantity; // Added quantity if missing
}
