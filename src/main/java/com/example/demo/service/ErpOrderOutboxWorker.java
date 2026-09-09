package com.example.demo.service;

import java.time.LocalDateTime;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.entity.ErpOrderOutbox;
import com.example.demo.entity.Order;
import com.example.demo.repository.ErpOrderOutboxRepository;
import com.example.demo.repository.OrderRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class ErpOrderOutboxWorker {
    private final ErpOrderOutboxRepository outboxRepository;
    private final OrderRepository orderRepository;
    private final ErpSyncService erpSyncService;

    @Scheduled(fixedDelayString = "${erp.order-outbox.delay-ms:5000}")
    @Transactional
    public void deliverPendingOrders() {
        for (ErpOrderOutbox message : outboxRepository.findTop10ByProcessedAtIsNullOrderByCreatedAtAsc()) {
            Order order = orderRepository.findById(message.getOrderId()).orElse(null);
            if (order == null) {
                message.setAttempts(message.getAttempts() + 1);
                message.setLastError("Order no longer exists");
                continue;
            }
            try {
                erpSyncService.sendOrderToErp(order);
                message.setProcessedAt(LocalDateTime.now());
                message.setLastError(null);
            } catch (Exception e) {
                message.setAttempts(message.getAttempts() + 1);
                String text = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                message.setLastError(text.substring(0, Math.min(text.length(), 255)));
                log.error("ERP delivery for order {} failed; it will be retried", order.getId(), e);
            }
        }
    }
}
