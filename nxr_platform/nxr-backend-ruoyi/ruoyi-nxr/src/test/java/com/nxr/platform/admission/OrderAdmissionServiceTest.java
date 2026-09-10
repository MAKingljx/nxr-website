package com.nxr.platform.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class OrderAdmissionServiceTest {

    private JdbcClient jdbc;
    private OrderAdmissionService service;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:order-admission;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""
        );
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.execute("DROP ALL OBJECTS");
        template.execute(
            """
            CREATE TABLE grading_order (
              id BIGINT PRIMARY KEY, order_no VARCHAR(40) NOT NULL, customer_id BIGINT NOT NULL,
              status_code VARCHAR(32) NOT NULL, admission_status_code VARCHAR(32), admission_revision INT NOT NULL,
              admission_submitted_at TIMESTAMP, admission_decided_at TIMESTAMP, admission_decision_note CLOB,
              admission_reviewed_by_user_id BIGINT, payment_due_at TIMESTAMP, payment_deadline_status_code VARCHAR(24),
              approved_terms_version VARCHAR(64), approved_terms_text CLOB, approved_turnaround_text VARCHAR(1000),
              approved_quote_amount DECIMAL(12,2), approved_quote_currency VARCHAR(8),
              accepted_terms_version VARCHAR(64), terms_accepted_at TIMESTAMP,
              total_amount DECIMAL(12,2) NOT NULL, currency_code VARCHAR(8) NOT NULL, updated_at TIMESTAMP
            );
            CREATE TABLE order_admission_config (
              config_id TINYINT PRIMARY KEY, payment_deadline_hours INT NOT NULL, max_cards_per_order INT NOT NULL,
              terms_version VARCHAR(64) NOT NULL, terms_text CLOB NOT NULL, turnaround_text VARCHAR(1000) NOT NULL,
              config_version INT NOT NULL, updated_by_user_id BIGINT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
              updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            );
            CREATE TABLE order_admission_event (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, order_id BIGINT NOT NULL, event_code VARCHAR(32) NOT NULL,
              title VARCHAR(255) NOT NULL, detail CLOB, actor_type_code VARCHAR(32) NOT NULL,
              actor_customer_id BIGINT, actor_admin_user_id BIGINT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            );
            CREATE TABLE order_terms_acceptance (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, order_id BIGINT NOT NULL, customer_id BIGINT NOT NULL,
              terms_version VARCHAR(64) NOT NULL, accepted_quote_amount DECIMAL(12,2) NOT NULL,
              accepted_quote_currency VARCHAR(8) NOT NULL, accepted_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
              UNIQUE(order_id, terms_version)
            );
            CREATE TABLE order_admission_supplemental_photo (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, order_id BIGINT NOT NULL, photo_id BIGINT NOT NULL,
              submitted_by_customer_id BIGINT NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
              UNIQUE(order_id, photo_id)
            );
            CREATE TABLE payment_record (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, order_id BIGINT NOT NULL, status_code VARCHAR(32) NOT NULL
            );
            CREATE TABLE payment_attempt (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, order_id BIGINT NOT NULL, active_order_id BIGINT,
              status_code VARCHAR(32) NOT NULL
            )
            """
        );
        template.update(
            "INSERT INTO order_admission_config(config_id,payment_deadline_hours,max_cards_per_order,terms_version,terms_text,turnaround_text,config_version) VALUES(1,48,500,'terms-v1','Terms v1','以受理确认为准',1)"
        );
        template.update(
            "INSERT INTO grading_order(id,order_no,customer_id,status_code,admission_status_code,admission_revision,admission_submitted_at,payment_deadline_status_code,total_amount,currency_code) VALUES(10,'NXR-100',7,'admission_review','pending_review',1,CURRENT_TIMESTAMP,'not_started',100.00,'USD')"
        );
        template.update("INSERT INTO payment_record(order_id,status_code) VALUES(10,'pending')");
        jdbc = JdbcClient.create(dataSource);
        service = new OrderAdmissionService(
            jdbc, null, Clock.fixed(Instant.parse("2026-09-08T02:00:00Z"), ZoneOffset.UTC)
        );
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    void approvalFreezesQuoteAndTermsAndRequiresExactCustomerAcceptance() {
        assertThatThrownBy(() -> tx(() -> {
            service.requirePaymentAllowed(10L, 7L);
            return null;
        })).isInstanceOf(ResponseStatusException.class).hasMessageContaining("Admission approval");

        OrderAdmissionService.AdmissionResponse approved = tx(() -> service.decide(
            10L, 9L, new OrderAdmissionService.DecisionRequest("approve", "Accepted for grading", 1)
        ));
        assertThat(approved.admissionStatus()).isEqualTo("approved");
        assertThat(approved.admissionRevision()).isEqualTo(2);
        assertThat(approved.quoteAmount()).isEqualByComparingTo("100.00");
        assertThat(approved.termsVersion()).isEqualTo("terms-v1");
        assertThat(approved.turnaroundText()).isEqualTo("以受理确认为准");
        assertThat(approved.paymentDueAtIso()).isEqualTo("2026-09-10T02:00Z");

        jdbc.sql("UPDATE grading_order SET total_amount=200.00 WHERE id=10").update();
        jdbc.sql("UPDATE order_admission_config SET terms_version='terms-v2',terms_text='Terms v2' WHERE config_id=1").update();
        OrderAdmissionService.AdmissionResponse stillFrozen = service.requireAdminAdmission(10L);
        assertThat(stillFrozen.quoteAmount()).isEqualByComparingTo("100.00");
        assertThat(stillFrozen.termsVersion()).isEqualTo("terms-v1");
        jdbc.sql("UPDATE grading_order SET total_amount=100.00 WHERE id=10").update();

        assertThatThrownBy(() -> tx(() -> service.acceptTerms(
            7L, "NXR-100", new OrderAdmissionService.AcceptTermsRequest("terms-v1", new BigDecimal("200.00"), "USD")
        ))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("do not match");

        OrderAdmissionService.AdmissionResponse accepted = tx(() -> service.acceptTerms(
            7L, "NXR-100", new OrderAdmissionService.AcceptTermsRequest("terms-v1", new BigDecimal("100.00"), "USD")
        ));
        assertThat(accepted.canPay()).isTrue();
        assertThat(accepted.admissionRevision()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT status_code FROM grading_order WHERE id=10").query(String.class).single())
            .isEqualTo("awaiting_payment");
        tx(() -> {
            service.requirePaymentAllowed(10L, 7L);
            return null;
        });

        assertThatThrownBy(() -> tx(() -> service.decide(
            10L, 9L, new OrderAdmissionService.DecisionRequest("request_information", "Stale review", 1)
        ))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("refresh");
    }

    @Test
    void informationRequestCanBeResubmittedButRejectedOrderCannot() {
        OrderAdmissionService.AdmissionResponse needsInfo = tx(() -> service.decide(
            10L, 9L, new OrderAdmissionService.DecisionRequest("request_information", "Add a clearer list", 1)
        ));
        assertThat(needsInfo.canResubmit()).isTrue();
        assertThatThrownBy(() -> tx(() -> service.decide(
            10L, 9L, new OrderAdmissionService.DecisionRequest("approve", "Cannot skip resubmission", 2)
        ))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("current state");
        assertThatThrownBy(() -> tx(() -> {
            service.requireGenericStatusChangeAllowed(10L);
            return null;
        })).isInstanceOf(ResponseStatusException.class).hasMessageContaining("admission workflow");
        OrderAdmissionService.AdmissionResponse resubmitted = tx(() -> service.resubmit(
            7L, "NXR-100", new OrderAdmissionService.ResubmitRequest("Added the missing set details")
        ));
        assertThat(resubmitted.admissionStatus()).isEqualTo("pending_review");
        assertThat(resubmitted.admissionRevision()).isEqualTo(3);

        OrderAdmissionService.AdmissionResponse rejected = tx(() -> service.decide(
            10L, 9L, new OrderAdmissionService.DecisionRequest("reject", "Unsupported submission", 3)
        ));
        assertThat(rejected.canResubmit()).isFalse();
        assertThatThrownBy(() -> tx(() -> service.resubmit(
            7L, "NXR-100", new OrderAdmissionService.ResubmitRequest("Try again")
        ))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("awaiting more information");
    }

    @Test
    void expiryBlocksNewPaymentAndKeepsVerifiedFundsForReview() {
        tx(() -> service.decide(10L, 9L, new OrderAdmissionService.DecisionRequest("approve", "Approved", 1)));
        tx(() -> service.acceptTerms(
            7L, "NXR-100", new OrderAdmissionService.AcceptTermsRequest("terms-v1", new BigDecimal("100.00"), "USD")
        ));
        jdbc.sql("UPDATE grading_order SET payment_due_at=TIMESTAMP '2026-09-08 01:00:00' WHERE id=10").update();

        assertThatThrownBy(() -> tx(() -> {
            service.requirePaymentAllowed(10L, 7L);
            return null;
        })).isInstanceOf(ResponseStatusException.class).hasMessageContaining("active payment deadline");
        assertThat(tx(() -> service.verifiedPaymentRequiresReview(10L))).isTrue();

        tx(() -> {
            service.expireDuePayments();
            return null;
        });
        assertThat(jdbc.sql("SELECT status_code FROM grading_order WHERE id=10").query(String.class).single())
            .isEqualTo("payment_expired");
        assertThat(jdbc.sql("SELECT event_code FROM order_admission_event ORDER BY id DESC LIMIT 1").query(String.class).single())
            .isEqualTo("payment_expired");
    }

    @Test
    void paidOrActiveGatewayOrderCannotBeReapprovedOrReturnedToTermsConfirmation() {
        tx(() -> service.decide(10L, 9L, new OrderAdmissionService.DecisionRequest("approve", "Approved", 1)));
        tx(() -> service.acceptTerms(
            7L, "NXR-100", new OrderAdmissionService.AcceptTermsRequest("terms-v1", new BigDecimal("100.00"), "USD")
        ));
        jdbc.sql("UPDATE grading_order SET status_code='awaiting_inbound' WHERE id=10").update();
        jdbc.sql("UPDATE payment_record SET status_code='confirmed' WHERE order_id=10").update();

        assertThatThrownBy(() -> tx(() -> service.decide(
            10L, 9L, new OrderAdmissionService.DecisionRequest("approve", "Do not reopen", 3)
        ))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("current state");
        assertThatThrownBy(() -> tx(() -> service.acceptTerms(
            7L, "NXR-100", new OrderAdmissionService.AcceptTermsRequest("terms-v1", new BigDecimal("100.00"), "USD")
        ))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("payment processing");

        jdbc.sql("UPDATE payment_record SET status_code='pending' WHERE order_id=10").update();
        jdbc.sql("UPDATE grading_order SET status_code='payment_expired' WHERE id=10").update();
        jdbc.sql("INSERT INTO payment_attempt(order_id,active_order_id,status_code) VALUES(10,10,'creation_unknown')").update();
        assertThatThrownBy(() -> tx(() -> service.decide(
            10L, 9L, new OrderAdmissionService.DecisionRequest("approve", "Unsafe renewal", 3)
        ))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("payment processing");
    }

    @Test
    void legacyOrderWithoutAdmissionMetadataRemainsPayableOnlyInPaymentStates() {
        jdbc.sql(
                "INSERT INTO grading_order(id,order_no,customer_id,status_code,admission_status_code,admission_revision,payment_deadline_status_code,total_amount,currency_code) "
                    + "VALUES(11,'NXR-LEGACY',7,'awaiting_payment',NULL,0,NULL,80.00,'USD')"
            )
            .update();
        OrderAdmissionService.AdmissionResponse payable = tx(() -> service.requireCustomerAdmission(7L, "NXR-LEGACY"));
        assertThat(payable.legacyOrder()).isTrue();
        assertThat(payable.canPay()).isTrue();

        jdbc.sql("UPDATE grading_order SET status_code='awaiting_inbound' WHERE id=11").update();
        OrderAdmissionService.AdmissionResponse paid = tx(() -> service.requireCustomerAdmission(7L, "NXR-LEGACY"));
        assertThat(paid.legacyOrder()).isTrue();
        assertThat(paid.canPay()).isFalse();
    }

    private <T> T tx(Supplier<T> action) {
        return transactions.execute(status -> action.get());
    }
}
