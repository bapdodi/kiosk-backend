package com.example.demo.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.example.demo.entity.Product;

public interface ProductRepository extends JpaRepository<Product, Long> {
    Optional<Product> findByErpCode(String erpCode);

    List<Product> findByName(String name);

    /** 복합옵션 상품을 그 하위 combination 의 ERP 코드로 역추적한다 (수동 이름변경 상품 재연결용). */
    @Query("SELECT DISTINCT c.product FROM Combination c WHERE c.erpCode = :erpCode")
    List<Product> findByCombinationErpCode(
            @org.springframework.data.repository.query.Param("erpCode") String erpCode);

    @Modifying
    @Query("UPDATE Product p SET p.stock = :stock WHERE p.erpCode = :erpCode")
    void updateStockByErpCode(@org.springframework.data.repository.query.Param("erpCode") String erpCode,
            @org.springframework.data.repository.query.Param("stock") Integer stock);

    List<Product> findAllByOrderBySortOrderAscIdAsc();

    Page<Product> findAllByOrderBySortOrderAscIdAsc(Pageable pageable);

    /** 대분류에 소속된 상품 (여러 카테고리 중 하나라도 일치). 같은 대분류에 중분류가 여러 개여도 상품은 한 번만 나오도록 DISTINCT. */
    @Query(value = "SELECT DISTINCT p FROM Product p JOIN p.categories c WHERE c.mainCategory = :mainCategory",
            countQuery = "SELECT COUNT(DISTINCT p) FROM Product p JOIN p.categories c WHERE c.mainCategory = :mainCategory")
    Page<Product> findByCategoryMain(
            @org.springframework.data.repository.query.Param("mainCategory") String mainCategory, Pageable pageable);

    /** (대분류 + 중분류) 쌍에 소속된 상품. */
    @Query(value = "SELECT DISTINCT p FROM Product p JOIN p.categories c WHERE c.mainCategory = :mainCategory AND c.subCategory = :subCategory",
            countQuery = "SELECT COUNT(DISTINCT p) FROM Product p JOIN p.categories c WHERE c.mainCategory = :mainCategory AND c.subCategory = :subCategory")
    Page<Product> findByCategoryMainAndSub(
            @org.springframework.data.repository.query.Param("mainCategory") String mainCategory,
            @org.springframework.data.repository.query.Param("subCategory") String subCategory, Pageable pageable);
}
