package com.example.demo.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "erp_order_outbox")
@Getter
@Setter
@NoArgsConstructor
public class ErpOrderOutbox {
    @Id
    private Long orderId;
    private int attempts;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime processedAt;

    public ErpOrderOutbox(Long orderId) {
        this.orderId = orderId;
        this.createdAt = LocalDateTime.now();
    }
}
