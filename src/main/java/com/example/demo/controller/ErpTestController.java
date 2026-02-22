package com.example.demo.controller;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/erptest")
public class ErpTestController {

    private final JdbcTemplate erpJdbcTemplate;

    public ErpTestController(@Qualifier("erpJdbcTemplate") JdbcTemplate erpJdbcTemplate) {
        this.erpJdbcTemplate = erpJdbcTemplate;
    }

    @GetMapping("/suju")
    public String getSuju() {
        int r = erpJdbcTemplate.update("UPDATE ITEM SET DANWI = '' WHERE DANWI IS NOT NULL");
        return "Fixed DANWI in " + r + " rows.";
    }
}
