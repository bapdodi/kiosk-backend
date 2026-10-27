package com.example.demo.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.demo.entity.StatementItemAlias;

public interface StatementItemAliasRepository extends JpaRepository<StatementItemAlias, Long> {
    List<StatementItemAlias> findByVendorCode(Integer vendorCode);

    Optional<StatementItemAlias> findByVendorCodeAndNameKeyAndItemCode(Integer vendorCode, String nameKey, Integer itemCode);
}
