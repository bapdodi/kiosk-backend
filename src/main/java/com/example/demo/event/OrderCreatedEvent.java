package com.example.demo.event;

/**
 * 주문이 새로 저장됐을 때 발행된다. 관리자 화면 SSE 알림의 트리거이며,
 * 커밋 이후에만 소비되도록 {@code @TransactionalEventListener} 로 받는다.
 */
public record OrderCreatedEvent(Long orderId, String customerName) {
}
