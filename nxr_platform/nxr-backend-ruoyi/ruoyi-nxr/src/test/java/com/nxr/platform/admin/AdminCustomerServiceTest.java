package com.nxr.platform.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

class AdminCustomerServiceTest {

    private JdbcTemplate jdbcTemplate;
    private AdminCustomerService service;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:nxr_admin_customers;MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbcTemplate = new JdbcTemplate(dataSource);
        resetSchema();
        seedData();
        service = new AdminCustomerService(JdbcClient.create(jdbcTemplate));
    }

    @Test
    void listsCustomerCountsAndLoadsOwnershipHistory() {
        AdminCustomerService.CustomerListResponse response = service.listCustomers(1, 20, "active", "alice");

        assertThat(response.total()).isEqualTo(1);
        assertThat(response.items()).singleElement().satisfies(customer -> {
            assertThat(customer.email()).isEqualTo("alice@example.test");
            assertThat(customer.activeCardCount()).isEqualTo(1);
            assertThat(customer.ownershipCount()).isEqualTo(2);
            assertThat(customer.orderCount()).isEqualTo(1);
            assertThat(customer.activeSessionCount()).isEqualTo(1);
        });

        AdminCustomerService.CustomerDetailResponse detail = service.requireCustomer(1L);
        assertThat(detail.cards()).hasSize(2);
        assertThat(detail.ownershipEvents()).hasSize(1);
        assertThat(detail.orders()).singleElement().satisfies(order ->
            assertThat(order.orderNo()).isEqualTo("NXR-TEST-1")
        );
    }

    @Test
    void deactivatingCustomerRevokesSessionsWithoutDeletingBusinessRecords() {
        AdminCustomerService.CustomerDetailResponse detail = service.updateCustomerStatus(
            1L,
            new AdminCustomerService.UpdateCustomerStatusRequest(false)
        );

        assertThat(detail.customer().active()).isFalse();
        assertThat(detail.customer().activeSessionCount()).isZero();
        assertThat(count("customer_session")).isZero();
        assertThat(count("certificate_ownership")).isEqualTo(2);
        assertThat(count("grading_order")).isEqualTo(1);
    }

    @Test
    void changesCustomerTypeWithoutChangingBusinessRecords() {
        AdminCustomerService.CustomerDetailResponse detail = service.updateCustomerType(
            1L,
            new AdminCustomerService.UpdateCustomerTypeRequest("merchant")
        );

        assertThat(detail.customer().accountTypeCode()).isEqualTo("merchant");
        assertThat(count("certificate_ownership")).isEqualTo(2);
        assertThat(count("grading_order")).isEqualTo(1);
    }

    private void resetSchema() {
        jdbcTemplate.execute("DROP ALL OBJECTS");
        jdbcTemplate.execute(
            """
            CREATE TABLE customer_account (
                id BIGINT PRIMARY KEY,
                email VARCHAR(191) NOT NULL,
                display_name VARCHAR(128) NOT NULL,
                mobile VARCHAR(64),
                account_type_code VARCHAR(32) NOT NULL DEFAULT 'customer',
                is_active TINYINT NOT NULL,
                created_at TIMESTAMP NOT NULL,
                last_login_at TIMESTAMP
            )
            """
        );
        jdbcTemplate.execute(
            """
            CREATE TABLE customer_session (
                id BIGINT PRIMARY KEY,
                customer_id BIGINT NOT NULL,
                expires_at TIMESTAMP NOT NULL
            )
            """
        );
        jdbcTemplate.execute(
            """
            CREATE TABLE certificate_ownership (
                id BIGINT PRIMARY KEY,
                cert_id VARCHAR(32) NOT NULL,
                customer_id BIGINT NOT NULL,
                ownership_status_code VARCHAR(32) NOT NULL,
                visibility_code VARCHAR(32) NOT NULL,
                note TEXT,
                bound_at TIMESTAMP NOT NULL,
                released_at TIMESTAMP
            )
            """
        );
        jdbcTemplate.execute(
            """
            CREATE TABLE certificate_ownership_event (
                id BIGINT PRIMARY KEY,
                cert_id VARCHAR(32) NOT NULL,
                from_customer_id BIGINT,
                to_customer_id BIGINT,
                event_type_code VARCHAR(32) NOT NULL,
                visibility_code VARCHAR(32) NOT NULL,
                message TEXT,
                created_at TIMESTAMP NOT NULL
            )
            """
        );
        jdbcTemplate.execute(
            """
            CREATE TABLE grading_submission (
                id BIGINT PRIMARY KEY,
                product_type_code VARCHAR(32) NOT NULL DEFAULT 'graded_card',
                vintage_classification_code VARCHAR(64),
                merch_description TEXT,
                card_name VARCHAR(255) NOT NULL,
                brand_name VARCHAR(64) NOT NULL
            )
            """
        );
        jdbcTemplate.execute(
            """
            CREATE TABLE grading_score (
                submission_id BIGINT PRIMARY KEY,
                final_grade_value DECIMAL(4,1),
                final_grade_label VARCHAR(64)
            )
            """
        );
        jdbcTemplate.execute(
            """
            CREATE TABLE published_certificate (
                id BIGINT PRIMARY KEY,
                submission_id BIGINT NOT NULL,
                cert_id VARCHAR(32) NOT NULL
            )
            """
        );
        jdbcTemplate.execute(
            """
            CREATE TABLE grading_order (
                id BIGINT PRIMARY KEY,
                order_no VARCHAR(40) NOT NULL,
                customer_id BIGINT NOT NULL,
                status_code VARCHAR(32) NOT NULL,
                service_level_code VARCHAR(32) NOT NULL,
                total_card_count INT NOT NULL,
                total_amount DECIMAL(12,2) NOT NULL,
                currency_code VARCHAR(8) NOT NULL,
                created_at TIMESTAMP NOT NULL,
                updated_at TIMESTAMP NOT NULL
            )
            """
        );
    }

    private void seedData() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        jdbcTemplate.update(
            "INSERT INTO customer_account VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            1L, "alice@example.test", "Alice", "10086", "customer", 1, now.minusDays(10), now.minusHours(1)
        );
        jdbcTemplate.update(
            "INSERT INTO customer_account VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            2L, "bob@example.test", "Bob", null, "customer", 0, now.minusDays(5), null
        );
        jdbcTemplate.update(
            "INSERT INTO customer_session VALUES (?, ?, ?)",
            1L, 1L, now.plusDays(1)
        );
        jdbcTemplate.update(
            "INSERT INTO customer_session VALUES (?, ?, ?)",
            2L, 1L, now.minusDays(1)
        );
        jdbcTemplate.update(
            "INSERT INTO grading_submission (id, card_name, brand_name) VALUES (?, ?, ?)",
            10L, "Test Card", "Pokemon"
        );
        jdbcTemplate.update(
            "INSERT INTO grading_score VALUES (?, ?, ?)",
            10L, new BigDecimal("9.5"), "Gem Mint"
        );
        jdbcTemplate.update(
            "INSERT INTO published_certificate VALUES (?, ?, ?)",
            20L, 10L, "CERT-001"
        );
        jdbcTemplate.update(
            "INSERT INTO certificate_ownership VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            30L, "CERT-001", 1L, "active", "public", "current", now.minusDays(2), null
        );
        jdbcTemplate.update(
            "INSERT INTO certificate_ownership VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            31L, "CERT-OLD", 1L, "transferred", "public", "previous", now.minusDays(8), now.minusDays(3)
        );
        jdbcTemplate.update(
            "INSERT INTO certificate_ownership_event VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            40L, "CERT-001", 2L, 1L, "transferred", "public", "handoff", now.minusDays(2)
        );
        jdbcTemplate.update(
            "INSERT INTO grading_order VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            50L, "NXR-TEST-1", 1L, "grading", "standard", 1, new BigDecimal("32.00"), "USD", now.minusDays(4), now
        );
    }

    private int count(String tableName) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Integer.class);
    }
}
