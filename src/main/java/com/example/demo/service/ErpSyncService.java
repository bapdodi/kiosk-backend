package com.example.demo.service;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.entity.Category;
import com.example.demo.entity.Order;
import com.example.demo.entity.Product;
import com.example.demo.repository.CategoryRepository;
import com.example.demo.repository.ProductRepository;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class ErpSyncService {

    private final JdbcTemplate jdbcTemplate;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    public ErpSyncService(@Qualifier("erpJdbcTemplate") JdbcTemplate jdbcTemplate,
            ProductRepository productRepository,
            CategoryRepository categoryRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
    }

    @Transactional
    public void syncProducts() {
        log.info("Starting ERP product synchronization...");

        // 1. Get Categories from PARTCODE table
        String categoryQuery = "SELECT PARTCODE, MIDCODE, SMALLCODE, PART FROM PARTCODE";
        List<Map<String, Object>> erpCategories = jdbcTemplate.queryForList(categoryQuery);

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
        String itemQuery = "SELECT CODE, ITEM, OUTPR, PARTCODE, MIDCODE, SMALLCODE FROM [ITEM]";
        List<Map<String, Object>> erpItems = jdbcTemplate.queryForList(itemQuery);

        for (Map<String, Object> itemRow : erpItems) {
            String code = String.valueOf(itemRow.get("CODE"));
            String name = (String) itemRow.get("ITEM");

            Integer price = toInteger(itemRow.get("OUTPR"));
            Integer part = toInteger(itemRow.get("PARTCODE"));
            Integer mid = toInteger(itemRow.get("MIDCODE"));
            Integer small = toInteger(itemRow.get("SMALLCODE"));

            String mainCatId = String.format("erp-%d-0-0", part);
            String subCatId = String.format("erp-%d-%d-0", part, mid);
            String detailCatId = String.format("erp-%d-%d-%d", part, mid, small);

            // Ensure categories exist even if not in PARTCODE
            ensureCategoryExists(part, 0, 0, "ERP " + part);
            if (mid != 0)
                ensureCategoryExists(part, mid, 0, "ERP " + mid);
            if (small != 0)
                ensureCategoryExists(part, mid, small, "ERP Item " + code);

            productRepository.findByErpCode(code).ifPresentOrElse(
                    product -> {
                        if (product != null) {
                            // Update existing
                            product.setName(name != null ? name.trim() : product.getName());
                            product.setPrice(price);
                            product.setMainCategory(mainCatId);
                            product.setSubCategory(subCatId);
                            product.setDetailCategory(detailCatId);
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
                                .mainCategory(mainCatId)
                                .subCategory(subCatId)
                                .detailCategory(detailCatId)
                                .isComplexOptions(false)
                                .build();
                        if (newProduct != null) {
                            productRepository.save(newProduct);
                            log.info("Created new product: {} from ERP", name);
                        }
                    });
        }
        log.info("ERP product synchronization completed. Total items processed.");
        List<Category> cats = categoryRepository.findAll();
        for (Category c : cats) {
            log.info("Final DB Category: id={}, name={}, parent={}", c.getId(), c.getName(), c.getParentId());
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
