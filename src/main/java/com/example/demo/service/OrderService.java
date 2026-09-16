package com.example.demo.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.demo.entity.Order;
import com.example.demo.entity.OrderItem;
import com.example.demo.repository.CombinationRepository;
import com.example.demo.repository.OrderRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.repository.ErpOrderOutboxRepository;
import com.example.demo.entity.ErpOrderOutbox;
import com.example.demo.event.OrderCreatedEvent;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final CombinationRepository combinationRepository;
    private final ErpSyncService erpSyncService;
    private final JdbcTemplate jdbcTemplate;
    private final ErpOrderOutboxRepository erpOrderOutboxRepository;
    private final ApplicationEventPublisher eventPublisher;

    public List<Order> getAllOrders() {
        // DB의 기본 반환 순서는 보장되지 않는다. 동시 주문도 안정적으로 보이도록 ID를 보조 정렬로 둔다.
        return orderRepository.findAllByOrderByTimestampDescIdDesc();
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
            for (OrderItem item : order.getItems()) {
                if (item.getErpCode() == null) {
                    // Try to find product by name to get erpCode
                    // Note: This is a fallback. Ideally the frontend should send erpCode or
                    // productId.
                    productRepository.findByName(item.getName()).stream().findFirst()
                            .ifPresent(p -> item.setErpCode(p.getErpCode()));
                }
            }
            priceOrder(order);
        }

        Order savedOrder = orderRepository.save(order);
        orderRepository.flush();
        erpOrderOutboxRepository.save(new ErpOrderOutbox(savedOrder.getId()));

        // 커밋된 뒤에만 관리자 화면으로 나가야 하므로 리스너 쪽에서 AFTER_COMMIT 으로 받는다.
        // 멱등 재요청으로 기존 주문을 돌려준 경우에는 여기까지 오지 않아 중복 알림이 없다.
        eventPublisher.publishEvent(new OrderCreatedEvent(savedOrder.getId(), savedOrder.getCustomerName()));

        return savedOrder;
    }

    /**
     * 주문 금액을 서버에서 다시 계산한다.
     *
     * 손님 화면에는 단가를 내려주지 않으므로 요청 본문의 금액은 신뢰할 수 없다(비어 있거나 조작됐을 수 있다).
     * 품목의 ERP 코드로 판매가(priceC)를 직접 찾아 채운다. 복합옵션 상품은 규격마다 코드가 달라
     * 조합(Combination)을 먼저 보고, 없으면 상품 단위 코드로 찾는다.
     *
     * 여기서 넣는 값은 소비자가다. 거래처 단가(A/B/C)를 반영한 실청구가는 ERP 전송 때
     * {@code ErpSyncService} 가 chargedPrice 와 totalAmount 로 다시 덮어쓴다.
     */
    private void priceOrder(Order order) {
        long total = 0;
        for (OrderItem item : order.getItems()) {
            int unitPrice = resolveUnitPrice(item);
            item.setFinalPrice(unitPrice);
            total += (long) unitPrice * (item.getQuantity() != null ? item.getQuantity() : 1);
        }
        order.setTotalAmount((int) total);
    }

    private int resolveUnitPrice(OrderItem item) {
        String erpCode = item.getErpCode();
        if (erpCode != null && !erpCode.isBlank()) {
            Integer comboPrice = combinationRepository.findFirstByErpCodeAndDeletedFalse(erpCode)
                    .map(c -> c.getPriceC())
                    .orElse(null);
            if (comboPrice != null) {
                return comboPrice;
            }
            Integer productPrice = productRepository.findByErpCode(erpCode)
                    .map(p -> p.getPriceC())
                    .orElse(null);
            if (productPrice != null) {
                return productPrice;
            }
        }
        // 코드로 못 찾은 품목은 0 으로 둔다. ERP 전송이 성공하면 실청구가로 덮어써진다.
        return 0;
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
