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
              terms_text CLOB,turnaround_text VARCHAR(1000),item_snapshot_hash CHAR(64) NOT NULL DEFAULT '',
              UNIQUE(order_id, terms_version,item_snapshot_hash)
            );
            CREATE TABLE order_admission_supplemental_photo (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, order_id BIGINT NOT NULL, photo_id BIGINT NOT NULL,
              submitted_by_customer_id BIGINT NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
              UNIQUE(order_id, photo_id)
            );
            CREATE TABLE payment_record (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, order_id BIGINT NOT NULL, status_code VARCHAR(32) NOT NULL,
              direction_code VARCHAR(32) DEFAULT 'receivable', payment_type_code VARCHAR(32) DEFAULT 'grading_fee'
            );
            CREATE TABLE payment_attempt (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, order_id BIGINT NOT NULL, active_order_id BIGINT,
              status_code VARCHAR(32) NOT NULL
            );
            CREATE TABLE grading_order_item(id BIGINT PRIMARY KEY,order_id BIGINT,item_no INT,card_name VARCHAR(255),
              brand_name VARCHAR(128),set_name VARCHAR(255),card_number VARCHAR(128),language_code VARCHAR(32),
              product_type VARCHAR(64),category VARCHAR(128),year_label VARCHAR(32),rarity VARCHAR(128),
              declared_value DECIMAL(12,2),item_note VARCHAR(2000));
            CREATE TABLE customer_account(id BIGINT PRIMARY KEY,account_type_code VARCHAR(32));
            CREATE TABLE merchant_order_batch(id BIGINT PRIMARY KEY,merchant_customer_id BIGINT);
            CREATE TABLE merchant_order_batch_item(id BIGINT AUTO_INCREMENT PRIMARY KEY,batch_id BIGINT,order_id BIGINT)
            """
        );
        template.update(
            "INSERT INTO order_admission_config(config_id,payment_deadline_hours,max_cards_per_order,terms_version,terms_text,turnaround_text,config_version) VALUES(1,48,500,'terms-v1','Terms v1','以受理确认为准',1)"
        );
        template.update(
            "INSERT INTO grading_order(id,order_no,customer_id,status_code,admission_status_code,admission_revision,admission_submitted_at,payment_deadline_status_code,total_amount,currency_code) VALUES(10,'NXR-100',7,'admission_review','pending_review',1,CURRENT_TIMESTAMP,'not_started',100.00,'USD')"
        );
        template.update("INSERT INTO payment_record(order_id,status_code) VALUES(10,'pending')");
        template.update("INSERT INTO customer_account(id,account_type_code) VALUES(7,'customer'),(8,'merchant')");
        template.update("INSERT INTO grading_order_item(id,order_id,item_no,card_name,product_type,category) VALUES(11,10,1,'Test card','graded_card','trading_card')");
        jdbc = JdbcClient.create(dataSource);
        service = new OrderAdmissionService(
            jdbc, null, Clock.fixed(Instant.parse("2026-09-08T02:00:00Z"), ZoneOffset.UTC)
        );
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    void partnerSubmissionRequiresAnExplicitExactCurrentContractWithoutWrites() {
        merchantOrder();
        var shown=service.merchantPublicConfig();
        for (var request:java.util.List.of(
            new OrderAdmissionService.MerchantTermsRequest(false,shown.termsVersion(),shown.termsText(),shown.turnaroundText()),
            new OrderAdmissionService.MerchantTermsRequest(null,shown.termsVersion(),shown.termsText(),shown.turnaroundText()),
            new OrderAdmissionService.MerchantTermsRequest(true,"stale",shown.termsText(),shown.turnaroundText()),
            new OrderAdmissionService.MerchantTermsRequest(true,shown.termsVersion(),"different text",shown.turnaroundText()),
            new OrderAdmissionService.MerchantTermsRequest(true,shown.termsVersion(),shown.termsText(),"different turnaround")
        )) assertThatThrownBy(()->tx(()->service.requireMerchantSubmissionTerms(request))).isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM order_terms_acceptance").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM order_admission_event").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT status_code FROM grading_order WHERE id=10").query(String.class).single()).isEqualTo("admission_review");
        assertThat(jdbc.sql("SELECT admission_revision FROM grading_order WHERE id=10").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void submissionConsentFreezesDisplayedTextAndApprovalDoesNotReplaceItOrRequestPayment() {
        merchantOrder();
        acceptSubmission();
        var original=service.requireAdminAdmission(10);
        assertThat(original.termsAcceptedAt()).isNotNull();
        assertThat(original.admissionStatus()).isEqualTo("pending_review");
        assertThat(original.canPay()).isFalse();
        jdbc.sql("UPDATE order_admission_config SET terms_text='Changed without version change',turnaround_text='Later turnaround' WHERE config_id=1").update();
        var approved=tx(()->service.decide(10,9,new OrderAdmissionService.DecisionRequest("approve","Approved",1)));
        assertThat(approved.termsVersion()).isEqualTo("terms-v1");
        assertThat(approved.termsText()).isEqualTo("Terms v1");
        assertThat(approved.turnaroundText()).isEqualTo("以受理确认为准");
        assertThat(approved.termsAcceptedAt()).isEqualTo(original.termsAcceptedAt());
        assertThat(approved.canAcceptTerms()).isFalse();assertThat(approved.canPay()).isFalse();
        assertThat(approved.paymentDueAt()).isNull();
        assertThat(jdbc.sql("SELECT status_code FROM grading_order WHERE id=10").query(String.class).single()).isEqualTo("awaiting_inbound");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM order_terms_acceptance").query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT terms_text FROM order_terms_acceptance").query(String.class).single()).isEqualTo("Terms v1");
        tx(()->{service.prepareMerchantFulfillment(10);return null;});
        assertThat(jdbc.sql("SELECT status_code FROM payment_record WHERE order_id=10").query(String.class).single()).isEqualTo("pending");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM payment_attempt").query(Integer.class).single()).isZero();
    }

    @Test
    void supplementalInformationPreservesConsentButChangedCardListRequiresNewConfirmation() {
        merchantOrder();acceptSubmission();
        var acceptedAt=service.requireAdminAdmission(10).termsAcceptedAt();
        tx(()->service.decide(10,9,new OrderAdmissionService.DecisionRequest("request_information","Please provide clearer photos",1)));
        assertThat(service.requireAdminAdmission(10).termsAcceptedAt()).isEqualTo(acceptedAt);
        var resubmitted=tx(()->service.resubmit(7,"NXR-100",new OrderAdmissionService.ResubmitRequest("Additional information")));
        assertThat(resubmitted.termsAcceptedAt()).isEqualTo(acceptedAt);
        jdbc.sql("UPDATE grading_order_item SET card_name='Corrected card' WHERE id=11").update();
        var approved=tx(()->service.decide(10,9,new OrderAdmissionService.DecisionRequest("approve","Corrected list approved",resubmitted.admissionRevision())));
        assertThat(approved.canAcceptTerms()).isTrue();assertThat(approved.termsAcceptedAt()).isNull();
        assertThat(jdbc.sql("SELECT status_code FROM grading_order WHERE id=10").query(String.class).single()).isEqualTo("terms_confirmation");
        assertThat(jdbc.sql("SELECT accepted_at FROM order_terms_acceptance").query(java.time.LocalDateTime.class).single()).isEqualTo(acceptedAt);
        tx(()->service.acceptTerms(7,"NXR-100",new OrderAdmissionService.AcceptTermsRequest("terms-v1",null,null)));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM order_terms_acceptance").query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT COUNT(DISTINCT item_snapshot_hash) FROM order_terms_acceptance").query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT status_code FROM grading_order WHERE id=10").query(String.class).single()).isEqualTo("awaiting_inbound");
    }

    @Test
    void unchangedCardListAfterInformationRequestKeepsOriginalConsent() {
        merchantOrder();acceptSubmission();
        var original=service.requireAdminAdmission(10);
        tx(()->service.decide(10,9,new OrderAdmissionService.DecisionRequest("request_information","Please explain",1)));
        var resubmitted=tx(()->service.resubmit(7,"NXR-100",new OrderAdmissionService.ResubmitRequest("Explanation only")));
        jdbc.sql("UPDATE order_admission_config SET terms_version='terms-v2',terms_text='Terms v2' WHERE config_id=1").update();
        var approved=tx(()->service.decide(10,9,new OrderAdmissionService.DecisionRequest("approve","Approved",resubmitted.admissionRevision())));
        assertThat(approved.termsVersion()).isEqualTo("terms-v1");assertThat(approved.termsAcceptedAt()).isEqualTo(original.termsAcceptedAt());
        assertThat(approved.canAcceptTerms()).isFalse();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM order_terms_acceptance").query(Integer.class).single()).isOne();
    }

    @Test
    void rejectionPreservesHistoricalConsentAndNeverPermitsShipping() {
        merchantOrder();acceptSubmission();
        var original=service.requireAdminAdmission(10);
        var rejected=tx(()->service.decide(10,9,new OrderAdmissionService.DecisionRequest("reject","Cannot accept",1)));
        assertThat(rejected.termsAcceptedAt()).isEqualTo(original.termsAcceptedAt());
        assertThat(rejected.termsVersion()).isEqualTo("terms-v1");
        assertThatThrownBy(()->tx(()->{service.prepareMerchantFulfillment(10);return null;})).isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM order_terms_acceptance").query(Integer.class).single()).isOne();
    }

    @Test
    void failedSubmissionTransactionRollsBackConsentAndItsAuditTogether() {
        merchantOrder();
        assertThatThrownBy(()->tx(()->{recordSubmissionConsent();throw new IllegalStateException("Later item linkage failed");}))
            .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM order_terms_acceptance").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM order_admission_event").query(Integer.class).single()).isZero();
        assertThat(service.requireAdminAdmission(10).termsAcceptedAt()).isNull();
    }

    private void acceptSubmission() {
        tx(()->{recordSubmissionConsent();return null;});
    }

    private void recordSubmissionConsent() {
        var shown=service.merchantPublicConfig();
        var accepted=service.requireMerchantSubmissionTerms(new OrderAdmissionService.MerchantTermsRequest(
            true,shown.termsVersion(),shown.termsText(),shown.turnaroundText()));
        service.recordMerchantSubmissionTerms(10,7,accepted);
    }

    @Test
    void merchantReviewAndTermsNeedNoPaymentOrDeadlineAndKeepFinancialHistoryUnchanged() {
        merchantOrder();
        jdbc.sql("UPDATE payment_record SET status_code='proof_submitted' WHERE order_id=10").update();
        var approved=tx(()->service.decide(10,9,new OrderAdmissionService.DecisionRequest("approve","Approved service",1)));
        assertThat(approved.paymentRequired()).isFalse();assertThat(approved.paymentDueAt()).isNull();
        assertThat(approved.canPay()).isFalse();assertThat(approved.canAcceptTerms()).isTrue();
        assertThat(jdbc.sql("SELECT payment_deadline_status_code FROM grading_order WHERE id=10").query(String.class).single()).isEqualTo("not_required");
        assertThatThrownBy(()->tx(()->service.acceptTerms(7,"NXR-100",new OrderAdmissionService.AcceptTermsRequest("wrong",null,null))))
            .hasMessageContaining("terms do not match");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM order_terms_acceptance").query(Integer.class).single()).isZero();
        var accepted=tx(()->service.acceptTerms(7,"NXR-100",new OrderAdmissionService.AcceptTermsRequest("terms-v1",null,null)));
        assertThat(accepted.canPay()).isFalse();assertThat(accepted.termsAcceptedAt()).isNotNull();
        assertThat(jdbc.sql("SELECT status_code FROM grading_order WHERE id=10").query(String.class).single()).isEqualTo("awaiting_inbound");
        assertThat(jdbc.sql("SELECT status_code FROM payment_record WHERE order_id=10").query(String.class).single()).isEqualTo("proof_submitted");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM payment_attempt").query(Integer.class).single()).isZero();
        tx(()->{service.requireGenericStatusChangeAllowed(10);return null;});
        assertThatThrownBy(()->tx(()->{service.requirePaymentAllowed(10,7);return null;})).hasMessageContaining("do not require payment");
    }

    @Test
    void legacyMerchantExpiryIsReadOnlyUntilAnExplicitTermsOrShippingCommand() {
        merchantOrder();
        tx(()->service.decide(10,9,new OrderAdmissionService.DecisionRequest("approve","Approved",1)));
        jdbc.sql("UPDATE grading_order SET status_code='payment_expired',payment_deadline_status_code='expired',payment_due_at=TIMESTAMP '2026-09-01 00:00:00' WHERE id=10").update();
        int events=jdbc.sql("SELECT COUNT(*) FROM order_admission_event").query(Integer.class).single();
        var response=tx(()->service.requireCustomerAdmission(7,"NXR-100"));
        assertThat(response.canAcceptTerms()).isTrue();assertThat(response.paymentExpired()).isFalse();assertThat(response.paymentDueAt()).isNull();
        tx(()->{service.expireDuePayments();return null;});
        assertThat(jdbc.sql("SELECT status_code FROM grading_order WHERE id=10").query(String.class).single()).isEqualTo("payment_expired");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM order_admission_event").query(Integer.class).single()).isEqualTo(events);
        assertThatThrownBy(()->tx(()->{service.prepareMerchantFulfillment(10);return null;})).hasMessageContaining("accepted service terms");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM order_terms_acceptance").query(Integer.class).single()).isZero();
        tx(()->service.acceptTerms(7,"NXR-100",new OrderAdmissionService.AcceptTermsRequest("terms-v1",null,null)));
        jdbc.sql("UPDATE grading_order SET status_code='awaiting_payment',payment_deadline_status_code='active',payment_due_at=TIMESTAMP '2026-09-01 00:00:00' WHERE id=10").update();
        tx(()->{service.prepareMerchantFulfillment(10);return null;});
        assertThat(jdbc.sql("SELECT status_code FROM grading_order WHERE id=10").query(String.class).single()).isEqualTo("awaiting_inbound");
        assertThat(jdbc.sql("SELECT payment_due_at FROM grading_order WHERE id=10").query(java.time.LocalDateTime.class).optional()).isEmpty();
        assertThat(jdbc.sql("SELECT status_code FROM payment_record WHERE order_id=10").query(String.class).single()).isEqualTo("pending");
    }

    @Test
    void foreignBatchOrMerchantAccountAloneNeverBypassesConsumerPayment() {
        jdbc.sql("UPDATE customer_account SET account_type_code='merchant' WHERE id=7").update();
        jdbc.sql("INSERT INTO merchant_order_batch(id,merchant_customer_id) VALUES(1,8)").update();
        jdbc.sql("INSERT INTO merchant_order_batch_item(batch_id,order_id) VALUES(1,10)").update();
        var approved=tx(()->service.decide(10,9,new OrderAdmissionService.DecisionRequest("approve","Approved",1)));
        assertThat(approved.paymentRequired()).isTrue();assertThat(approved.paymentDueAt()).isNotNull();
        assertThatThrownBy(()->tx(()->{service.prepareMerchantFulfillment(10);return null;})).hasMessageContaining("partner batch order");
        var accepted=tx(()->service.acceptTerms(7,"NXR-100",new OrderAdmissionService.AcceptTermsRequest("terms-v1",new BigDecimal("100.00"),"USD")));
        assertThat(accepted.canPay()).isTrue();
        assertThat(jdbc.sql("SELECT status_code FROM grading_order WHERE id=10").query(String.class).single()).isEqualTo("awaiting_payment");
    }

    private void merchantOrder() {
        jdbc.sql("UPDATE customer_account SET account_type_code='merchant' WHERE id=7").update();
        jdbc.sql("INSERT INTO merchant_order_batch(id,merchant_customer_id) VALUES(1,7)").update();
        jdbc.sql("INSERT INTO merchant_order_batch_item(batch_id,order_id) VALUES(1,10)").update();
    }

    @Test
    void merchantApprovalUsesServiceDefaultAndLegacyProjectionNeverRewritesSavedTerms() {
        String defaultText="Please confirm the submitted item list, approved quote, and current NXR service terms before payment.";
        String merchantText="Please confirm the submitted item list and current NXR service terms before shipping.";
        merchantOrder();
        jdbc.sql("UPDATE order_admission_config SET terms_text=:text WHERE config_id=1").param("text",defaultText).update();
        var approved=tx(()->service.decide(10,9,new OrderAdmissionService.DecisionRequest("approve","Approved service",1)));
        assertThat(approved.termsText()).isEqualTo(merchantText);
        assertThat(jdbc.sql("SELECT approved_terms_text FROM grading_order WHERE id=10").query(String.class).single()).isEqualTo(merchantText);
        assertThat(jdbc.sql("SELECT terms_text FROM order_admission_config WHERE config_id=1").query(String.class).single()).isEqualTo(defaultText);
        jdbc.sql("UPDATE grading_order SET approved_terms_text=:text WHERE id=10").param("text",defaultText).update();
        int events=jdbc.sql("SELECT COUNT(*) FROM order_admission_event").query(Integer.class).single();
        int revision=jdbc.sql("SELECT admission_revision FROM grading_order WHERE id=10").query(Integer.class).single();
        assertThat(tx(()->service.requireCustomerAdmission(7,"NXR-100")).termsText()).isEqualTo(merchantText);
        assertThat(service.requireAdminAdmission(10).termsText()).isEqualTo(merchantText);
        assertThat(jdbc.sql("SELECT approved_terms_text FROM grading_order WHERE id=10").query(String.class).single()).isEqualTo(defaultText);
        assertThat(jdbc.sql("SELECT admission_revision FROM grading_order WHERE id=10").query(Integer.class).single()).isEqualTo(revision);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM order_admission_event").query(Integer.class).single()).isEqualTo(events);
    }

    @Test
    void customMerchantTermsRemainExactEvenWhenTheyContainPaymentWording() {
        String custom="Please confirm the submitted item list, approved quote, and current NXR service terms before payment. Custom contract: 30-day settlement.";
        merchantOrder();
        jdbc.sql("UPDATE order_admission_config SET terms_text=:text WHERE config_id=1").param("text",custom).update();
        var approved=tx(()->service.decide(10,9,new OrderAdmissionService.DecisionRequest("approve","Approved custom terms",1)));
        assertThat(approved.termsText()).isEqualTo(custom);
        assertThat(tx(()->service.requireCustomerAdmission(7,"NXR-100")).termsText()).isEqualTo(custom);
        assertThat(jdbc.sql("SELECT approved_terms_text FROM grading_order WHERE id=10").query(String.class).single()).isEqualTo(custom);
    }

    @Test
    void consumerSystemDefaultRetainsPaymentWordingAndPaymentWorkflow() {
        String defaultText="Please confirm the submitted item list, approved quote, and current NXR service terms before payment.";
        jdbc.sql("UPDATE order_admission_config SET terms_text=:text WHERE config_id=1").param("text",defaultText).update();
        var approved=tx(()->service.decide(10,9,new OrderAdmissionService.DecisionRequest("approve","Approved direct order",1)));
        assertThat(approved.termsText()).isEqualTo(defaultText);assertThat(approved.paymentRequired()).isTrue();
        assertThat(approved.paymentDueAt()).isNotNull();
        assertThat(tx(()->service.requireCustomerAdmission(7,"NXR-100")).termsText()).isEqualTo(defaultText);
        assertThat(jdbc.sql("SELECT approved_terms_text FROM grading_order WHERE id=10").query(String.class).single()).isEqualTo(defaultText);
    }

    @Test
    void quoteDriftAfterApprovalRejectsOldAcceptanceWithoutAddingRows() {
        tx(() -> service.decide(10L, 9L,
            new OrderAdmissionService.DecisionRequest("approve", "Approved initial quote", 1)));
        // A changed stored quote must never accept the customer's earlier approval snapshot.
        jdbc.sql("UPDATE grading_order SET total_amount=125.00 WHERE id=10").update();
        int revision = jdbc.sql("SELECT admission_revision FROM grading_order WHERE id=10")
            .query(Integer.class).single();
        int events = jdbc.sql("SELECT COUNT(*) FROM order_admission_event WHERE order_id=10")
            .query(Integer.class).single();

        assertThatThrownBy(() -> tx(() -> service.acceptTerms(7L, "NXR-100",
            new OrderAdmissionService.AcceptTermsRequest("terms-v1", new BigDecimal("100.00"), "USD"))))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("quote changed after approval");

        assertThat(jdbc.sql("SELECT COUNT(*) FROM order_terms_acceptance").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT admission_revision FROM grading_order WHERE id=10").query(Integer.class).single())
            .isEqualTo(revision);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM order_admission_event WHERE order_id=10").query(Integer.class).single())
            .isEqualTo(events);
        assertThat(jdbc.sql("SELECT status_code FROM grading_order WHERE id=10").query(String.class).single())
            .isEqualTo("terms_confirmation");
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
    void acceptedPaidFulfillmentCanContinueAfterTheOriginalPaymentDeadline() {
        tx(() -> service.decide(10L, 9L, new OrderAdmissionService.DecisionRequest("approve", "Approved", 1)));
        tx(() -> service.acceptTerms(
            7L, "NXR-100", new OrderAdmissionService.AcceptTermsRequest("terms-v1", new BigDecimal("100.00"), "USD")
        ));
        jdbc.sql("UPDATE payment_record SET status_code='confirmed' WHERE order_id=10").update();
        jdbc.sql("UPDATE grading_order SET status_code='received', payment_due_at=TIMESTAMP '2026-09-08 01:00:00' WHERE id=10").update();

        for (String status : java.util.List.of("received", "grading", "review", "quality_check", "completed", "return_shipped")) {
            jdbc.sql("UPDATE grading_order SET status_code=:status WHERE id=10").param("status", status).update();
            tx(() -> { service.requireGenericStatusChangeAllowed(10L); service.expireDuePayments(); return null; });
            assertThat(jdbc.sql("SELECT status_code FROM grading_order WHERE id=10").query(String.class).single()).isEqualTo(status);
        }
        assertThat(jdbc.sql("SELECT status_code FROM payment_record WHERE order_id=10").query(String.class).single()).isEqualTo("confirmed");

        jdbc.sql("UPDATE grading_order SET status_code='received', total_amount=101 WHERE id=10").update();
        assertGenericBlocked();
        jdbc.sql("UPDATE grading_order SET total_amount=100, terms_accepted_at=NULL WHERE id=10").update();
        assertGenericBlocked();
    }

    @Test
    void expiredUnpaidAndLatePaymentHeldOrdersCannotUseGenericStatusToEnterFulfillment() {
        tx(() -> service.decide(10L, 9L, new OrderAdmissionService.DecisionRequest("approve", "Approved", 1)));
        tx(() -> service.acceptTerms(
            7L, "NXR-100", new OrderAdmissionService.AcceptTermsRequest("terms-v1", new BigDecimal("100.00"), "USD")
        ));
        jdbc.sql("UPDATE grading_order SET payment_due_at=TIMESTAMP '2026-09-08 01:00:00' WHERE id=10").update();
        assertGenericBlocked();
        jdbc.sql("UPDATE grading_order SET status_code='received' WHERE id=10").update();
        assertGenericBlocked();
        jdbc.sql("UPDATE payment_record SET status_code='confirmed' WHERE order_id=10").update();
        jdbc.sql("UPDATE grading_order SET status_code='payment_exception' WHERE id=10").update();
        assertGenericBlocked();
        assertThat(tx(() -> service.verifiedPaymentRequiresReview(10L))).isTrue();
    }

    private void assertGenericBlocked() {
        assertThatThrownBy(() -> tx(() -> { service.requireGenericStatusChangeAllowed(10L); return null; }))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("admission workflow");
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
