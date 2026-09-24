package com.example.demo.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.example.demo.entity.Customer;
import com.example.demo.repository.CustomerRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * ERP 거래처(GURAE)를 로컬 customers 테이블로 복사한다.
 *
 * 주문 생성 시점에 거래처 단가를 고르려면 DANGA 가 필요한데, 손님 화면이 사내 MSSQL 응답을
 * 기다리지 않도록 로컬에 사본을 둔다. ERP 에서 사라진 거래처는 지우지 않는다 —
 * 과거 주문이 참조할 수 있고, 목록 노출은 KIND/DANGA 로 따로 거르기 때문이다.
 */
@Service
@Slf4j
public class ErpCustomerSync {

    private final JdbcTemplate erpJdbcTemplate;
    private final CustomerRepository customerRepository;

    public ErpCustomerSync(@Qualifier("erpJdbcTemplate") JdbcTemplate erpJdbcTemplate,
            CustomerRepository customerRepository) {
        this.erpJdbcTemplate = erpJdbcTemplate;
        this.customerRepository = customerRepository;
    }

    /**
     * @Transactional 을 달지 않는다. 저장은 saveAll 한 번이라 그 자체로 한 트랜잭션이고,
     * 상품 동기화 안에서 불릴 때는 그 트랜잭션에 합류한다. 여기에 달면 ERP 조회 실패가
     * 프록시를 지나며 상품 동기화 트랜잭션까지 rollback-only 로 만들어, "거래처 실패해도
     * 상품 동기화는 계속한다"가 깨진다.
     */
    public int syncCustomers() {
        List<Map<String, Object>> rows = erpJdbcTemplate.queryForList(
                "SELECT LTRIM(RTRIM(CODE)) as CODE, LTRIM(RTRIM(NAME)) as NAME, KIND, DANGA FROM GURAE");
        LocalDateTime now = LocalDateTime.now();
        List<Customer> customers = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object code = row.get("CODE");
            if (code == null || code.toString().isBlank()) {
                continue;
            }
            Object name = row.get("NAME");
            Object kind = row.get("KIND");
            customers.add(Customer.builder()
                    .erpCode(code.toString().trim())
                    .name(name == null ? "" : name.toString().trim())
                    .kind(kind == null ? null : kind.toString().trim())
                    .danga(row.get("DANGA") == null ? null : ErpValues.toInteger(row.get("DANGA")))
                    .syncedAt(now)
                    .build());
        }
        customerRepository.saveAll(customers);
        log.info("Synced {} ERP customers", customers.size());
        return customers.size();
    }

    /**
     * 배포 직후 사본이 비어 있으면 주문이 전부 소비자가로 계산되므로, 기동 때 한 번 채운다.
     * 이미 사본이 있으면 건드리지 않는다 — 갱신은 상품 동기화나 거래처 동기화 API 가 맡는다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void seedCustomersIfEmpty() {
        try {
            if (customerRepository.count() > 0) {
                return;
            }
            log.info("Local ERP customer copy is empty, seeding from ERP...");
            syncCustomers();
        } catch (Exception e) {
            // ERP 가 안 떠 있어도 키오스크는 떠야 한다. 단가는 다음 동기화 때 맞춰진다.
            log.warn("Initial ERP customer seed failed, will stay empty until next sync", e);
        }
    }
}
