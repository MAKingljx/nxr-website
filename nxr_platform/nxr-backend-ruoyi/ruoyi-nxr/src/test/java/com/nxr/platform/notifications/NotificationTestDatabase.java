package com.nxr.platform.notifications;

import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

final class NotificationTestDatabase {

    private NotificationTestDatabase() {
    }

    static DataSource create(String name) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
            CREATE TABLE customer_account (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, email VARCHAR(191) NOT NULL UNIQUE,
              password_hash VARCHAR(100) NOT NULL, display_name VARCHAR(128) NOT NULL,
              mobile VARCHAR(64), account_type_code VARCHAR(32) NOT NULL DEFAULT 'customer',
              is_active TINYINT NOT NULL DEFAULT 1, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
              last_login_at TIMESTAMP, email_verified_at TIMESTAMP
            )
            """);
        jdbc.execute("""
            CREATE TABLE customer_session (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, customer_id BIGINT NOT NULL,
              token_hash CHAR(64) NOT NULL UNIQUE, expires_at TIMESTAMP NOT NULL,
              last_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
              created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """);
        jdbc.execute("""
            CREATE TABLE customer_account_token (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, customer_id BIGINT NOT NULL,
              purpose_code VARCHAR(32) NOT NULL, token_hash CHAR(64) NOT NULL UNIQUE,
              request_ip_hash CHAR(64) NOT NULL, expires_at TIMESTAMP NOT NULL,
              consumed_at TIMESTAMP, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """);
        jdbc.execute("""
            CREATE TABLE customer_auth_rate_event (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, action_code VARCHAR(32) NOT NULL,
              identifier_hash CHAR(64) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """);
        jdbc.execute("""
            CREATE TABLE customer_notification_outbox (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, stable_key CHAR(64) NOT NULL UNIQUE,
              customer_id BIGINT NOT NULL, notification_type_code VARCHAR(48) NOT NULL,
              encrypted_payload CLOB NOT NULL, status_code VARCHAR(16) NOT NULL DEFAULT 'pending',
              attempt_count INT NOT NULL DEFAULT 0, next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
              claimed_at TIMESTAMP, sent_at TIMESTAMP, last_error_code VARCHAR(64),
              created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """);
        return dataSource;
    }
}
