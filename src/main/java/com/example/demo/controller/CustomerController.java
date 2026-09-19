package com.example.demo.controller;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final JdbcTemplate erpJdbcTemplate;

    public CustomerController(@Qualifier("erpJdbcTemplate") JdbcTemplate erpJdbcTemplate) {
        this.erpJdbcTemplate = erpJdbcTemplate;
    }

    /**
     * 키오스크 주문 화면에 뜨는 거래처 목록.
     *
     * 판매 단가 등급(DANGA)이 2=OUTA, 3=OUTB, 4=OUTC 인 거래처만 보여준다. 그 밖의 등급은
     * 단가표가 없어 주문이 들어와도 단가를 고를 수 없고, 결국 소비자가로 청구된다.
     * DANGA=1 은 매입(입고) 거래처, DANGA=6 은 건별 협의가 거래처다(둘 다 키오스크 주문 대상이 아니다).
     * 화이트리스트로 둔 이유는 나중에 새 등급이 생겨도 소비자가로 새어 나가지 않게 하기 위해서다.
     */
    @GetMapping
    public List<Map<String, Object>> getCustomers() {
        return erpJdbcTemplate
                .queryForList(
                        "SELECT LTRIM(RTRIM(CODE)) as CODE, LTRIM(RTRIM(NAME)) as NAME FROM GURAE "
                                + "WHERE KIND = '1' AND DANGA IN (2, 3, 4) ORDER BY NAME");
    }
}
