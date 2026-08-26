package com.nxr.platform.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.web.server.ResponseStatusException;

class OrderFulfillmentWorkflowTest {

    private JdbcTemplate jdbcTemplate;
    private CustomerPortalService portalService;
    private OrderFulfillmentService fulfillmentService;
    private MerchantBulkOrderService merchantBulkOrderService;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:nxr_order_fulfillment;MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbcTemplate = new JdbcTemplate(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("order_fulfillment_h2.sql"));
        }
        JdbcClient jdbcClient = JdbcClient.create(jdbcTemplate);
        fulfillmentService = new OrderFulfillmentService(jdbcClient, jdbcTemplate);
        portalService = new CustomerPortalService(jdbcClient, jdbcTemplate, fulfillmentService);
        merchantBulkOrderService = new MerchantBulkOrderService(portalService, fulfillmentService);
        seedReferenceData();
    }

    @Test
    void completesPaymentIntakeGradingReturnAndSupportWorkflow() {
        OrderFulfillmentService.CustomerAddress address = fulfillmentService.saveAddress(
            1L,
            null,
            new OrderFulfillmentService.AddressRequest(
                "Home", "Merchant User", "+1 555 0100", "1 Original Street", null,
                "Los Angeles", "CA", "90001", "US", true
            )
        );

        CustomerPortalService.OrderDetailResponse created = portalService.createOrder(
            1L,
            orderRequest(address.id(), "economy_line", "EN", 2, "First shipment")
        );
        assertThat(created.statusCode()).isEqualTo("awaiting_payment");
        assertThat(created.serviceFee()).isEqualByComparingTo("40.00");
        assertThat(created.returnShippingFee()).isEqualByComparingTo("12.00");
        assertThat(created.totalAmount()).isEqualByComparingTo("52.00");
        assertThat(created.returnShippingOptionCode()).isEqualTo("economy_line");
        assertThat(created.items()).hasSize(2).allMatch(item -> item.languageCode().equals("EN"));

        fulfillmentService.saveAddress(
            1L,
            address.id(),
            new OrderFulfillmentService.AddressRequest(
                "Home", "Merchant User", "+1 555 0100", "99 Changed Street", null,
                "Los Angeles", "CA", "90001", "US", true
            )
        );
        assertThat(portalService.requireCustomerOrder(1L, created.orderNo()).returnAddressLine1())
            .isEqualTo("1 Original Street");

        CustomerPortalService.PaymentSessionResponse session = portalService.createPaymentSession(
            1L, created.orderNo(), new CustomerPortalService.PaymentSessionRequest("stripe")
        );
        CustomerPortalService.PaymentCallbackResponse callback = portalService.receivePaymentCallback(
            "stripe",
            new CustomerPortalService.PaymentCallbackRequest(
                "evt-001", session.paymentNo(), "txn-001", new BigDecimal("52.00"), "USD", "succeeded", "{}"
            )
        );
        assertThat(callback.duplicate()).isFalse();
        assertThat(portalService.receivePaymentCallback(
            "stripe",
            new CustomerPortalService.PaymentCallbackRequest(
                "evt-001", session.paymentNo(), "txn-001", new BigDecimal("52.00"), "USD", "succeeded", "{}"
            )
        ).duplicate()).isTrue();

        CustomerPortalService.OrderDetailResponse paid = portalService.requireCustomerOrder(1L, created.orderNo());
        assertThat(paid.statusCode()).isEqualTo("awaiting_inbound");
        assertThat(paid.intakeCode()).isNotBlank();
        assertThat(fulfillmentService.requirePackingSlip(1L, created.orderNo()).languageGroups())
            .singleElement().satisfies(group -> {
                assertThat(group.languageCode()).isEqualTo("EN");
                assertThat(group.quantity()).isEqualTo(2);
            });

        portalService.addInboundShipment(
            1L, created.orderNo(),
            new CustomerPortalService.CreateShipmentRequest("inbound", "UPS", "IN-001", "Customer shipment")
        );
        fulfillmentService.receiveOrder(
            created.id(), 900L,
            new OrderFulfillmentService.ReceiveOrderRequest(paid.intakeCode(), "PKG-001", 2, "Parcel intact", List.of())
        );
        assertThat(portalService.requireAdminOrder(created.id()).statusCode()).isEqualTo("received");

        assertThatThrownBy(() -> portalService.createAdminShipment(
            created.id(), 901L,
            new CustomerPortalService.CreateShipmentRequest("outbound", "DHL", "OUT-EARLY", "Too early")
        )).isInstanceOf(ResponseStatusException.class).hasMessageContaining("final quality check");

        CustomerPortalService.OrderDetailResponse received = portalService.requireAdminOrder(created.id());
        portalService.linkOrderItemSubmission(created.id(), received.items().get(0).id(), 101L, 901L);
        portalService.linkOrderItemSubmission(created.id(), received.items().get(1).id(), 102L, 901L);
        OrderFulfillmentService.AdminOperationsResponse operations = fulfillmentService.loadAdminOperations(created.id());
        assertThat(operations.workTasks()).hasSize(8);
        for (OrderFulfillmentService.WorkTaskRecord task : operations.workTasks()) {
            fulfillmentService.updateWorkTask(
                created.id(), task.id(), 901L,
                new OrderFulfillmentService.WorkTaskUpdateRequest("completed", "Completed in test", null)
            );
        }
        portalService.updateOrderStatusByAdmin(
            created.id(), 901L, new CustomerPortalService.UpdateOrderStatusRequest("review", "Manual review completed")
        );
        fulfillmentService.qualityCheck(
            created.id(), 902L, new OrderFulfillmentService.QualityCheckRequest(true, "Encapsulation and final inspection passed")
        );
        assertThat(portalService.requireAdminOrder(created.id()).statusCode()).isEqualTo("completed");

        OrderFulfillmentService.TicketRecord ticket = fulfillmentService.createTicket(
            1L, created.orderNo(),
            new OrderFulfillmentService.TicketRequest("inquiry", "Return timing", "When will this ship?", null)
        );
        fulfillmentService.updateTicketByAdmin(
            created.id(), ticket.id(), 903L,
            new OrderFulfillmentService.AdminTicketRequest("resolved", "It will ship after the option change settles.", null)
        );

        OrderFulfillmentService.ShippingChangeRecord change = fulfillmentService.requestShippingChange(
            1L, created.orderNo(),
            new OrderFulfillmentService.ShippingChangeRequest("standard_express", "Need faster return", null)
        );
        change = fulfillmentService.reviewShippingChange(
            created.id(), change.id(), 903L,
            new OrderFulfillmentService.ReviewShippingChangeRequest(true, "Approved; collect surcharge")
        );
        assertThat(change.statusCode()).isEqualTo("awaiting_settlement");
        assertThat(change.differenceAmount()).isEqualByComparingTo("13.00");
        fulfillmentService.settleShippingChange(
            created.id(), change.id(), 904L,
            new OrderFulfillmentService.SettleShippingChangeRequest("adj-001", "Surcharge received")
        );

        CustomerPortalService.OrderDetailResponse outbound = portalService.createAdminShipment(
            created.id(), 905L,
            new CustomerPortalService.CreateShipmentRequest("outbound", "DHL", "OUT-001", "Return shipment")
        );
        CustomerPortalService.ShipmentRecord outboundShipment = outbound.shipments().stream()
            .filter(shipment -> shipment.directionCode().equals("outbound"))
            .findFirst().orElseThrow();
        assertThat(outboundShipment.shippingOptionCode()).isEqualTo("standard_express");
        fulfillmentService.addTrackingEvent(
            created.id(), outboundShipment.id(), 905L,
            new OrderFulfillmentService.TrackingEventRequest("delivered", null, "Los Angeles", "Signed by customer", null)
        );

        CustomerPortalService.OrderDetailResponse delivered = portalService.requireCustomerOrder(1L, created.orderNo());
        assertThat(delivered.statusCode()).isEqualTo("delivered");
        assertThat(delivered.totalAmount()).isEqualByComparingTo("52.00");
        assertThat(delivered.payments()).hasSize(2);
        assertThat(delivered.payments().stream().filter(payment -> payment.paymentTypeCode().equals("shipping_adjustment")))
            .singleElement().satisfies(payment -> assertThat(payment.amount()).isEqualByComparingTo("13.00"));

        OrderFulfillmentService.CustomerOperationsResponse customerOperations = fulfillmentService.loadCustomerOperations(1L, created.orderNo());
        assertThat(customerOperations.trackingEvents()).singleElement().satisfies(event -> assertThat(event.eventCode()).isEqualTo("delivered"));
        assertThat(customerOperations.tickets()).hasSize(2);
        assertThat(customerOperations.shippingChanges()).singleElement().satisfies(item -> assertThat(item.statusCode()).isEqualTo("settled"));
    }

    @Test
    void merchantImportKeepsValidRowsWhenAnotherRowFails() {
        OrderFulfillmentService.CustomerAddress address = fulfillmentService.saveAddress(
            1L,
            null,
            new OrderFulfillmentService.AddressRequest(
                "Warehouse", "Merchant User", "+1 555 0100", "20 Merchant Way", null,
                "Seattle", "WA", "98101", "US", true
            )
        );
        MerchantBulkOrderService.BulkOrderRequest request = new MerchantBulkOrderService.BulkOrderRequest(
            "merchant-orders.csv",
            List.of(
                orderRequest(address.id(), "economy_line", "JA", 3, "Valid row"),
                orderRequest(address.id(), "economy_line", "EN", 0, "Invalid row")
            )
        );

        OrderFulfillmentService.MerchantImportResult result = merchantBulkOrderService.createOrders(1L, request);

        assertThat(result.acceptedRows()).isEqualTo(1);
        assertThat(result.rejectedRows()).isEqualTo(1);
        assertThat(result.rows()).extracting(OrderFulfillmentService.MerchantImportRow::statusCode)
            .containsExactly("accepted", "rejected");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM grading_order", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM grading_order_item", Integer.class)).isEqualTo(3);
    }

    @Test
    void servicePriceChangeOnlyAffectsNewOrders() {
        OrderFulfillmentService.CustomerAddress address = fulfillmentService.saveAddress(
            1L,
            null,
            new OrderFulfillmentService.AddressRequest(
                "Home", "Merchant User", "+1 555 0100", "1 Snapshot Street", null,
                "Los Angeles", "CA", "90001", "US", true
            )
        );
        CustomerPortalService.OrderDetailResponse original = portalService.createOrder(
            1L, orderRequest(address.id(), "economy_line", "EN", 1, "Original price")
        );

        OrderFulfillmentService.ServicePrice updated = fulfillmentService.saveServicePrice(
            new OrderFulfillmentService.ServicePriceRequest("Basic grading", new BigDecimal("22.50"), "USD")
        );
        CustomerPortalService.OrderDetailResponse next = portalService.createOrder(
            1L, orderRequest(address.id(), "economy_line", "JA", 1, "Updated price")
        );

        assertThat(updated.versionNo()).isEqualTo(2);
        assertThat(portalService.requireCustomerOrder(1L, original.orderNo()).totalAmount()).isEqualByComparingTo("32.00");
        assertThat(next.serviceFee()).isEqualByComparingTo("22.50");
        assertThat(next.totalAmount()).isEqualByComparingTo("34.50");
    }

    private CustomerPortalService.CreateOrderRequest orderRequest(
        long addressId,
        String shippingOptionCode,
        String languageCode,
        int quantity,
        String note
    ) {
        return new CustomerPortalService.CreateOrderRequest(
            "basic_grading", addressId, false, shippingOptionCode,
            null, null, null, null, null, null, null, null, note,
            List.of(new CustomerPortalService.LanguageGroupRequest(languageCode, quantity)), List.of()
        );
    }

    private void seedReferenceData() {
        jdbcTemplate.update(
            "INSERT INTO customer_account (id, email, password_hash, display_name, mobile, account_type_code) VALUES (?, ?, ?, ?, ?, ?)",
            1L, "merchant@example.test", "not-used", "Merchant User", "+1 555 0100", "merchant"
        );
        jdbcTemplate.update(
            "INSERT INTO grading_service_price (price_code, display_name, unit_price, currency_code, is_active, version_no) VALUES (?, ?, ?, ?, ?, ?)",
            "basic_grading", "Basic grading", new BigDecimal("20.00"), "USD", 1, 1
        );
        jdbcTemplate.update(
            "INSERT INTO return_shipping_option (option_code, display_name, description, country_scope, currency_code, price_amount, sort_order, is_active) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            "economy_line", "Economy Line", "Tracked economy return", "*", "USD", new BigDecimal("12.00"), 10, 1
        );
        jdbcTemplate.update(
            "INSERT INTO return_shipping_option (option_code, display_name, description, country_scope, currency_code, price_amount, sort_order, is_active) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            "standard_express", "Standard Express", "Tracked express return", "US,CA", "USD", new BigDecimal("25.00"), 20, 1
        );
        jdbcTemplate.update("INSERT INTO grading_submission (id, cert_id, status_code) VALUES (101, 'TEST101', 'review')");
        jdbcTemplate.update("INSERT INTO grading_submission (id, cert_id, status_code) VALUES (102, 'TEST102', 'review')");
    }
}
