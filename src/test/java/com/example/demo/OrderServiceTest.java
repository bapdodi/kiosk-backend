package com.example.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.entity.Order;
import com.example.demo.entity.OrderItem;
import com.example.demo.entity.Product;
import com.example.demo.repository.CombinationRepository;
import com.example.demo.repository.ErpOrderOutboxRepository;
import com.example.demo.repository.OrderRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.service.OrderService;
import com.example.demo.service.CustomerStampService;

/** 인증 없이 열린 주문 접수 API 가 본문의 서버 소유 값을 믿지 않는지 본다. */
class OrderServiceTest {

    private OrderRepository orders;
    private ProductRepository products;
    private CombinationRepository combinations;
    private OrderService service;
    private CustomerStampService stamps;

    @BeforeEach
    void setUp() {
        orders = mock(OrderRepository.class);
        products = mock(ProductRepository.class);
        combinations = mock(CombinationRepository.class);
        stamps = mock(CustomerStampService.class);
        service = new OrderService(orders, products, combinations, mock(JdbcTemplate.class),
                mock(ErpOrderOutboxRepository.class), mock(ApplicationEventPublisher.class), stamps);
        when(orders.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(combinations.findFirstByErpCodeAndDeletedFalse(anyString())).thenReturn(Optional.empty());
        when(products.findByErpCode("P-1"))
                .thenReturn(Optional.of(Product.builder().erpCode("P-1").priceA(2500).priceC(4000).build()));
    }

    private static Order order(OrderItem... items) {
        return Order.builder().customerName("동광").items(new ArrayList<>(List.of(items))).build();
    }

    @Test
    void 본문의_주문ID_상태_청구가는_버리고_금액은_서버가_매긴다() {
        Order forged = order(OrderItem.builder().id(77L).erpCode("P-1").quantity(2)
                .finalPrice(1).chargedPrice(1).build());
        forged.setId(5L);
        forged.setStatus("completed");
        forged.setTotalAmount(1);
        forged.setStampCount(5);
        forged.setStampRewardEarned(true);
        forged.setStampRewardRedeemed(true);

        Order saved = service.createOrder(forged, null);

        // id 가 남아 있으면 save() 가 5번 주문을 덮어쓴다.
        assertNull(saved.getId());
        assertNull(saved.getStampCount());
        assertEquals(false, saved.isStampRewardEarned());
        assertEquals(false, saved.isStampRewardRedeemed());
        assertEquals("pending", saved.getStatus());
        OrderItem item = saved.getItems().get(0);
        assertNull(item.getId());
        assertNull(item.getChargedPrice());
        assertEquals(2500, item.getFinalPrice());
        assertEquals(5000, saved.getTotalAmount());
    }

    @Test
    void 수량이_0_이하이거나_품목이_없으면_거절한다() {
        assertThrows(ResponseStatusException.class,
                () -> service.createOrder(order(OrderItem.builder().erpCode("P-1").quantity(0).build()), null));
        assertThrows(ResponseStatusException.class,
                () -> service.createOrder(order(OrderItem.builder().erpCode("P-1").quantity(-3).build()), null));
        assertThrows(ResponseStatusException.class, () -> service.createOrder(order(), null));
        verify(orders, never()).save(any(Order.class));
    }

    @Test
    void 같은_주문_재시도는_도장을_중복_적립하지_않는다() {
        Order existing = Order.builder().id(42L).stampCount(5).stampRewardEarned(true).build();
        when(orders.findByRequestId("same-request")).thenReturn(Optional.of(existing));
        Order result = service.createOrder(order(OrderItem.builder().erpCode("P-1").quantity(1).build()), "same-request");
        assertEquals(existing, result);
        verifyNoInteractions(stamps);
        verify(orders, never()).save(any(Order.class));
    }
}
