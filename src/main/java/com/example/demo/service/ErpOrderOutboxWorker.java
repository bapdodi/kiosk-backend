package com.example.demo.service;

import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Value;
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

    /**
     * 재시도 상한. 품목 코드 오류처럼 재시도로 풀리지 않는 건이 5초마다 영원히 재시도되며
     * 조회 창(10건)을 차지해, 뒤따르는 정상 주문의 전송까지 막는 것을 방지한다.
     * 상한에 걸린 건은 processedAt 이 비어 있는 채로 남아 조회에서만 빠지므로,
     * 원인을 고친 뒤 attempts 를 0 으로 되돌리면 다시 전송된다.
     */
    @Value("${erp.order-outbox.max-attempts:20}")
    private int maxAttempts;

    @Scheduled(fixedDelayString = "${erp.order-outbox.delay-ms:5000}")
    @Transactional
    public void deliverPendingOrders() {
        for (ErpOrderOutbox message : outboxRepository
                .findTop10ByProcessedAtIsNullAndAttemptsLessThanOrderByCreatedAtAsc(maxAttempts)) {
            Order order = orderRepository.findById(message.getOrderId()).orElse(null);
            if (order == null) {
                recordFailure(message, "Order no longer exists");
                continue;
            }
            try {
                erpSyncService.sendOrderToErp(order);
                message.setProcessedAt(LocalDateTime.now());
                message.setLastError(null);
            } catch (Exception e) {
                String text = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                recordFailure(message, text);
                log.error("ERP delivery for order {} failed; it will be retried", order.getId(), e);
            }
        }
    }

    private void recordFailure(ErpOrderOutbox message, String error) {
        int attempts = message.getAttempts() + 1;
        message.setAttempts(attempts);
        message.setLastError(error.substring(0, Math.min(error.length(), 255)));
        if (attempts >= maxAttempts) {
            log.error("ERP delivery for order {} gave up after {} attempts; manual action required: {}",
                    message.getOrderId(), attempts, message.getLastError());
        }
    }
}
