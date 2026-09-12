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
        return new JdbcTemplate(ds);
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
