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
import com.example.demo.repository.CustomerRepository;
import com.example.demo.repository.OrderRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.repository.ErpOrderOutboxRepository;
import com.example.demo.entity.ErpOrderOutbox;
import com.example.demo.event.OrderCreatedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final CombinationRepository combinationRepository;
    private final CustomerRepository customerRepository;
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
        // ERP 전송은 접수 시점이 아니라 관리자가 '처리 완료'로 바꿀 때 예약한다.
        // updateOrderStatus() 참고.

        // 커밋된 뒤에만 관리자 화면으로 나가야 하므로 리스너 쪽에서 AFTER_COMMIT 으로 받는다.
        // 멱등 재요청으로 기존 주문을 돌려준 경우에는 여기까지 오지 않아 중복 알림이 없다.
        eventPublisher.publishEvent(new OrderCreatedEvent(savedOrder.getId(), savedOrder.getCustomerName()));

        return savedOrder;
    }

    /**
     * 주문 금액을 서버에서 다시 계산한다.
     *
     * 손님 화면에는 단가를 내려주지 않으므로 요청 본문의 금액은 신뢰할 수 없다(비어 있거나 조작됐을 수 있다).
     * 품목의 ERP 코드로 판매가를 직접 찾아 채운다. 복합옵션 상품은 규격마다 코드가 달라
     * 조합(Combination)을 먼저 보고, 없으면 상품 단위 코드로 찾는다.
     *
     * 단가는 주문자의 거래처 등급(ERP GURAE.DANGA)을 로컬 사본에서 읽어 A/B/C 중 하나를 고른다.
     * 예전에는 여기서 소비자가만 넣고 ERP 전송 때 실청구가로 덮어썼는데, 그러면 전송 전(주문 접수 ~
     * 처리 완료 사이) 주문 화면과 거래명세서가 소비자가로 보였다. ERP 전송 때의 최종 확정은 그대로다.
     */
    private void priceOrder(Order order) {
        Integer danga = resolveDanga(order.getErpCustomerCode());
        long total = 0;
        for (OrderItem item : order.getItems()) {
            int unitPrice = resolveUnitPrice(item, danga);
            item.setFinalPrice(unitPrice);
            total += (long) unitPrice * (item.getQuantity() != null ? item.getQuantity() : 1);
        }
        order.setTotalAmount((int) total);
    }

    /** 거래처 단가 등급. 사본에 없으면 null 이고, 이 경우 소비자가로 계산한다. */
    private Integer resolveDanga(String erpCustomerCode) {
        if (erpCustomerCode == null || erpCustomerCode.isBlank()) {
            return null;
        }
        return customerRepository.findByErpCode(erpCustomerCode.trim())
                .map(c -> c.getDanga())
                .orElseGet(() -> {
                    log.warn("No local copy of ERP customer {}, pricing at consumer price", erpCustomerCode);
                    return null;
                });
    }

    private int resolveUnitPrice(OrderItem item, Integer danga) {
        String erpCode = item.getErpCode();
        if (erpCode != null && !erpCode.isBlank()) {
            Integer comboPrice = combinationRepository.findFirstByErpCodeAndDeletedFalse(erpCode)
                    .map(c -> pickTier(danga, c.getPriceA(), c.getPriceB(), c.getPriceC()))
                    .orElse(null);
            if (comboPrice != null) {
                return comboPrice;
            }
            Integer productPrice = productRepository.findByErpCode(erpCode)
                    .map(p -> pickTier(danga, p.getPriceA(), p.getPriceB(), p.getPriceC()))
                    .orElse(null);
            if (productPrice != null) {
                return productPrice;
            }
        }
        // 코드로 못 찾은 품목은 0 으로 둔다. ERP 전송이 성공하면 실청구가로 덮어써진다.
        return 0;
    }

    /**
     * DANGA 등급에 맞는 단가를 고른다. ERP 전송(ErpSyncService)의 선택 규칙과 같아야 한다.
     *
     * 등급을 못 고르거나 해당 단가가 비어 있으면 소비자가(priceC)로 떨어진다. DANGA=1(매입 거래처)은
     * 주문 화면 거래처 목록에서 걸러지므로 정상 경로로는 여기 오지 않는다.
     */
    private Integer pickTier(Integer danga, Integer priceA, Integer priceB, Integer priceC) {
        if (danga != null) {
            if (danga == 2 && priceA != null && priceA > 0) {
                return priceA;
            }
            if (danga == 3 && priceB != null && priceB > 0) {
                return priceB;
            }
        }
        return priceC;
    }

    /**
     * 주문 상태를 바꾼다.
     *
     * '처리 완료'로 넘어가는 순간에만 ERP 전송을 예약한다(아웃박스에 한 행). 실제 전송은
     * ErpOrderOutboxWorker 가 맡으므로 ERP 가 잠시 죽어 있어도 재시도로 살아난다.
     * 아웃박스 PK 가 주문 ID 라 완료 → 대기 → 완료 로 오가도 행은 하나뿐이고,
     * sendOrderToErp() 도 KIOSK_ORDER_RECEIPT 로 이미 보낸 주문을 건너뛰어 중복 전표가 생기지 않는다.
     */
    @Transactional
    public Optional<Order> updateOrderStatus(Long id, String status) {
        return orderRepository.findById(id)
                .map(order -> {
                    String next = status.replace("\"", "");
                    boolean becameCompleted = "completed".equals(next) && !"completed".equals(order.getStatus());
                    order.setStatus(next);
                    Order saved = orderRepository.save(order);
                    if (becameCompleted && !erpOrderOutboxRepository.existsById(id)) {
                        erpOrderOutboxRepository.save(new ErpOrderOutbox(id));
                    }
                    return saved;
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
