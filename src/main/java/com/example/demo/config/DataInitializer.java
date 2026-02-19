package com.example.demo.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Configuration
@RequiredArgsConstructor
public class DataInitializer {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

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

            // 제품 및 카테고리 목 데이터는 모두 제거되었습니다.
            // 사용자가 직접 DB에 데이터를 입력할 수 있는 상태입니다.
        };
    }
}