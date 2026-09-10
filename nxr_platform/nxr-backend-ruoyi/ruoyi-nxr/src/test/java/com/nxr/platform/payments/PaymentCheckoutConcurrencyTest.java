package com.nxr.platform.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nxr.platform.customer.CustomerPortalService;
import com.nxr.platform.customer.CustomerPortalService.CustomerReference;
import com.nxr.platform.customer.CustomerPortalService.OrderDetailResponse;
import com.nxr.platform.customer.CustomerPortalService.PaymentRecord;
import com.nxr.platform.payments.PaymentModels.CheckoutRequest;
import com.nxr.platform.payments.PaymentModels.CheckoutResponse;
import com.nxr.platform.payments.PaymentModels.ProviderCheckout;
import com.nxr.platform.payments.PaymentModels.ProviderContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.web.server.ResponseStatusException;

class PaymentCheckoutConcurrencyTest {

    private JdbcClient jdbc;
    private CustomerPortalService portal;
    private PaymentConfigurationService configurations;
    private PaymentCheckoutService service;
    private AtomicInteger createCalls;
    private AtomicReference<String> createStatus;
    private AtomicReference<PaymentModels.VerifiedPayment> webhookPayment;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:payment-guard;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""
        );
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.execute("DROP ALL OBJECTS");
        template.execute("""
            CREATE TABLE grading_order (
              id BIGINT PRIMARY KEY, order_no VARCHAR(40) NOT NULL, customer_id BIGINT NOT NULL,
              status_code VARCHAR(32) NOT NULL, total_amount DECIMAL(12,2) NOT NULL,
              currency_code VARCHAR(8) NOT NULL, updated_at TIMESTAMP
            );
            CREATE TABLE payment_record (
              id BIGINT PRIMARY KEY, order_id BIGINT NOT NULL, direction_code VARCHAR(16) NOT NULL,
              payment_type_code VARCHAR(32) NOT NULL, payment_no VARCHAR(48), provider_code VARCHAR(32),
              status_code VARCHAR(32), amount DECIMAL(12,2) NOT NULL, currency_code VARCHAR(8) NOT NULL,
              payment_url VARCHAR(1024), qr_payload VARCHAR(1024), provider_transaction_id VARCHAR(255),
              callback_received_at TIMESTAMP, callback_payload CLOB, updated_at TIMESTAMP
            );
            CREATE TABLE payment_attempt (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, payment_record_id BIGINT NOT NULL, order_id BIGINT NOT NULL,
              active_order_id BIGINT, customer_id BIGINT NOT NULL, provider_code VARCHAR(32) NOT NULL,
              idempotency_key VARCHAR(100) NOT NULL, merchant_order_no VARCHAR(64) NOT NULL,
              provider_order_id VARCHAR(128), provider_transaction_id VARCHAR(255),
              expected_amount DECIMAL(12,2) NOT NULL, expected_currency CHAR(3) NOT NULL,
              status_code VARCHAR(32) NOT NULL, payment_url VARCHAR(1024), qr_payload VARCHAR(1024),
              verified_at TIMESTAMP, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
              updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
              UNIQUE(customer_id, idempotency_key), UNIQUE(active_order_id), UNIQUE(provider_code, provider_order_id)
            );
            CREATE TABLE payment_callback_event (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, provider_code VARCHAR(32), provider_event_id VARCHAR(255),
              payment_id BIGINT, payload CLOB, UNIQUE(provider_code, provider_event_id)
            );
            CREATE TABLE payment_finance_exception (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, order_id BIGINT, payment_record_id BIGINT, payment_attempt_id BIGINT,
              provider_code VARCHAR(32), provider_event_id VARCHAR(255), provider_transaction_id VARCHAR(255),
              exception_type_code VARCHAR(32), amount DECIMAL(12,2), currency_code CHAR(3)
            );
            CREATE TABLE order_timeline_event (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, order_id BIGINT, event_code VARCHAR(32), title VARCHAR(255),
              detail CLOB, status_code VARCHAR(32), visible_to_customer TINYINT, actor_type_code VARCHAR(32)
            )
            """);
        template.update("INSERT INTO grading_order (id,order_no,customer_id,status_code,total_amount,currency_code) VALUES (10,'NXR-100',7,'awaiting_payment',28.40,'USD')");
        template.update("INSERT INTO payment_record (id,order_id,direction_code,payment_type_code,payment_no,provider_code,status_code,amount,currency_code) VALUES (20,10,'receivable','grading_fee','PAY-NXR-100','manual_transfer','pending',28.40,'USD')");
        jdbc = JdbcClient.create(dataSource);
        portal = mock(CustomerPortalService.class);
        configurations = mock(PaymentConfigurationService.class);
        ProviderContext context = new ProviderContext(
            "paypal", "sandbox", "https://api-m.sandbox.paypal.com", "https://example.com/webhook",
            "https://example.com/account/orders", Map.of()
        );
        when(configurations.requireEnabled(anyString(), anyString())).thenReturn(context);
        when(configurations.requireForWebhook(anyString())).thenReturn(context);
        when(portal.requireCustomerOrder(7L, "NXR-100")).thenReturn(order());
        createCalls = new AtomicInteger();
        createStatus = new AtomicReference<>("created");
        webhookPayment = new AtomicReference<>();
        PaymentAdapter adapter = new PaymentAdapter() {
            @Override public String provider() { return "paypal"; }
            @Override public ProviderCheckout createCheckout(PaymentModels.CheckoutContext ignored) {
                int sequence = createCalls.incrementAndGet();
                return new ProviderCheckout("PAYPAL-REMOTE-" + sequence,
                    "https://www.sandbox.paypal.com/checkoutnow?token=" + sequence, null, createStatus.get(), "{}");
            }
            @Override public PaymentModels.VerifiedPayment verifyWebhook(ProviderContext ignored, PaymentModels.WebhookRequest request) {
                return webhookPayment.get();
            }
        };
        service = new PaymentCheckoutService(
            jdbc, configurations, new PaymentAdapterRegistry(List.of(adapter)), portal, new DataSourceTransactionManager(dataSource)
        );
    }

    @Test
    void acceptsPartialRefundByCaptureIdAndDoesNotResurrectLatePaidEvent() {
        service.createCheckout(7L, "NXR-100", new CheckoutRequest("paypal", "idem-key-0001"));
        jdbc.sql("UPDATE payment_attempt SET status_code='paid', provider_transaction_id='CAPTURE-ORIGINAL' WHERE order_id=10").update();
        jdbc.sql("UPDATE payment_record SET status_code='confirmed', provider_transaction_id='CAPTURE-ORIGINAL' WHERE id=20").update();

        webhookPayment.set(new PaymentModels.VerifiedPayment(
            "refund-event-1", "CAPTURE-ORIGINAL", "REFUND-1", "", new BigDecimal("14.20"), "USD", "refunded", "{}"
        ));
        assertThat(service.processWebhook("paypal", new PaymentModels.WebhookRequest(Map.of(), "{}", Map.of())))
            .isEqualTo("accepted");
        assertThat(jdbc.sql("SELECT amount FROM payment_finance_exception").query(BigDecimal.class).single())
            .isEqualByComparingTo("14.20");
        assertThat(jdbc.sql("SELECT status_code FROM grading_order WHERE id=10").query(String.class).single())
            .isEqualTo("payment_exception");

        webhookPayment.set(new PaymentModels.VerifiedPayment(
            "paid-event-late", "PAYPAL-REMOTE-1", "CAPTURE-ORIGINAL", "NXR-100",
            new BigDecimal("28.40"), "USD", "paid", "{}"
        ));
        service.processWebhook("paypal", new PaymentModels.WebhookRequest(Map.of(), "{}", Map.of()));

        webhookPayment.set(new PaymentModels.VerifiedPayment(
            "failed-event-late", "PAYPAL-REMOTE-1", "CAPTURE-ORIGINAL", "NXR-100",
            new BigDecimal("28.40"), "USD", "failed", "{}"
        ));
        service.processWebhook("paypal", new PaymentModels.WebhookRequest(Map.of(), "{}", Map.of()));

        assertThat(jdbc.sql("SELECT status_code FROM payment_record WHERE id=20").query(String.class).single())
            .isEqualTo("refunded");
        assertThat(jdbc.sql("SELECT status_code FROM payment_attempt WHERE order_id=10").query(String.class).single())
            .isEqualTo("refunded");
        verify(portal, never()).receivePaymentCallback(anyString(), any(CustomerPortalService.PaymentCallbackRequest.class));
    }

    @Test
    void verifiedFailureReleasesActiveCheckoutForAControlledRetry() {
        service.createCheckout(7L, "NXR-100", new CheckoutRequest("paypal", "idem-key-0001"));
        webhookPayment.set(new PaymentModels.VerifiedPayment(
            "failed-event-1", "PAYPAL-REMOTE-1", "FAILED-CAPTURE-1", "NXR-100",
            new BigDecimal("28.40"), "USD", "failed", "{}"
        ));
        service.processWebhook("paypal", new PaymentModels.WebhookRequest(Map.of(), "{}", Map.of()));

        assertThat(jdbc.sql("SELECT active_order_id FROM payment_attempt WHERE id=1")
            .query(Long.class).optional()).isEmpty();
        CheckoutResponse retry = service.createCheckout(
            7L, "NXR-100", new CheckoutRequest("paypal", "idem-key-0002")
        );
        assertThat(retry.providerOrderId()).isEqualTo("PAYPAL-REMOTE-2");
        assertThat(createCalls).hasValue(2);
    }

    @Test
    void definiteCreationFailureReleasesActiveCheckoutForRetry() {
        createStatus.set("failed");
        CheckoutResponse failed = service.createCheckout(
            7L, "NXR-100", new CheckoutRequest("paypal", "idem-key-failed-create")
        );
        assertThat(failed.status()).isEqualTo("failed");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM payment_attempt WHERE active_order_id=10")
            .query(Integer.class).single()).isZero();

        createStatus.set("created");
        CheckoutResponse retry = service.createCheckout(
            7L, "NXR-100", new CheckoutRequest("paypal", "idem-key-after-failure")
        );
        assertThat(retry.providerOrderId()).isEqualTo("PAYPAL-REMOTE-2");
    }

    @Test
    void rejectsRefundAboveOriginalAmount() {
        PaymentCheckoutService.validateVerifiedAmount(new BigDecimal("28.40"), new BigDecimal("14.20"), "refunded");
        assertThatThrownBy(() -> PaymentCheckoutService.validateVerifiedAmount(
            new BigDecimal("28.40"), new BigDecimal("28.41"), "refunded"
        )).isInstanceOf(ResponseStatusException.class).hasMessageContaining("exceeds");
    }

    @Test
    void reusesSameProviderAttemptAndRejectsProviderSwitch() {
        CheckoutResponse first = service.createCheckout(7L, "NXR-100", new CheckoutRequest("paypal", "idem-key-0001"));
        CheckoutResponse repeated = service.createCheckout(7L, "NXR-100", new CheckoutRequest("paypal", "idem-key-0002"));

        assertThat(first.providerOrderId()).isEqualTo("PAYPAL-REMOTE-1");
        assertThat(repeated.providerOrderId()).isEqualTo(first.providerOrderId());
        assertThat(createCalls).hasValue(1);
        assertThatThrownBy(() -> service.createCheckout(7L, "NXR-100", new CheckoutRequest("alipay", "idem-key-0003")))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("already has an active checkout");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM payment_attempt").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void refusesLockedCurrentPaymentThatDiffersFromPreLockOrderSnapshot() {
        jdbc.sql("UPDATE payment_record SET amount = 29.00 WHERE id = 20").update();

        assertThatThrownBy(() -> service.createCheckout(
            7L, "NXR-100", new CheckoutRequest("paypal", "idem-key-stale-payment")
        )).isInstanceOf(ResponseStatusException.class).hasMessageContaining("amount is inconsistent");

        assertThat(createCalls).hasValue(0);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM payment_attempt").query(Integer.class).single()).isZero();
    }

    @Test
    void refusesOnlineCheckoutWhileManualPaymentProofIsUnderReview() {
        jdbc.sql("UPDATE grading_order SET status_code='payment_review' WHERE id=10").update();
        jdbc.sql("UPDATE payment_record SET status_code='proof_submitted',provider_code='bank_transfer' WHERE id=20").update();

        assertThatThrownBy(() -> service.createCheckout(
            7L, "NXR-100", new CheckoutRequest("paypal", "idem-key-proof-review")
        )).isInstanceOf(ResponseStatusException.class).hasMessageContaining("financial review");
        assertThat(createCalls).hasValue(0);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM payment_attempt").query(Integer.class).single()).isZero();
    }

    private static OrderDetailResponse order() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 8, 10, 0);
        PaymentRecord payment = new PaymentRecord(
            20L, 10L, "receivable", "grading_fee", "PAY-NXR-100", "manual_transfer", null, "pending",
            new BigDecimal("28.40"), "USD", null, null, null, null, null, null, null, null, null, null, now
        );
        return new OrderDetailResponse(
            10L, "NXR-100", "awaiting_payment", null, "basic_grading", null, null, 1,
            new BigDecimal("20.00"), new BigDecimal("8.40"), new BigDecimal("28.40"), "USD",
            "Collector", "123", "Street", null, "City", null, "100000", "CN",
            null, null, null, null, null, new CustomerReference(7L, "customer@example.com", "Collector"),
            now, now, List.of(), List.of(payment), List.of(), List.of()
        );
    }
}
