package com.example.demo.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Configuration
@RequiredArgsConstructor
public class DataInitializer {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;

    @Bean
    public CommandLineRunner initData() {
        return args -> {
            // User Initialization - 단 하나의 어드민 계정만 생성하거나 업데이트합니다.
            User admin = userRepository.findByUsername("admin")
                    .orElseGet(() -> User.builder()
                            .username("admin")
                            .build());

            admin.setPassword(passwordEncoder.encode("admin123"));
            admin.setRole("ROLE_ADMIN");
            userRepository.save(admin);

            System.out.println("Default admin user updated/created: admin / admin123");

            migrateLegacyCategories();

            // 제품 및 카테고리 목 데이터는 모두 제거되었습니다.
            // 사용자가 직접 DB에 데이터를 입력할 수 있는 상태입니다.
        };
    }

    /**
     * 단일 카테고리(products.main_category / sub_category) → 다중 카테고리(product_categories) 1회성 이전.
     * - 기존 단일 컬럼이 아직 남아 있을 때만 동작한다(신규 DB 에서는 건너뜀).
     * - 이미 product_categories 행이 있는 상품은 건드리지 않아 멱등하다.
     */
    private void migrateLegacyCategories() {
        try {
            Integer hasLegacyColumn = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns " +
                            "WHERE table_name = 'products' AND column_name = 'main_category'",
                    Integer.class);

            if (hasLegacyColumn == null || hasLegacyColumn == 0) {
                return; // 신규 DB: 이전할 레거시 데이터 없음
            }

            int migrated = jdbcTemplate.update(
                    "INSERT INTO product_categories (product_id, main_category, sub_category) " +
                            "SELECT p.id, p.main_category, p.sub_category FROM products p " +
                            "WHERE p.main_category IS NOT NULL AND p.main_category <> '' " +
                            "AND NOT EXISTS (SELECT 1 FROM product_categories pc WHERE pc.product_id = p.id)");

            System.out.println("Migrated legacy categories for " + migrated + " product(s) into product_categories");

            // 이전이 끝났으니 더 이상 쓰이지 않는 레거시 단일 분류 컬럼을 제거해 1회성으로 만든다.
            // (남겨두면 매 기동마다 재실행되고, 사용자가 일부러 비운 상품에 옛 분류가 되살아날 수 있음)
            jdbcTemplate.execute(
                    "ALTER TABLE products DROP COLUMN IF EXISTS main_category, DROP COLUMN IF EXISTS sub_category");
        } catch (Exception e) {
            // 마이그레이션 실패가 애플리케이션 기동을 막지 않도록 로깅만 한다.
            System.err.println("Legacy category migration skipped: " + e.getMessage());
        }
    }
}