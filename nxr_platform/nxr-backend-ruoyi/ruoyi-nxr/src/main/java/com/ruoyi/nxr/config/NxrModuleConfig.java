package com.ruoyi.nxr.config;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * NXR 业务模块桥接配置。
 *
 * 业务代码移植自 nxr-backend-java，保留原 com.nxr.platform 包名，
 * 通过本配置类纳入若依（com.ruoyi）的组件扫描范围。
 */
@Configuration
@ComponentScan(basePackages = "com.nxr.platform")
public class NxrModuleConfig {

    /**
     * 业务层沿用原工程的 JdbcClient 直写 SQL 风格。
     * 若依使用 Druid 动态数据源（@Primary），此处基于主数据源构建 JdbcClient。
     */
    @Bean
    public JdbcClient jdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }
}
