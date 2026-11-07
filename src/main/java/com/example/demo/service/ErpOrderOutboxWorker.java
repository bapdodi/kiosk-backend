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

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class ErpOrderOutboxWorker {
    /** 완료 후 이만큼 지나도 ERP 로 못 나갔으면 막힌 것으로 본다. 정상이면 5초 안에 나간다. */
    private static final long STUCK_AFTER_MINUTES = 10;

    private final ErpOrderOutboxRepository outboxRepository;
    private final OrderRepository orderRepository;
    private final ErpOrderSender erpOrderSender;
    private final MeterRegistry meterRegistry;

    /**
     * 재시도 상한. 기본 17280 회 = 5초 간격으로 하루.
     *
     * 예전 상한 20 회(약 100초)는 MSSQL 재시작·장애 전환·백업 잠금 한 번에 다 써 버려,
     * 그사이 완료된 주문이 ERP 에 영영 들어가지 않았다. 재시도로 풀리지 않는 건(품목 코드 오류 등)은
     * 시도 횟수가 많은 순으로 뒤로 밀려 새 주문의 전송을 막지 않는다.
     * 상한에 걸린 건은 processedAt 이 비어 있는 채로 남아 조회에서만 빠지므로,
     * 원인을 고친 뒤 attempts 를 0 으로 되돌리면 다시 전송된다(장애 전환 대조 스크립트도 이렇게 재전송한다).
     */
    @Value("${erp.order-outbox.max-attempts:17280}")
    private int maxAttempts;

    /** Prometheus 에서 kiosk_erp_outbox_stuck 으로 보인다. 0 보다 크면 사람이 봐야 한다. */
    @PostConstruct
    void registerStuckGauge() {
        Gauge.builder("kiosk.erp.outbox.stuck", outboxRepository,
                        repository -> repository.countByProcessedAtIsNullAndCreatedAtBefore(
                                LocalDateTime.now().minusMinutes(STUCK_AFTER_MINUTES)))
                .description("완료했지만 10분 넘게 ERP 로 못 보낸 주문 수")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${erp.order-outbox.delay-ms:5000}")
    @Transactional
    public void deliverPendingOrders() {
        for (ErpOrderOutbox message : outboxRepository
                .findTop10ByProcessedAtIsNullAndAttemptsLessThanOrderByAttemptsAscCreatedAtAsc(maxAttempts)) {
            Order order = orderRepository.findById(message.getOrderId()).orElse(null);
            if (order == null) {
                recordFailure(message, "Order no longer exists");
                continue;
            }
            try {
                erpOrderSender.sendOrderToErp(order);
                message.setProcessedAt(LocalDateTime.now());
                message.setLastError(null);
            } catch (Exception e) {
                String text = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                recordFailure(message, text);
                // 하루 동안 5초마다 실패할 수 있어 스택은 첫 실패 때만 남긴다.
                if (message.getAttempts() == 1) {
                    log.error("ERP delivery for order {} failed; it will be retried", order.getId(), e);
                } else {
                    log.warn("ERP delivery for order {} failed again (attempt {}): {}",
                            order.getId(), message.getAttempts(), message.getLastError());
                }
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
