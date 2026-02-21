package com.example.demo.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class ErpDatabaseConfig {

    @Value("${erp.datasource.url}")
    private String url;

    @Value("${erp.datasource.username}")
    private String username;

    @Value("${erp.datasource.password}")
    private String password;

    @Bean(name = "erpDataSource")
    public DataSource erpDataSource() {
        return DataSourceBuilder.create()
                .url(url)
                .username(username)
                .password(password)
                .driverClassName("com.microsoft.sqlserver.jdbc.SQLServerDriver")
                .build();
    }

    @Bean(name = "erpJdbcTemplate")
    public JdbcTemplate erpJdbcTemplate() {
        DataSource ds = erpDataSource();
        if (ds == null) {
            throw new RuntimeException("Failed to create ERP DataSource");
        }
        return new JdbcTemplate(ds);
    }
}
