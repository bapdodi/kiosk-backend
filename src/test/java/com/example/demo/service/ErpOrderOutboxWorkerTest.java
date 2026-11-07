package com.example.demo.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.demo.entity.ErpOrderOutbox;
import com.example.demo.entity.Order;
import com.example.demo.repository.ErpOrderOutboxRepository;
import com.example.demo.repository.OrderRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** 완료 주문이 ERP 로 나가지 못할 때 포기하지 않고, 막히면 지표로 드러나는지 본다. */
class ErpOrderOutboxWorkerTest {
    private ErpOrderOutboxRepository outbox;
    private OrderRepository orders;
    private ErpOrderSender sender;
    private SimpleMeterRegistry registry;
    private ErpOrderOutboxWorker worker;

    @BeforeEach
    void setUp() {
        outbox = mock(ErpOrderOutboxRepository.class);
        orders = mock(OrderRepository.class);
        sender = mock(ErpOrderSender.class);
        registry = new SimpleMeterRegistry();
        worker = new ErpOrderOutboxWorker(outbox, orders, sender, registry);
        ReflectionTestUtils.setField(worker, "maxAttempts", 17280);
    }

    @Test
    void failedDeliveryStaysPendingWithError() {
        ErpOrderOutbox message = pending(1L, 20);
        doThrow(new IllegalStateException("ERP down")).when(sender).sendOrderToErp(any());

        worker.deliverPendingOrders();

        // 예전 상한(20)을 넘겨도 계속 재시도 대상으로 남는다.
        assertEquals(21, message.getAttempts());
        assertEquals("ERP down", message.getLastError());
        assertNull(message.getProcessedAt());
    }

    @Test
    void successfulDeliveryIsMarkedProcessed() {
        ErpOrderOutbox message = pending(2L, 3);
        message.setLastError("ERP down");

        worker.deliverPendingOrders();

        assertNotNull(message.getProcessedAt());
        assertNull(message.getLastError());
    }

    @Test
    void queriesUpToTheConfiguredCap() {
        when(outbox.findTop10ByProcessedAtIsNullAndAttemptsLessThanOrderByAttemptsAscCreatedAtAsc(anyInt()))
                .thenReturn(List.of());

        worker.deliverPendingOrders();

        verify(outbox).findTop10ByProcessedAtIsNullAndAttemptsLessThanOrderByAttemptsAscCreatedAtAsc(17280);
    }

    @Test
    void stuckGaugeReportsPendingOlderThanTenMinutes() {
        when(outbox.countByProcessedAtIsNullAndCreatedAtBefore(any(LocalDateTime.class))).thenReturn(3L);

        worker.registerStuckGauge();

        assertEquals(3.0, registry.get("kiosk.erp.outbox.stuck").gauge().value());
    }

    private ErpOrderOutbox pending(Long orderId, int attempts) {
        ErpOrderOutbox message = new ErpOrderOutbox(orderId);
        message.setAttempts(attempts);
        Order order = new Order();
        order.setId(orderId);
        when(outbox.findTop10ByProcessedAtIsNullAndAttemptsLessThanOrderByAttemptsAscCreatedAtAsc(anyInt()))
                .thenReturn(List.of(message));
        when(orders.findById(orderId)).thenReturn(Optional.of(order));
        return message;
    }
}
