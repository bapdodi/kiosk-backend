package com.example.demo.config;

import javax.sql.DataSource;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class PrimaryDatabaseConfig {

    @Primary
    @Bean
    @ConfigurationProperties("spring.datasource")
    public org.springframework.boot.autoconfigure.jdbc.DataSourceProperties dataSourceProperties() {
        return new org.springframework.boot.autoconfigure.jdbc.DataSourceProperties();
    }

    @Primary
    @Bean(name = "dataSource")
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource dataSource(org.springframework.boot.autoconfigure.jdbc.DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }

    @Primary
    @Bean(name = "jdbcTemplate")
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
