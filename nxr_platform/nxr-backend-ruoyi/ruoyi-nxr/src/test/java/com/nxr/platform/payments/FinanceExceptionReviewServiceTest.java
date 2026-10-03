package com.nxr.platform.payments;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import com.nxr.platform.customer.AgentOrderCancellationService;
import static org.mockito.Mockito.mock;

class FinanceExceptionReviewServiceTest {
    @Test
    void reviewListKeepsOrderBoundaryAndExcludesProviderPayloads() throws Exception {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:finance-review-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("""
            CREATE TABLE payment_finance_exception (
                id BIGINT PRIMARY KEY, order_id BIGINT, payment_record_id BIGINT, payment_attempt_id BIGINT,
                provider_code VARCHAR(32), provider_event_id VARCHAR(128), provider_transaction_id VARCHAR(128),
                exception_type_code VARCHAR(32), amount DECIMAL(12,2), currency_code VARCHAR(8),
                resolution_status_code VARCHAR(32), resolved_by_user_id BIGINT, resolution_note VARCHAR(200), resolved_at TIMESTAMP,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, callback_payload VARCHAR(200)
            )
            """);
        jdbc.update("INSERT INTO payment_finance_exception (id,order_id,payment_record_id,payment_attempt_id,provider_code,provider_event_id,provider_transaction_id,exception_type_code,amount,currency_code,resolution_status_code,callback_payload) VALUES (1,12,20,30,'paypal','event-1','tx-1','reversed',48.00,'USD','open','SENSITIVE PROVIDER PAYLOAD')");
        jdbc.update("INSERT INTO payment_finance_exception (id,order_id,exception_type_code,amount,currency_code,resolution_status_code) VALUES (2,13,'refunded',99,'USD','open')");
        var service = new FinanceExceptionReviewService(JdbcClient.create(jdbc), new DataSourceTransactionManager(source), mock(AgentOrderCancellationService.class));
        var list = service.listForOrder(12);
        assertThat(list).singleElement().satisfies(item -> {
            assertThat(item.orderId()).isEqualTo(12);
            assertThat(item.exceptionTypeCode()).isEqualTo("reversed");
            assertThat(item.amount()).isEqualByComparingTo("48.00");
            assertThat(item.resolutionStatusCode()).isEqualTo("open");
        });
        var json = new ObjectMapper().findAndRegisterModules().writeValueAsString(list);
        assertThat(json).doesNotContain("callback_payload", "SENSITIVE PROVIDER PAYLOAD");
        assertThat(service.listForOrder(99)).isEmpty();
    }
}
