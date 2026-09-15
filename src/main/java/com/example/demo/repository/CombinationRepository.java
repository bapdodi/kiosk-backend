package com.example.demo.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.demo.entity.Combination;

public interface CombinationRepository extends JpaRepository<Combination, Long> {

    /** 주문 품목의 ERP 코드로 조합(규격)을 찾는다. 조합의 priceC 는 차액이 아니라 그 규격의 단가다. */
    Optional<Combination> findFirstByErpCodeAndDeletedFalse(String erpCode);
}
