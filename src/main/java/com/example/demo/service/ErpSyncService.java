package com.example.demo.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.entity.Order;
import com.example.demo.entity.Product;
import com.example.demo.repository.ProductRepository;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class ErpSyncService {

    private final JdbcTemplate jdbcTemplate;
    private final ProductRepository productRepository;

    public ErpSyncService(@Qualifier("erpJdbcTemplate") JdbcTemplate jdbcTemplate,
            ProductRepository productRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.productRepository = productRepository;
    }

    @Transactional
    public void syncProducts() {
        log.info("Starting ERP product synchronization...");

        // 1. Get Categories from PARTCODE table
        // Map structure: PARTCODE -> MIDCODE -> SMALLCODE -> Name
        String categoryQuery = "SELECT PARTCODE, MIDCODE, SMALLCODE, PART FROM PARTCODE";
        List<Map<String, Object>> categories = jdbcTemplate.queryForList(categoryQuery);

        // Simple mapping for demonstration - you can refine this based on your specific
        // ERP hierarchy
        Map<String, String> categoryNameMap = categories.stream()
                .collect(Collectors.toMap(
                        row -> toInteger(row.get("PARTCODE")) + "-" + toInteger(row.get("MIDCODE")) + "-"
                                + toInteger(row.get("SMALLCODE")),
                        row -> (String) row.get("PART"),
                        (v1, v2) -> v1));

        // 2. Get Items from ITEM table
        String itemQuery = "SELECT CODE, ITEM, OUTPR, PARTCODE, MIDCODE, SMALLCODE FROM [ITEM]";
        List<Map<String, Object>> erpItems = jdbcTemplate.queryForList(itemQuery);

        for (Map<String, Object> itemRow : erpItems) {
            String code = String.valueOf(itemRow.get("CODE"));
            String name = (String) itemRow.get("ITEM");

            Integer price = toInteger(itemRow.get("OUTPR"));
            Integer part = toInteger(itemRow.get("PARTCODE"));
            Integer mid = toInteger(itemRow.get("MIDCODE"));
            Integer small = toInteger(itemRow.get("SMALLCODE"));

            String mainCat = categoryNameMap.getOrDefault(part + "-0-0", "ERP " + part);
            String subCat = categoryNameMap.getOrDefault(part + "-" + mid + "-0", "ERP " + mid);
            String detailCat = categoryNameMap.getOrDefault(part + "-" + mid + "-" + small, "");

            productRepository.findByErpCode(code).ifPresentOrElse(
                    product -> {
                        if (product != null) {
                            // Update existing
                            product.setName(name);
                            product.setPrice(price);
                            product.setMainCategory(mainCat);
                            product.setSubCategory(subCat);
                            product.setDetailCategory(detailCat);
                            productRepository.save(product);
                            log.info("Updated product: {} from ERP", name);
                        }
                    },
                    () -> {
                        // Create new
                        Product newProduct = Product.builder()
                                .erpCode(code)
                                .name(name != null ? name.trim() : "ERP Item " + code)
                                .price(price)
                                .mainCategory(mainCat)
                                .subCategory(subCat)
                                .detailCategory(detailCat)
                                .isComplexOptions(false)
                                .build();
                        if (newProduct != null) {
                            productRepository.save(newProduct);
                            log.info("Created new product: {} from ERP", name);
                        }
                    });
        }
        log.info("ERP product synchronization completed.");
    }

    @Transactional
    public void sendOrderToErp(Order order) {
        log.info("Sending order #{} to ERP...", order.getId());

        String dateStr = order.getTimestamp()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        for (com.example.demo.entity.OrderItem item : order.getItems()) {
            try {
                // If erpCode is missing, we can't sync it properly
                if (item.getErpCode() == null || item.getErpCode().isEmpty()) {
                    log.warn("OrderItem {} has no ERP code. Skipping ERP sync for this item.", item.getName());
                    continue;
                }

                String insertSuju = "INSERT INTO SUJU (dDATE, ITEMCODE, PRICE, EA, BALJUNO) VALUES (?, ?, ?, ?, ?)";
                jdbcTemplate.update(insertSuju,
                        dateStr,
                        item.getErpCode(),
                        item.getFinalPrice(),
                        item.getQuantity() != null ? item.getQuantity() : 1,
                        "KIOSK-" + order.getId());
                log.info("Synced item {} ({}) to ERP SUJU", item.getName(), item.getErpCode());
            } catch (Exception e) {
                log.error("Failed to sync item {} to ERP: {}", item.getName(), e.getMessage());
            }
        }
    }

    private Integer toInteger(Object obj) {
        if (obj == null)
            return 0;
        if (obj instanceof Number) {
            return ((Number) obj).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(obj));
        } catch (Exception e) {
            return 0;
        }
    }
}
