package com.nxr.platform.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class MerchantWalletServiceTest {

    private JdbcTemplate jdbcTemplate;
    private MerchantWalletService walletService;
    private CustomerPortalService portalService;
    private OrderFulfillmentService fulfillmentService;
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:nxr_merchant_wallet;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("order_fulfillment_h2.sql"));
        }
        createWalletSchema();
        JdbcClient jdbcClient = JdbcClient.create(jdbcTemplate);
        walletService = new MerchantWalletService(jdbcClient);
        fulfillmentService = new OrderFulfillmentService(jdbcClient, jdbcTemplate);
        portalService = new CustomerPortalService(jdbcClient, jdbcTemplate, fulfillmentService, walletService, null);
        seedReferenceData();
    }

    @Test
    void rechargeOnlyCreditsAfterFinanceConfirmationAndGatewayConfirmationIsIdempotent() {
        assertThatThrownBy(() -> walletService.createRecharge(
            1L, new MerchantWalletService.RechargeRequest("JPY", new BigDecimal("1.50"),
                "bank_transfer", "invalid", null)
        )).isInstanceOf(ResponseStatusException.class).hasMessageContaining("precision");
        MerchantWalletService.RechargeRecord manual = walletService.createRecharge(
            1L, new MerchantWalletService.RechargeRequest("USD", new BigDecimal("100.00"),
                "bank_transfer", "bank-ref", "proof-ref")
        );
        assertThat(manual.statusCode()).isEqualTo("pending");
        assertThat(walletService.listWallets(1L)).isEmpty();

        MerchantWalletService.RechargeRecord confirmed = walletService.reviewRecharge(
            1L, manual.id(), 900L, true,
            new MerchantWalletService.RechargeReviewRequest("bank-txn", "Funds verified")
        );
        assertThat(confirmed.statusCode()).isEqualTo("confirmed");
        assertThat(walletService.listWallets(1L)).singleElement()
            .satisfies(wallet -> assertThat(wallet.balance()).isEqualByComparingTo("100.00"));
        walletService.reviewRecharge(1L, manual.id(), 900L, true,
            new MerchantWalletService.RechargeReviewRequest("bank-txn", "Funds verified"));
        assertThat(walletService.listTransactions(1L, "USD", 1, 20).total()).isEqualTo(1);

        MerchantWalletService.RechargeRecord gateway = walletService.createRecharge(
            1L, new MerchantWalletService.RechargeRequest("USD", new BigDecimal("25.00"),
                "stripe", null, null)
        );
        walletService.confirmGatewayRecharge(gateway.id(), "stripe", "gateway-txn", new BigDecimal("25.00"), "USD");
        walletService.confirmGatewayRecharge(gateway.id(), "stripe", "gateway-txn", new BigDecimal("25.00"), "USD");
        assertThat(walletService.listWallets(1L)).singleElement()
            .satisfies(wallet -> assertThat(wallet.balance()).isEqualByComparingTo("125.00"));
        assertThatThrownBy(() -> walletService.confirmGatewayRecharge(
            gateway.id(), "stripe", "another-txn", new BigDecimal("25.00"), "USD"
        )).isInstanceOf(ResponseStatusException.class)
            .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void walletOrderPaymentIsCurrencyIsolatedIdempotentAndRefundedBeforeInbound() {
        credit(1L, "USD", "80.00");
        OrderFulfillmentService.CustomerAddress address = address();
        CustomerPortalService.OrderDetailResponse order = portalService.createOrder(
            1L, orderRequest(address.id(), "economy_line", "USD")
        );

        for (String activeStatus : List.of("pending", "creating", "creation_unknown", "capturing", "capture_unknown")) {
            jdbcTemplate.update("INSERT INTO payment_attempt (order_id,status_code) VALUES (?, ?)", order.id(), activeStatus);
            assertThatThrownBy(() -> portalService.payOrderFromWallet(
                1L, order.orderNo(), new CustomerPortalService.WalletPaymentRequest("checkout-blocked")
            )).isInstanceOf(ResponseStatusException.class).hasMessageContaining("payment attempt");
            assertThatThrownBy(() -> portalService.cancelCustomerOrder(
                1L, order.orderNo(), new CustomerPortalService.CancelOrderRequest("Cannot cancel an uncertain charge")
            )).isInstanceOf(ResponseStatusException.class).hasMessageContaining("payment attempt");
            assertThat(balance("USD")).isEqualByComparingTo("80.00");
            jdbcTemplate.update("DELETE FROM payment_attempt WHERE order_id = ?", order.id());
        }

        CustomerPortalService.OrderDetailResponse paid = portalService.payOrderFromWallet(
            1L, order.orderNo(), new CustomerPortalService.WalletPaymentRequest("checkout-1")
        );
        assertThat(paid.statusCode()).isEqualTo("awaiting_inbound");
        assertThat(paid.payments()).singleElement().satisfies(payment -> {
            assertThat(payment.providerCode()).isEqualTo("wallet");
            assertThat(payment.statusCode()).isEqualTo("confirmed");
        });
        portalService.payOrderFromWallet(1L, order.orderNo(), new CustomerPortalService.WalletPaymentRequest("checkout-1"));
        assertThat(balance("USD")).isEqualByComparingTo("48.00");
        assertThat(walletService.listTransactions(1L, "USD", 1, 20).total()).isEqualTo(2);

        CustomerPortalService.OrderDetailResponse cancelled = portalService.cancelCustomerOrder(
            1L, order.orderNo(), new CustomerPortalService.CancelOrderRequest("No longer sending cards")
        );
        assertThat(cancelled.statusCode()).isEqualTo("cancelled");
        assertThat(balance("USD")).isEqualByComparingTo("80.00");
        assertThat(cancelled.payments()).singleElement()
            .satisfies(payment -> assertThat(payment.statusCode()).isEqualTo("refunded"));

        credit(1L, "CNY", "100.00");
        jdbcTemplate.update("UPDATE grading_service_price SET currency_code = 'CNY', unit_price = 150.00 WHERE price_code = 'basic_grading'");
        CustomerPortalService.OrderDetailResponse cnyOrder = portalService.createOrder(
            1L, orderRequest(address.id(), "economy_cny", "CNY")
        );
        assertThatThrownBy(() -> portalService.payOrderFromWallet(
            1L, cnyOrder.orderNo(), new CustomerPortalService.WalletPaymentRequest("checkout-cny")
        )).isInstanceOf(ResponseStatusException.class).hasMessageContaining("Insufficient CNY");
        assertThat(balance("USD")).isEqualByComparingTo("80.00");
    }

    @Test
    void cancellationRequiresFinancialReviewAfterProofOrExternalConfirmation() {
        credit(1L, "USD", "80.00");
        OrderFulfillmentService.CustomerAddress address = address();
        CustomerPortalService.OrderDetailResponse proofOrder = portalService.createOrder(
            1L, orderRequest(address.id(), "economy_line", "USD")
        );
        portalService.submitPaymentProof(1L, proofOrder.orderNo(),
            new CustomerPortalService.SubmitPaymentProofRequest("bank_transfer", "proof-reference", null));
        assertThatThrownBy(() -> portalService.payOrderFromWallet(
            1L, proofOrder.orderNo(), new CustomerPortalService.WalletPaymentRequest("must-not-double-pay")
        )).isInstanceOf(ResponseStatusException.class).hasMessageContaining("payment activity");
        assertThat(balance("USD")).isEqualByComparingTo("80.00");
        assertThatThrownBy(() -> portalService.cancelCustomerOrder(
            1L, proofOrder.orderNo(), new CustomerPortalService.CancelOrderRequest("Do not discard recorded funds")
        )).isInstanceOf(ResponseStatusException.class).hasMessageContaining("financial review");
        jdbcTemplate.update("UPDATE grading_order SET status_code='payment_expired' WHERE id=?", proofOrder.id());
        CustomerPortalService.OrderDetailResponse rejected = portalService.rejectPayment(
            proofOrder.id(), proofOrder.payments().get(0).id(), 900L,
            new CustomerPortalService.RejectPaymentRequest("Proof rejected after the deadline")
        );
        assertThat(rejected.statusCode()).isEqualTo("payment_expired");
        assertThat(rejected.payments()).singleElement()
            .satisfies(payment -> assertThat(payment.statusCode()).isEqualTo("rejected"));

        CustomerPortalService.OrderDetailResponse paidOrder = portalService.createOrder(
            1L, orderRequest(address.id(), "economy_line", "USD")
        );
        CustomerPortalService.PaymentSessionResponse session = portalService.createPaymentSession(
            1L, paidOrder.orderNo(), new CustomerPortalService.PaymentSessionRequest("paypal")
        );
        portalService.receivePaymentCallback("paypal", new CustomerPortalService.PaymentCallbackRequest(
            "paid-before-cancel", session.paymentNo(), "external-confirmation", paidOrder.totalAmount(),
            "USD", "paid", "{}"
        ));
        assertThatThrownBy(() -> portalService.updateOrderStatusByAdmin(
            paidOrder.id(), 900L, new CustomerPortalService.UpdateOrderStatusRequest("cancelled", "No implicit refund")
        )).isInstanceOf(ResponseStatusException.class).hasMessageContaining("financial review");
        assertThat(portalService.requireAdminOrder(paidOrder.id()).statusCode()).isEqualTo("awaiting_inbound");
    }

    @Test
    void concurrentWalletPaymentWithSameKeyReturnsOneCurrentIdempotentResult() throws Exception {
        credit(1L, "USD", "80.00");
        CustomerPortalService.OrderDetailResponse order = portalService.createOrder(
            1L, orderRequest(address().id(), "economy_line", "USD")
        );
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<CustomerPortalService.OrderDetailResponse> first = executor.submit(() -> transactionTemplate.execute(status -> {
                await(start);
                return portalService.payOrderFromWallet(
                    1L, order.orderNo(), new CustomerPortalService.WalletPaymentRequest("same-checkout")
                );
            }));
            Future<CustomerPortalService.OrderDetailResponse> second = executor.submit(() -> transactionTemplate.execute(status -> {
                await(start);
                return portalService.payOrderFromWallet(
                    1L, order.orderNo(), new CustomerPortalService.WalletPaymentRequest("same-checkout")
                );
            }));
            start.countDown();
            assertThat(first.get().statusCode()).isEqualTo("awaiting_inbound");
            assertThat(second.get().statusCode()).isEqualTo("awaiting_inbound");
        } finally {
            executor.shutdownNow();
        }
        assertThat(balance("USD")).isEqualByComparingTo("48.00");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM merchant_wallet_order_payment WHERE order_id = ?", Integer.class, order.id()
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM merchant_wallet_transaction WHERE transaction_type_code = 'order_payment'", Integer.class
        )).isEqualTo(1);
    }

    @Test
    void nonMerchantCannotUseBulkOrderOrWallet() {
        jdbcTemplate.update("UPDATE customer_account SET account_type_code = 'customer' WHERE id = 1");
        MerchantBulkOrderService bulk = new MerchantBulkOrderService(portalService, fulfillmentService);
        assertThatThrownBy(() -> bulk.createOrders(1L, new MerchantBulkOrderService.BulkOrderRequest(
            "orders.csv", List.of(orderRequest(1L, "economy_line", "USD"))
        ))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("Merchant");
        assertThatThrownBy(() -> walletService.createRecharge(
            1L, new MerchantWalletService.RechargeRequest("USD", new BigDecimal("10"), "manual_transfer", "ref", null)
        )).isInstanceOf(ResponseStatusException.class).hasMessageContaining("Merchant");
    }

    @Test
    void inboundDeliveryDoesNotCompleteOrderAndLateTrackingCannotRegressDeliveredShipment() {
        credit(1L, "USD", "80.00");
        OrderFulfillmentService.CustomerAddress address = address();
        CustomerPortalService.OrderDetailResponse order = portalService.createOrder(
            1L, orderRequest(address.id(), "economy_line", "USD")
        );
        portalService.payOrderFromWallet(1L, order.orderNo(), new CustomerPortalService.WalletPaymentRequest("tracking-pay"));
        CustomerPortalService.OrderDetailResponse shipped = portalService.addInboundShipment(
            1L, order.orderNo(), new CustomerPortalService.CreateShipmentRequest("inbound", "UPS", "IN-1", null)
        );
        CustomerPortalService.ShipmentRecord inbound = shipped.shipments().get(0);
        fulfillmentService.addTrackingEvent(order.id(), inbound.id(), 900L,
            new OrderFulfillmentService.TrackingEventRequest("delivered", null, null, "At NXR", null));
        assertThat(portalService.requireCustomerOrder(1L, order.orderNo()).statusCode()).isEqualTo("inbound_shipped");
        List<CustomerPortalService.OrderTimelineEvent> timeline = portalService.requireCustomerOrder(1L, order.orderNo()).timeline();
        assertThat(timeline.get(timeline.size() - 1).statusCode()).isEqualTo("inbound_shipped");

        jdbcTemplate.update("UPDATE grading_order SET status_code = 'return_shipped' WHERE id = ?", order.id());
        jdbcTemplate.update(
            "INSERT INTO order_shipment (order_id,direction_code,carrier_name,tracking_number,status_code) VALUES (?,?,?,?,?)",
            order.id(), "outbound", "DHL", "OUT-1", "in_transit"
        );
        long outboundId = jdbcTemplate.queryForObject(
            "SELECT id FROM order_shipment WHERE order_id = ? AND direction_code = 'outbound'", Long.class, order.id()
        );
        fulfillmentService.addTrackingEvent(order.id(), outboundId, 900L,
            new OrderFulfillmentService.TrackingEventRequest("delivered", null, null, "Delivered", null));
        fulfillmentService.addTrackingEvent(order.id(), outboundId, 900L,
            new OrderFulfillmentService.TrackingEventRequest("in_transit", null, null, "Late carrier event", null));
        assertThat(portalService.requireCustomerOrder(1L, order.orderNo()).statusCode()).isEqualTo("delivered");
        assertThat(jdbcTemplate.queryForObject("SELECT status_code FROM order_shipment WHERE id = ?", String.class, outboundId))
            .isEqualTo("delivered");
    }

    @Test
    void paymentExceptionCannotBeClearedByWarehouseExceptionQualityCheckOrSubmissionLink() {
        CustomerPortalService.OrderDetailResponse order = portalService.createOrder(
            1L, orderRequest(address().id(), "economy_line", "USD")
        );
        jdbcTemplate.update(
            "UPDATE grading_order SET status_code = 'payment_exception', intake_code = 'HOLD-CODE' WHERE id = ?", order.id()
        );

        fulfillmentService.createException(order.id(), 900L,
            new OrderFulfillmentService.OrderExceptionRequest("other", "Inspection note", "Recorded during hold", true));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status_code FROM grading_order WHERE id = ?", String.class, order.id()
        )).isEqualTo("payment_exception");
        long exceptionId = jdbcTemplate.queryForObject(
            "SELECT id FROM order_exception WHERE order_id = ?", Long.class, order.id()
        );
        fulfillmentService.resolveException(order.id(), exceptionId, 900L,
            new OrderFulfillmentService.ResolveExceptionRequest("Inspection note resolved"));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status_code FROM grading_order WHERE id = ?", String.class, order.id()
        )).isEqualTo("payment_exception");

        assertThatThrownBy(() -> fulfillmentService.receiveOrder(order.id(), 900L,
            new OrderFulfillmentService.ReceiveOrderRequest("HOLD-CODE", null, 1, null, List.of())))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("payment needs attention");
        assertThatThrownBy(() -> fulfillmentService.qualityCheck(order.id(), 900L,
            new OrderFulfillmentService.QualityCheckRequest(true, "Must remain paused")))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("payment needs attention");
        assertThatThrownBy(() -> portalService.linkOrderItemSubmission(order.id(), 1L, 1L, 900L))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("payment needs attention");
    }

    @Test
    void lateFailedCallbackCannotDowngradeConfirmedPaymentAndExcessPrecisionIsRejected() {
        OrderFulfillmentService.CustomerAddress address = address();
        CustomerPortalService.OrderDetailResponse order = portalService.createOrder(
            1L, orderRequest(address.id(), "economy_line", "USD")
        );
        CustomerPortalService.PaymentSessionResponse session = portalService.createPaymentSession(
            1L, order.orderNo(), new CustomerPortalService.PaymentSessionRequest("paypal")
        );
        portalService.receivePaymentCallback("paypal", new CustomerPortalService.PaymentCallbackRequest(
            "event-paid", session.paymentNo(), "provider-txn", order.totalAmount(), "USD", "paid", "{}"
        ));
        CustomerPortalService.PaymentRecord firstConfirmation = portalService.requireCustomerOrder(1L, order.orderNo()).payments().get(0);
        CustomerPortalService.PaymentCallbackResponse repeatedSuccess = portalService.receivePaymentCallback(
            "paypal", new CustomerPortalService.PaymentCallbackRequest(
                "event-paid-repeat", session.paymentNo(), "provider-txn", order.totalAmount(), "USD", "succeeded", "{}"
            )
        );
        assertThat(repeatedSuccess.message()).contains("already confirmed");
        assertThat(portalService.requireCustomerOrder(1L, order.orderNo()).payments().get(0).confirmedAt())
            .isEqualTo(firstConfirmation.confirmedAt());
        assertThatThrownBy(() -> portalService.receivePaymentCallback(
            "paypal", new CustomerPortalService.PaymentCallbackRequest(
                "event-wrong-transaction", session.paymentNo(), "different-provider-txn",
                order.totalAmount(), "USD", "paid", "{}"
            )
        )).isInstanceOf(ResponseStatusException.class).hasMessageContaining("another provider transaction");
        CustomerPortalService.PaymentCallbackResponse late = portalService.receivePaymentCallback(
            "paypal", new CustomerPortalService.PaymentCallbackRequest(
                "event-late", session.paymentNo(), "provider-txn", order.totalAmount(), "USD", "failed", "{}"
            )
        );
        assertThat(late.message()).contains("ignored");
        assertThat(portalService.requireCustomerOrder(1L, order.orderNo()).payments()).singleElement()
            .satisfies(payment -> assertThat(payment.statusCode()).isEqualTo("confirmed"));

        CustomerPortalService.OrderDetailResponse another = portalService.createOrder(
            1L, orderRequest(address.id(), "economy_line", "USD")
        );
        CustomerPortalService.PaymentSessionResponse anotherSession = portalService.createPaymentSession(
            1L, another.orderNo(), new CustomerPortalService.PaymentSessionRequest("stripe")
        );
        assertThatThrownBy(() -> portalService.receivePaymentCallback(
            "stripe", new CustomerPortalService.PaymentCallbackRequest(
                "event-precision", anotherSession.paymentNo(), "precision-txn",
                new BigDecimal("32.001"), "USD", "paid", "{}"
            )
        )).isInstanceOf(ResponseStatusException.class).hasMessageContaining("precision");
    }

    @Test
    void concurrentSuccessAndFailureCallbacksAlwaysLeavePaymentConfirmed() throws Exception {
        OrderFulfillmentService.CustomerAddress address = address();
        CustomerPortalService.OrderDetailResponse order = portalService.createOrder(
            1L, orderRequest(address.id(), "economy_line", "USD")
        );
        CustomerPortalService.PaymentSessionResponse session = portalService.createPaymentSession(
            1L, order.orderNo(), new CustomerPortalService.PaymentSessionRequest("stripe")
        );
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> success = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                await(start);
                portalService.receivePaymentCallback("stripe", new CustomerPortalService.PaymentCallbackRequest(
                    "race-success", session.paymentNo(), "race-transaction", order.totalAmount(), "USD", "paid", "{}"
                ));
            }));
            Future<?> failure = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                await(start);
                portalService.receivePaymentCallback("stripe", new CustomerPortalService.PaymentCallbackRequest(
                    "race-failure", session.paymentNo(), "race-transaction", order.totalAmount(), "USD", "failed", "{}"
                ));
            }));
            start.countDown();
            success.get();
            failure.get();
        } finally {
            executor.shutdownNow();
        }
        assertThat(portalService.requireCustomerOrder(1L, order.orderNo()).payments()).singleElement()
            .satisfies(payment -> {
                assertThat(payment.statusCode()).isEqualTo("confirmed");
                assertThat(payment.providerTransactionId()).isEqualTo("race-transaction");
            });
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private void credit(long customerId, String currency, String amount) {
        MerchantWalletService.RechargeRecord recharge = walletService.createRecharge(
            customerId, new MerchantWalletService.RechargeRequest(currency, new BigDecimal(amount), "manual_transfer", "ref", null)
        );
        walletService.reviewRecharge(customerId, recharge.id(), 900L, true,
            new MerchantWalletService.RechargeReviewRequest("finance-" + recharge.id(), "Cash received"));
    }

    private BigDecimal balance(String currency) {
        return walletService.listWallets(1L).stream().filter(wallet -> wallet.currencyCode().equals(currency))
            .findFirst().orElseThrow().balance();
    }

    private OrderFulfillmentService.CustomerAddress address() {
        return fulfillmentService.saveAddress(1L, null, new OrderFulfillmentService.AddressRequest(
            "Office", "Merchant Contact", "+1 555 0100", "1 Wallet Street", null,
            "New York", "NY", "10001", "US", true
        ));
    }

    private CustomerPortalService.CreateOrderRequest orderRequest(long addressId, String shippingCode, String currency) {
        return new CustomerPortalService.CreateOrderRequest(
            "basic_grading", addressId, false, shippingCode, null, null, null, null, null, null, null, null,
            "Wallet test", List.of(new CustomerPortalService.LanguageGroupRequest("EN", 1)), List.of(), currency
        );
    }

    private void seedReferenceData() {
        jdbcTemplate.update(
            "INSERT INTO customer_account (id,email,password_hash,display_name,mobile,account_type_code) VALUES (?,?,?,?,?,?)",
            1L, "merchant@example.test", "unused", "Merchant", "+1 555", "merchant"
        );
        jdbcTemplate.update(
            "INSERT INTO grading_service_price (price_code,display_name,unit_price,currency_code,is_active,version_no) VALUES (?,?,?,?,?,?)",
            "basic_grading", "Basic grading", new BigDecimal("20.00"), "USD", 1, 1
        );
        jdbcTemplate.update(
            "INSERT INTO return_shipping_option (option_code,display_name,description,country_scope,currency_code,price_amount,sort_order,is_active) VALUES (?,?,?,?,?,?,?,?)",
            "economy_line", "Economy", "USD return", "*", "USD", new BigDecimal("12.00"), 1, 1
        );
        jdbcTemplate.update(
            "INSERT INTO return_shipping_option (option_code,display_name,description,country_scope,currency_code,price_amount,sort_order,is_active) VALUES (?,?,?,?,?,?,?,?)",
            "economy_cny", "Economy CNY", "CNY return", "*", "CNY", new BigDecimal("50.00"), 2, 1
        );
    }

    private void createWalletSchema() {
        jdbcTemplate.execute("CREATE TABLE merchant_company_profile (customer_id BIGINT PRIMARY KEY, company_name VARCHAR(191) NOT NULL, contact_name VARCHAR(128) NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbcTemplate.execute("CREATE TABLE merchant_wallet (id BIGINT AUTO_INCREMENT PRIMARY KEY, customer_id BIGINT NOT NULL, currency_code VARCHAR(8) NOT NULL, balance DECIMAL(18,2) NOT NULL DEFAULT 0, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, UNIQUE(customer_id,currency_code))");
        jdbcTemplate.execute("CREATE TABLE merchant_wallet_recharge (id BIGINT AUTO_INCREMENT PRIMARY KEY, recharge_no VARCHAR(48) NOT NULL UNIQUE, customer_id BIGINT NOT NULL, currency_code VARCHAR(8) NOT NULL, amount DECIMAL(18,2) NOT NULL, provider_code VARCHAR(32) NOT NULL, payer_reference VARCHAR(255), proof_reference VARCHAR(512), provider_transaction_id VARCHAR(255), status_code VARCHAR(32) NOT NULL, reviewed_by_user_id BIGINT, reviewed_at TIMESTAMP, review_note TEXT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, UNIQUE(provider_code,provider_transaction_id))");
        jdbcTemplate.execute("CREATE TABLE merchant_wallet_transaction (id BIGINT AUTO_INCREMENT PRIMARY KEY, wallet_id BIGINT NOT NULL, transaction_no VARCHAR(48) NOT NULL UNIQUE, transaction_type_code VARCHAR(32) NOT NULL, direction_code VARCHAR(16) NOT NULL, amount DECIMAL(18,2) NOT NULL, balance_after DECIMAL(18,2) NOT NULL, reference_type_code VARCHAR(32) NOT NULL, reference_id BIGINT NOT NULL, idempotency_key VARCHAR(128) NOT NULL, note TEXT, actor_type_code VARCHAR(32) NOT NULL, actor_customer_id BIGINT, actor_admin_user_id BIGINT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, UNIQUE(wallet_id,idempotency_key))");
        jdbcTemplate.execute("CREATE TABLE merchant_wallet_order_payment (order_id BIGINT PRIMARY KEY, wallet_id BIGINT NOT NULL, debit_transaction_id BIGINT NOT NULL UNIQUE, refund_transaction_id BIGINT UNIQUE, idempotency_key VARCHAR(128) NOT NULL, amount DECIMAL(18,2) NOT NULL, currency_code VARCHAR(8) NOT NULL, status_code VARCHAR(32) NOT NULL, paid_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, refunded_at TIMESTAMP)");
        jdbcTemplate.execute("CREATE TABLE payment_attempt (id BIGINT AUTO_INCREMENT PRIMARY KEY, order_id BIGINT NOT NULL, status_code VARCHAR(32) NOT NULL)");
    }
}
