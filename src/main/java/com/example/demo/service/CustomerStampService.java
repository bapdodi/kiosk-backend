package com.example.demo.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.entity.Order;
import com.example.demo.repository.CustomerRepository;

import lombok.RequiredArgsConstructor;

/** 주문 접수 트랜잭션 안에서 고객별 카운터를 원자적으로 증가시킨다. */
@Service
@RequiredArgsConstructor
public class CustomerStampService {
    private final JdbcTemplate jdbcTemplate;
    private final CustomerRepository customers;

    @Transactional
    public void award(Order order) {
        String code = order.getErpCustomerCode();
        if (code == null || code.isBlank()) return;
        code = code.trim();
        var customer = customers.findByErpCode(code);
        // 비회원 공용 계정은 서로 다른 손님을 구별할 수 없으므로 적립하지 않는다.
        if (customer.isEmpty()) return;
        String name = customer.get().getName();
        if (name == null || "1".equals(name.trim())) return;
        Long total = jdbcTemplate.queryForObject("""
                INSERT INTO customer_stamp_accounts (customer_code, total_stamps) VALUES (?, 1)
                ON CONFLICT (customer_code) DO UPDATE
                SET total_stamps = customer_stamp_accounts.total_stamps + 1
                RETURNING total_stamps
                """, Long.class, code);
        int count = (int) ((total - 1) % 5) + 1;
        order.setStampCount(count);
        order.setStampRewardEarned(count == 5);
    }
}
