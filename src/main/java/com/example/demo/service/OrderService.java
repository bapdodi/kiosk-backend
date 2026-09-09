package com.example.demo.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.demo.entity.Order;
import com.example.demo.repository.OrderRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.repository.ErpOrderOutboxRepository;
import com.example.demo.entity.ErpOrderOutbox;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final ErpSyncService erpSyncService;
    private final JdbcTemplate jdbcTemplate;
    private final ErpOrderOutboxRepository erpOrderOutboxRepository;

    public List<Order> getAllOrders() {
        return orderRepository.findAll();
    }

    @Transactional
    public Order createOrder(Order order, String requestId) {
        if (requestId != null && !requestId.isBlank()) {
            String normalizedRequestId = requestId.trim();
            if (normalizedRequestId.length() > 64) {
                throw new IllegalArgumentException("Idempotency-Key is too long");
            }
            // The transaction-scoped lock closes the concurrent duplicate-request race.
            jdbcTemplate.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 0))",
                    Object.class, normalizedRequestId);
            Optional<Order> existing = orderRepository.findByRequestId(normalizedRequestId);
            if (existing.isPresent()) {
                return existing.get();
            }
            order.setRequestId(normalizedRequestId);
        }
        order.setTimestamp(LocalDateTime.now());
        if (order.getStatus() == null) {
            order.setStatus("pending");
        }

        // Populate erpCode for each item
        if (order.getItems() != null) {
            for (com.example.demo.entity.OrderItem item : order.getItems()) {
                if (item.getErpCode() == null) {
                    // Try to find product by name to get erpCode
                    // Note: This is a fallback. Ideally the frontend should send erpCode or
                    // productId.
                    productRepository.findByName(item.getName()).stream().findFirst()
                            .ifPresent(p -> item.setErpCode(p.getErpCode()));
                }
            }
        }

        Order savedOrder = orderRepository.save(order);
        orderRepository.flush();
        erpOrderOutboxRepository.save(new ErpOrderOutbox(savedOrder.getId()));

        return savedOrder;
    }

    @Transactional
    public Optional<Order> updateOrderStatus(Long id, String status) {
        return orderRepository.findById(id)
                .map(order -> {
                    order.setStatus(status.replace("\"", ""));
                    return orderRepository.save(order);
                });
    }

    @Transactional
    public boolean deleteOrder(Long id) {
        return orderRepository.findById(id)
                .map(order -> {
                    orderRepository.delete(order);
                    return true;
                })
                .orElse(false);
    }
}
