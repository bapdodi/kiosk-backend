package com.example.demo.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.example.demo.entity.Combination;

public interface CombinationRepository extends JpaRepository<Combination, Long> {

    /** 주문 품목의 ERP 코드로 조합(규격)을 찾는다. 조합의 priceC 는 차액이 아니라 그 규격의 단가다. */
    Optional<Combination> findFirstByErpCodeAndDeletedFalse(String erpCode);

    /**
     * 휴지통에 보여줄 "숨겨진 규격".
     *
     * 상품 자체가 휴지통에 있으면 그 상품이 통째로 목록에 뜨므로 여기서는 제외한다.
     */
    @Query("SELECT c FROM Combination c JOIN FETCH c.product p "
            + "WHERE c.deleted = true AND p.deletedAt IS NULL "
            + "ORDER BY p.name ASC, c.name ASC")
    List<Combination> findHiddenOptions();
}
