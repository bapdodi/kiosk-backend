package com.example.demo.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

import com.example.demo.entity.Order;

public interface OrderRepository extends JpaRepository<Order, Long> {
    Optional<Order> findByRequestId(String requestId);
}
