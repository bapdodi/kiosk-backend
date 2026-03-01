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

    @GetMapping
    public List<Map<String, Object>> getCustomers() {
        return erpJdbcTemplate
                .queryForList("SELECT LTRIM(RTRIM(CODE)) as CODE, LTRIM(RTRIM(NAME)) as NAME FROM GURAE ORDER BY NAME");
    }
}
