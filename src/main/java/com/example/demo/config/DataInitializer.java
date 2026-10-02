package com.example.demo.config;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Configuration
@Slf4j
@RequiredArgsConstructor
public class DataInitializer {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;

    /** 관리자 비밀번호. 비어 있으면 이미 있는 관리자 계정의 비밀번호는 건드리지 않는다. */
    @Value("${kiosk.admin.password:}")
    private String adminPassword;

    @Bean
    public CommandLineRunner initData() {
        return args -> {
            initAdmin();

            normalizeCategorySortOrder();

            // 제품 및 카테고리 목 데이터는 모두 제거되었습니다.
            // 사용자가 직접 DB에 데이터를 입력할 수 있는 상태입니다.
        };
    }

    /**
     * 단 하나의 관리자 계정(admin)을 준비한다.
     *
     * 예전에는 기동할 때마다 비밀번호를 소스에 적힌 고정값으로 되돌렸다. 저장소가 공개라
     * 그 값이 곧 운영 관리자 비밀번호였다. 이제 KIOSK_ADMIN_PASSWORD 를 준 경우에만 그 값으로
     * 맞추고, 없으면 기존 계정은 그대로 둔다. 계정이 아예 없으면 임의 비밀번호로 만들고 로그에 한 번 남긴다.
     */
    private void initAdmin() {
        Optional<User> existing = userRepository.findByUsername("admin");
        boolean configured = adminPassword != null && !adminPassword.isBlank();
        if (existing.isPresent() && !configured) {
            return;
        }

        User admin = existing.orElseGet(() -> User.builder().username("admin").build());
        String password = configured ? adminPassword : randomPassword();
        admin.setPassword(passwordEncoder.encode(password));
        admin.setRole("ROLE_ADMIN");
        userRepository.save(admin);

        if (configured) {
            log.info("관리자(admin) 비밀번호를 KIOSK_ADMIN_PASSWORD 값으로 맞췄습니다.");
        } else {
            log.warn("관리자(admin) 계정을 새로 만들었습니다. 임시 비밀번호: {} "
                    + "— KIOSK_ADMIN_PASSWORD 로 바꿔 두세요.", password);
        }
    }

    private static String randomPassword() {
        byte[] bytes = new byte[12];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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

            log.info("Normalized sort_order for {} category row(s)", updated);
        } catch (Exception e) {
            // 정규화 실패가 애플리케이션 기동을 막지 않도록 로깅만 한다.
            log.warn("Category sort_order normalization skipped: {}", e.getMessage());
        }
    }
}
