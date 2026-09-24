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

            normalizeCategorySortOrder();

            // 제품 및 카테고리 목 데이터는 모두 제거되었습니다.
            // 사용자가 직접 DB에 데이터를 입력할 수 있는 상태입니다.
        };
    }

    /**
     * categories.sort_order 정규화. 과거 ERP 자동생성/초기 데이터로 만들어진 행들이
     * sort_order = 0 또는 NULL 로 몰려 있으면, 정렬 tie-break 가 id 사전순이 되어
     * ("erp-10-0-0" < "erp-2-0-0") 관리자가 정한 순서와 무관하게 화면이 흔들린다.
     *
     * 그룹(level + parent_id)별로 "지금 화면에 보이는 순서" 그대로 0..n-1 을 다시 매긴다.
     * 정렬 기준은 상품 조회 쿼리와 동일하게
     * (sort_order ASC NULLS LAST, id ASC) 라서 보이는 순서는 바뀌지 않는다.
     * 동점/NULL 이 하나도 없으면 아무것도 하지 않아 멱등하다.
     */
    private void normalizeCategorySortOrder() {
        try {
            Integer hasTable = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns " +
                            "WHERE table_name = 'categories' AND column_name = 'sort_order'",
                    Integer.class);
            if (hasTable == null || hasTable == 0) {
                return; // 아직 스키마 갱신 전 → 다음 기동에 처리된다
            }

            Integer needsFix = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM (" +
                            "  SELECT 1 FROM categories" +
                            "  GROUP BY \"level\", COALESCE(parent_id, ''), sort_order" +
                            "  HAVING COUNT(*) > 1" +
                            ") dup",
                    Integer.class);
            Integer nullCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM categories WHERE sort_order IS NULL", Integer.class);
            if ((needsFix == null || needsFix == 0) && (nullCount == null || nullCount == 0)) {
                return; // 이미 그룹별로 고유한 순서를 가짐 → 멱등
            }

            int updated = jdbcTemplate.update(
                    "UPDATE categories c SET sort_order = t.rn FROM (" +
                            "  SELECT id, (row_number() OVER (" +
                            "      PARTITION BY \"level\", COALESCE(parent_id, '')" +
                            "      ORDER BY sort_order ASC NULLS LAST, id ASC" +
                            "  ) - 1) AS rn FROM categories" +
                            ") t WHERE c.id = t.id AND c.sort_order IS DISTINCT FROM t.rn");

            System.out.println("Normalized sort_order for " + updated + " category row(s)");
        } catch (Exception e) {
            // 정규화 실패가 애플리케이션 기동을 막지 않도록 로깅만 한다.
            System.err.println("Category sort_order normalization skipped: " + e.getMessage());
        }
    }
}
