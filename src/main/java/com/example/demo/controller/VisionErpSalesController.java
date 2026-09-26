package com.example.demo.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Read-only bridge for the vision review service. ERP remains the sales source of truth. */
@RestController
@RequestMapping("/api/vision/erp-sales")
public class VisionErpSalesController {
    private static final DateTimeFormatter ERP_DATE = DateTimeFormatter.ofPattern("yy.MM.dd");
    private final JdbcTemplate erp;
    private final String token;

    public VisionErpSalesController(@Qualifier("erpJdbcTemplate") JdbcTemplate erp,
            @Value("${vision.erp-bridge-token:}") String token) {
        this.erp = erp;
        this.token = token;
    }

    @GetMapping
    public List<Map<String, Object>> sales(@RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestHeader(value = "X-Vision-Token", required = false) String supplied) {
        if (token.isBlank() || supplied == null || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        String table = "IL" + String.format("%02d", date.getYear() % 100);
        Integer exists = erp.queryForObject(
                "SELECT COUNT(*) FROM sys.tables WHERE name = ?", Integer.class, table);
        if (exists == null || exists == 0) {
            return List.of();
        }
        return erp.queryForList("SELECT l.AUTOKEY AS autokey, l.dDATE AS erpDate, "
                + "l.dNO AS voucherNo, l.EDITNO AS [lineNo], "
                + "LTRIM(RTRIM(CAST(l.ITEMCODE AS varchar(40)))) AS itemCode, "
                + "LTRIM(RTRIM(ISNULL(i.ITEM,''))) AS itemName, l.EA AS quantity, "
                + "ISNULL(l.BIGO2,'') AS traceTag "
                + "FROM " + table + " l LEFT JOIN ITEM i ON i.CODE = l.ITEMCODE "
                + "WHERE l.KIND = '3' AND l.dDATE = ? ORDER BY l.AUTOKEY",
                date.format(ERP_DATE));
    }
}
