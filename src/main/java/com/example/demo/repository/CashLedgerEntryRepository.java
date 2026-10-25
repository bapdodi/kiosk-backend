package com.example.demo.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.demo.entity.CashLedgerEntry;

public interface CashLedgerEntryRepository extends JpaRepository<CashLedgerEntry, Long> {
    List<CashLedgerEntry> findAllByOrderByEntryDateAscIdAsc();
}
