package com.example.demo.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.demo.entity.StatementVendorAlias;

public interface StatementVendorAliasRepository extends JpaRepository<StatementVendorAlias, Long> {
    List<StatementVendorAlias> findByBizNoOrderByHitsDesc(String bizNo);

    Optional<StatementVendorAlias> findByBizNoAndVendorCode(String bizNo, Integer vendorCode);
}
