package com.example.demo.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.demo.entity.Category;

public interface CategoryRepository extends JpaRepository<Category, String> {
    // 정렬 없는 조회 메서드는 두지 않는다. sort_order 동점이 많으면 DB 가 돌려주는
    // 물리 순서가 그대로 화면 순서가 되어 "순서가 제멋대로 바뀐다"로 이어진다.
    List<Category> findByLevelOrderBySortOrderAscIdAsc(String level);

    List<Category> findByParentIdOrderBySortOrderAscIdAsc(String parentId);

    List<Category> findAllByOrderBySortOrderAscIdAsc();

    /**
     * sortOrder 채번(max+1)을 직렬화하기 위한 트랜잭션 범위 advisory lock.
     * 두 명이 동시에 카테고리를 만들면 둘 다 같은 max 를 읽어 같은 순서를 배정받는다.
     * 트랜잭션이 끝나면 자동 해제된다.
     */
    @Query(value = "SELECT pg_advisory_xact_lock(3417)", nativeQuery = true)
    void lockSortOrderAssignment();

    /** 주어진 카테고리를 대분류나 중분류로 쓰는 상품 수. trashed 가 true 면 휴지통 상품만 센다. */
    @Query(value = "SELECT COUNT(DISTINCT pc.product_id) FROM product_categories pc"
            + " JOIN products p ON p.id = pc.product_id"
            + " WHERE (pc.main_category IN (:ids) OR pc.sub_category IN (:ids))"
            + " AND (p.deleted_at IS NOT NULL) = :trashed", nativeQuery = true)
    long countProductsUsing(@Param("ids") Collection<String> ids, @Param("trashed") boolean trashed);
}
