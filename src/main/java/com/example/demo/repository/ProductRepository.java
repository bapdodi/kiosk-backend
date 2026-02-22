package com.example.demo.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.example.demo.entity.Product;

public interface ProductRepository extends JpaRepository<Product, Long> {
    Optional<Product> findByErpCode(String erpCode);

    List<Product> findByName(String name);

    @Modifying
    @Query("UPDATE Product p SET p.stock = :stock WHERE p.erpCode = :erpCode")
    void updateStockByErpCode(@org.springframework.data.repository.query.Param("erpCode") String erpCode,
            @org.springframework.data.repository.query.Param("stock") Integer stock);
}
