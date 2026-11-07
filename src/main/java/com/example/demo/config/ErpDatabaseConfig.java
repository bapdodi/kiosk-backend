package com.example.demo.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

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
        JdbcTemplate template = new JdbcTemplate(ds);
        // 경영박사가 잡은 잠금 뒤에서 끝없이 기다리면 연결 10개가 금방 차 ERP 를 쓰는 기능이 다 멈춘다.
        // 가장 무거운 새벽 추천 집계가 로컬 사본에서 3초라 60초면 넉넉하다. RESTORE 는 master 템플릿이라 해당 없음.
        template.setQueryTimeout(60);
        return template;
    }

    /**
     * RESTORE DATABASE 는 복원 대상 DB 에 연결된 상태에서는 실행할 수 없어 master 연결이 따로 필요하다.
     * ERP URL 의 databaseName 만 master 로 바꿔 재사용한다.
     */
    @Bean(name = "erpMasterDataSource")
    public DataSource erpMasterDataSource() {
        return DataSourceBuilder.create()
                .url(url.replaceAll("(?i)databaseName=[^;]*", "databaseName=master"))
                .username(username)
                .password(password)
                .driverClassName("com.microsoft.sqlserver.jdbc.SQLServerDriver")
                .build();
    }

    @Bean(name = "erpMasterJdbcTemplate")
    public JdbcTemplate erpMasterJdbcTemplate() {
        return new JdbcTemplate(erpMasterDataSource());
    }

    @Bean(name = "erpTransactionManager")
    public PlatformTransactionManager erpTransactionManager(
            @org.springframework.beans.factory.annotation.Qualifier("erpDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
