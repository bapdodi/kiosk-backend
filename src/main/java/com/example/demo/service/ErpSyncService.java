package com.example.demo.service;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.entity.Category;
import com.example.demo.entity.Combination;
import com.example.demo.entity.Order;
import com.example.demo.entity.Product;
import com.example.demo.repository.CategoryRepository;
import com.example.demo.repository.ProductRepository;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class ErpSyncService {

    private final JdbcTemplate erpJdbcTemplate;
    private final JdbcTemplate primaryJdbcTemplate;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    public ErpSyncService(@Qualifier("erpJdbcTemplate") JdbcTemplate erpJdbcTemplate,
            @Qualifier("jdbcTemplate") JdbcTemplate primaryJdbcTemplate,
            ProductRepository productRepository,
            CategoryRepository categoryRepository) {
        this.erpJdbcTemplate = erpJdbcTemplate;
        this.primaryJdbcTemplate = primaryJdbcTemplate;
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
    }

    @Transactional
    public void syncProducts() {
        log.info("Starting ERP product synchronization...");

        // 1. Get Categories from PARTCODE table
        String categoryQuery = "SELECT PARTCODE, MIDCODE, SMALLCODE, PART FROM PARTCODE";
        List<Map<String, Object>> erpCategories = erpJdbcTemplate.queryForList(categoryQuery);

        for (Map<String, Object> row : erpCategories) {
            int part = toInteger(row.get("PARTCODE"));
            int mid = toInteger(row.get("MIDCODE"));
            int small = toInteger(row.get("SMALLCODE"));
            String name = (String) row.get("PART");

            if (name == null || name.trim().isEmpty()) {
                name = "ERP " + part;
            }

            ensureCategoryExists(part, mid, small, name.trim());
        }

        // 2. Get Items from ITEM table
        String itemQuery = "SELECT CODE, ITEM, OUTPR, PARTCODE, MIDCODE, SMALLCODE, JEGO FROM [ITEM] WHERE CODE >= 100";
        List<Map<String, Object>> erpItems = erpJdbcTemplate.queryForList(itemQuery);

        // Grouping items by name manually to process them properly
        java.util.Map<String, List<Map<String, Object>>> groupedItems = new java.util.HashMap<>();
        for (Map<String, Object> itemRow : erpItems) {
            String name = (String) itemRow.get("ITEM");
            if (name == null)
                continue;
            name = name.trim();
            groupedItems.computeIfAbsent(name, k -> new java.util.ArrayList<>()).add(itemRow);
        }

        for (java.util.Map.Entry<String, List<Map<String, Object>>> entry : groupedItems.entrySet()) {
            String name = entry.getKey();
            List<Map<String, Object>> rows = entry.getValue();

            // Sub-group by price to identify actual selectable options
            java.util.Map<Integer, List<Map<String, Object>>> priceGroups = new java.util.LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                Integer price = toInteger(row.get("OUTPR"));
                priceGroups.computeIfAbsent(price, k -> new java.util.ArrayList<>()).add(row);
            }

            // Get categories from the first row of the entire group
            Map<String, Object> firstRow = rows.get(0);
            Integer basePrice = toInteger(firstRow.get("OUTPR"));
            Integer part = toInteger(firstRow.get("PARTCODE"));
            Integer mid = toInteger(firstRow.get("MIDCODE"));
            Integer small = toInteger(firstRow.get("SMALLCODE"));

            String mainCatId = String.format("erp-%d-0-0", part);
            String subCatId = String.format("erp-%d-%d-0", part, mid);
            String detailCatId = String.format("erp-%d-%d-%d", part, mid, small);

            ensureCategoryExists(part, 0, 0, "ERP " + part);
            if (mid != 0)
                ensureCategoryExists(part, mid, 0, "ERP " + mid);
            if (small != 0)
                ensureCategoryExists(part, mid, small, name);

            Product product = productRepository.findByName(name).stream().findFirst().orElse(null);

            if (product == null) {
                product = Product.builder()
                        .name(name)
                        .price(basePrice)
                        .mainCategory(mainCatId)
                        .subCategory(subCatId)
                        .detailCategory(detailCatId)
                        .hashtags(new java.util.ArrayList<>())
                        .images(new java.util.ArrayList<>())
                        .optionGroups(new java.util.ArrayList<>())
                        .combinations(new java.util.ArrayList<>())
                        .isCategoryModified(false)
                        .isComplexOptions(priceGroups.size() > 1) // Only complex if prices differ
                        .build();
            } else {
                product.setPrice(basePrice);
                if (product.getIsCategoryModified() == null || !product.getIsCategoryModified()) {
                    product.setMainCategory(mainCatId);
                    product.setSubCategory(subCatId);
                    product.setDetailCategory(detailCatId);
                }
            }

            // Consolidate combinations
            java.util.List<Combination> combinations = new java.util.ArrayList<>();
            for (java.util.Map.Entry<Integer, List<Map<String, Object>>> priceEntry : priceGroups.entrySet()) {
                Integer price = priceEntry.getKey();
                List<Map<String, Object>> samePriceRows = priceEntry.getValue();

                // Merge stock for items with same name and same price
                int totalStockForPrice = samePriceRows.stream().mapToInt(r -> toInteger(r.get("JEGO"))).sum();
                String firstErpCode = String.valueOf(samePriceRows.get(0).get("CODE"));

                String comboName = name;
                if (priceGroups.size() > 1) {
                    comboName = String.format("%s (%,d원)", name, price);
                }

                combinations.add(Combination.builder()
                        .id(firstErpCode) // Use first erpCode as internal ID
                        .name(comboName)
                        .price(price)
                        .erpCode(firstErpCode)
                        .stock(totalStockForPrice)
                        .build());
            }

            // If only one price group, merge into simple product
            if (priceGroups.size() == 1) {
                Combination single = combinations.get(0);
                product.setErpCode(single.getErpCode());
                product.setStock(single.getStock());
                product.setIsComplexOptions(false);
                product.setCombinations(new java.util.ArrayList<>());
            } else {
                product.setErpCode(null);
                product.setStock(combinations.stream().mapToInt(Combination::getStock).sum());
                product.setIsComplexOptions(true);
                product.getCombinations().clear();
                product.getCombinations().addAll(combinations);
            }

            productRepository.save(product);
            log.info("Synced product: {} (Price options: {})", name, priceGroups.size());
        }
        log.info("ERP product synchronization completed. Total items processed.");
        List<Category> cats = categoryRepository.findAll();
        for (Category c : cats) {
            log.info("Final DB Category: id={}, name={}, parent={}", c.getId(), c.getName(), c.getParentId());
        }
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 5000)
    @Transactional
    public void syncStockRealtime() {
        try {
            String stockQuery = "SELECT CODE, JEGO FROM ITEM WHERE CODE >= 100";
            List<Map<String, Object>> erpStocks = erpJdbcTemplate.queryForList(stockQuery);

            for (Map<String, Object> row : erpStocks) {
                String code = String.valueOf(row.get("CODE"));
                Integer stock = toInteger(row.get("JEGO"));

                productRepository.updateStockByErpCode(code, stock);

                // Also update combination stock for grouped items
                primaryJdbcTemplate.update("UPDATE combinations SET stock = ? WHERE erp_code = ?", stock, code);
            }
        } catch (Exception e) {
            log.error("Failed to sync realtime stock from ERP: {}", e.getMessage());
        }
    }

    private void ensureCategoryExists(int part, int mid, int small, String name) {
        String id = String.format("erp-%d-%d-%d", part, mid, small);

        String level;
        String parentId = null;

        if (mid == 0 && small == 0) {
            level = "main";
        } else if (small == 0) {
            level = "sub";
            parentId = String.format("erp-%d-0-0", part);
            ensureCategoryExists(part, 0, 0, "ERP " + part);
        } else {
            level = "detail";
            parentId = String.format("erp-%d-%d-0", part, mid);
            ensureCategoryExists(part, 0, 0, "ERP " + part);
            ensureCategoryExists(part, mid, 0, "ERP " + mid);
        }

        Category category = categoryRepository.findById(id).orElse(null);
        if (category != null) {
            if (category.getName() != null && category.getName().startsWith("ERP ") && !name.startsWith("ERP ")) {
                category.setName(name);
                categoryRepository.save(category);
                log.info("Updated existing category: id={}, name={}", category.getId(), category.getName());
            } else {
                log.info("Category exists, not updating: id={}, name={}", category.getId(), category.getName());
            }
            return;
        }

        category = Category.builder()
                .id(id)
                .name(name)
                .level(level)
                .parentId(parentId)
                .build();
        categoryRepository.save(category);
        log.info("Created category: id={}, name={}, level={}, parent={}", category.getId(), category.getName(),
                category.getLevel(), category.getParentId());
    }

    @Transactional
    public void sendOrderToErp(Order order) {
        log.info("Sending order #{} to ERP...", order.getId());

        String sujuDateStr = order.getTimestamp()
                .format(java.time.format.DateTimeFormatter.ofPattern("yy.MM.dd"));
        String yy = order.getTimestamp().format(java.time.format.DateTimeFormatter.ofPattern("yy"));
        String ilTable = "IL" + yy;

        for (com.example.demo.entity.OrderItem item : order.getItems()) {
            try {
                // If erpCode is missing, we can't sync it properly
                if (item.getErpCode() == null || item.getErpCode().isEmpty()) {
                    log.warn("OrderItem {} has no ERP code. Skipping ERP sync for this item.", item.getName());
                    continue;
                }

                String custCode = order.getErpCustomerCode() != null && !order.getErpCustomerCode().isEmpty()
                        ? order.getErpCustomerCode()
                        : "1";

                // 1) Insert into SUJU
                String insertSuju = "INSERT INTO SUJU (dDATE, ITEMCODE, PRICE, EA, BALJUNO, CUST) VALUES (?, ?, ?, ?, ?, ?)";
                erpJdbcTemplate.update(insertSuju,
                        sujuDateStr,
                        item.getErpCode(),
                        item.getFinalPrice(),
                        item.getQuantity() != null ? item.getQuantity() : 1,
                        "KIOSK-" + order.getId(),
                        custCode);

                // 2) Insert into ILxx (Transaction History so it shows in '최근 거래')
                // GUM = PRICE * EA
                int ea = item.getQuantity() != null ? item.getQuantity() : 1;
                long gum = (long) item.getFinalPrice() * ea;

                String insertIl = "INSERT INTO " + ilTable
                        + " (dNO, EDITNO, dDATE, ITEMCODE, CUST, KIND, PRICE, EA, GUM, VAT, SA, DAECHE, EA2, BIGO, BIGO2, BIGO3, ORDERCODE, POINT, JIJOM) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

                // Fetch max dNO to auto-increment it manually since it might not be identity
                Integer maxDno = erpJdbcTemplate.queryForObject(
                        "SELECT ISNULL(MAX(dNO),0) FROM " + ilTable + " WHERE dDATE = ?", Integer.class, sujuDateStr);
                int nextDno = (maxDno != null ? maxDno : 0) + 1;

                erpJdbcTemplate.update(insertIl,
                        nextDno, // dNO
                        nextDno, // EDITNO
                        sujuDateStr, // dDATE (yy.MM.dd)
                        item.getErpCode(),
                        custCode, // CUST
                        "3", // KIND = 3 (외상매출/외출)
                        item.getFinalPrice(), // PRICE
                        ea, // EA
                        gum, // GUM
                        0, // VAT
                        0, // SA
                        0, // DAECHE
                        0, // EA2
                        "", // BIGO (Cannot be null)
                        "KIOSK-" + order.getId(), // BIGO2 for tracing
                        "", // BIGO3
                        "", // ORDERCODE
                        0, // POINT
                        0 // JIJOM
                );

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
