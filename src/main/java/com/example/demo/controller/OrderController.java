package com.example.demo.controller;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.demo.entity.Order;
import com.example.demo.service.OrderEventBroadcaster;
import com.example.demo.service.OrderService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final OrderEventBroadcaster orderEventBroadcaster;

    @GetMapping("/admin")
    public List<Order> getAllOrders() {
        return orderService.getAllOrders();
    }

    /**
     * 관리자 화면의 새 주문 알림 채널. 경로가 /api/*&#47;admin/** 규칙에 걸려 ROLE_ADMIN 이 필요하다.
     * 폴링과 달리 백그라운드 탭에서도 지연 없이 도착한다.
     */
    @GetMapping(path = "/admin/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamOrders() {
        return orderEventBroadcaster.subscribe();
    }

    @PostMapping
    public Order createOrder(@RequestBody Order order,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestId) {
        return orderService.createOrder(order, requestId);
    }

    @PutMapping("/admin/{id}/status")
    public ResponseEntity<Order> updateOrderStatus(@PathVariable("id") Long id, @RequestBody String status) {
        return orderService.updateOrderStatus(id, status)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/admin/{id}")
    public ResponseEntity<Void> deleteOrder(@PathVariable("id") Long id) {
        if (orderService.deleteOrder(id)) {
            return ResponseEntity.ok().build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }
}
