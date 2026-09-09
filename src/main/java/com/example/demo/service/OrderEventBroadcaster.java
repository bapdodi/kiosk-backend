package com.example.demo.service;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.demo.event.OrderCreatedEvent;

import lombok.extern.slf4j.Slf4j;

/**
 * 관리자 화면으로 새 주문을 밀어주는 SSE 허브.
 *
 * 폴링(setInterval)은 브라우저가 백그라운드 탭에서 1분까지 늦추지만, 서버가 밀어주는
 * 이벤트는 그 제약을 받지 않는다. 연결은 프로세스 메모리에만 있으므로 백엔드가
 * 재시작되면 끊기고, 브라우저(EventSource)가 알아서 재연결한다.
 */
@Service
@Slf4j
public class OrderEventBroadcaster {

    /** 프록시가 유휴 연결을 끊기 전에 살아있음을 알리는 주기. HA nginx 의 proxy_read_timeout 은 300s. */
    private static final long HEARTBEAT_MS = 15_000L;

    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();

    /** 연결 수명. 만료되면 브라우저가 곧바로 재연결하므로 좀비 연결이 쌓이지 않는다. */
    private final long timeoutMs;

    public OrderEventBroadcaster(@Value("${orders.stream.timeout-ms:1800000}") long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(timeoutMs);
        emitters.add(emitter);

        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));

        try {
            // 첫 바이트를 즉시 흘려보내야 중간 프록시가 응답을 붙들고 있지 않는다.
            emitter.send(SseEmitter.event().name("connected").data("ok"));
        } catch (IOException e) {
            emitters.remove(emitter);
            emitter.completeWithError(e);
        }
        return emitter;
    }

    @TransactionalEventListener
    public void onOrderCreated(OrderCreatedEvent event) {
        broadcast("order-created", Map.of(
                "orderId", event.orderId(),
                "customerName", event.customerName() == null ? "" : event.customerName()));
    }

    @Scheduled(fixedDelay = HEARTBEAT_MS)
    public void heartbeat() {
        broadcast("ping", Map.of("at", System.currentTimeMillis()));
    }

    private void broadcast(String name, Object payload) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(name).data(payload));
            } catch (Exception e) {
                // 이미 닫힌 연결. 목록에서 빼고 종료시키면 브라우저가 재연결한다.
                emitters.remove(emitter);
                emitter.completeWithError(e);
            }
        }
    }

    /** 모니터링/테스트용 현재 연결 수. */
    public int activeConnections() {
        return emitters.size();
    }
}
