package com.example.demo;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import com.example.demo.entity.Customer;
import com.example.demo.entity.Order;
import com.example.demo.repository.CustomerRepository;
import com.example.demo.service.CustomerStampService;

class CustomerStampServiceTest {
    JdbcTemplate jdbc;
    CustomerRepository customers;
    CustomerStampService service;

    @BeforeEach
    void setup() {
        jdbc = mock(JdbcTemplate.class);
        customers = mock(CustomerRepository.class);
        service = new CustomerStampService(jdbc, customers);
        when(customers.findByErpCode("A")).thenReturn(Optional.of(Customer.builder().erpCode("A").name("상호A").build()));
    }

    @Test
    void 다섯번째에만_상품을_적립하고_다음판은_하나부터_시작한다() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq("A"))).thenReturn(4L, 5L, 6L, 10L);
        for (int total : new int[]{4, 5, 6, 10}) {
            Order order = Order.builder().erpCustomerCode(" A ").build();
            service.award(order);
            assertEquals((total - 1) % 5 + 1, order.getStampCount());
            assertEquals(total % 5 == 0, order.isStampRewardEarned());
        }
    }

    @Test
    void 비회원과_없는_고객은_적립하지_않는다() {
        when(customers.findByErpCode("GUEST")).thenReturn(Optional.of(Customer.builder().erpCode("GUEST").name("1").build()));
        for (String code : new String[]{null, "", "GUEST", "UNKNOWN"}) {
            Order order = Order.builder().erpCustomerCode(code).build();
            service.award(order);
            assertNull(order.getStampCount());
        }
        verifyNoInteractions(jdbc);
    }
}
