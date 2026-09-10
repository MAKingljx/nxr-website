package com.nxr.platform.customer;

import static org.assertj.core.api.Assertions.assertThat;

import com.nxr.platform.commerce.OrderAccessScopeService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class CustomerPortalAdminScopeTest {

    @Test
    void countAndPageUseTheSameActiveBusinessLineAndWorkCenterScope() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:order-scope;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""
        );
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.execute("DROP ALL OBJECTS");
        template.execute(
            """
            CREATE TABLE customer_account (
              id BIGINT PRIMARY KEY, email VARCHAR(191), display_name VARCHAR(191)
            );
            CREATE TABLE commerce_business_line (id BIGINT PRIMARY KEY, is_active TINYINT NOT NULL);
            CREATE TABLE commerce_work_center (id BIGINT PRIMARY KEY, is_active TINYINT NOT NULL);
            CREATE TABLE grading_order (
              id BIGINT PRIMARY KEY, order_no VARCHAR(40), customer_id BIGINT, status_code VARCHAR(32),
              service_level_code VARCHAR(32), return_shipping_option_code VARCHAR(64),
              return_shipping_option_name VARCHAR(128), total_card_count INT, total_amount DECIMAL(12,2),
              currency_code VARCHAR(8), business_line_id BIGINT, work_center_id BIGINT,
              created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """
        );
        template.update("INSERT INTO customer_account(id,email,display_name) VALUES(7,'merchant@example.test','Merchant')");
        template.update("INSERT INTO commerce_business_line(id,is_active) VALUES(1,1),(2,1)");
        template.update("INSERT INTO commerce_work_center(id,is_active) VALUES(10,1),(20,1)");
        insertOrder(template, 101, 1, 10);
        insertOrder(template, 102, 1, 20);
        insertOrder(template, 103, 2, 10);
        insertOrder(template, 104, null, null);

        CustomerPortalService service = new CustomerPortalService(JdbcClient.create(dataSource), template);
        OrderAccessScopeService.AccessScope scope = new OrderAccessScopeService.AccessScope(
            false, List.of(1L), List.of(10L)
        );
        CustomerPortalService.OrderListResponse visible = service.listAdminOrders(1, 20, null, null, scope);
        assertThat(visible.total()).isEqualTo(1);
        assertThat(visible.items()).extracting(CustomerPortalService.OrderListItem::id).containsExactly(101L);

        template.update("UPDATE commerce_work_center SET is_active=0 WHERE id=10");
        CustomerPortalService.OrderListResponse deactivated = service.listAdminOrders(1, 20, null, null, scope);
        assertThat(deactivated.total()).isZero();
        assertThat(deactivated.items()).isEmpty();
    }

    private static void insertOrder(JdbcTemplate template, long id, Integer lineId, Integer centerId) {
        template.update(
            "INSERT INTO grading_order(id,order_no,customer_id,status_code,service_level_code,total_card_count,total_amount,currency_code,business_line_id,work_center_id) "
                + "VALUES(?,? ,7,'admission_review','basic_grading',1,100.00,'USD',?,?)",
            id, "NXR-" + id, lineId, centerId
        );
    }
}
