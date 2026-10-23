package com.nxr.platform.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import com.nxr.platform.commerce.CommercePolicyService;
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
    void realMerchantBatchCreationCreatesNoPaymentAndDirectOrderRemainsPayable() {
        // The production commerce migration adds these metadata columns.
        for(String column:List.of("order_origin_code VARCHAR(32)","business_line_id BIGINT","work_center_id BIGINT",
            "commerce_price_policy_id BIGINT","commerce_shipping_policy_id BIGINT","quoted_unit_price DECIMAL(12,2)",
            "quoted_card_weight_grams INT","quoted_packaging_weight_grams INT","quoted_chargeable_weight_grams INT",
            "pricing_source_code VARCHAR(32)","shipping_source_code VARCHAR(32)"))jdbcTemplate.execute("ALTER TABLE grading_order ADD "+column);
        var address=fulfillmentService.saveAddress(1,null,new OrderFulfillmentService.AddressRequest("Partner","Partner","123","Partner street",null,"City",null,"1","US",true));
        var routing=new CommercePolicyService.RoutingAssignment(null,null,null,null,"customer_submission");
        var quote=new CommercePolicyService.QuoteResult(1,"business","US","USD",1,null,null,"global_price","global_shipping",
            new BigDecimal("20.00"),new BigDecimal("20.00"),new BigDecimal("12.00"),new BigDecimal("32.00"),null,null,null,null,"economy_line","Economy Line",routing);
        var order=portalService.createOrderWithBatchQuote(1,orderRequest(address.id(),"economy_line","EN",1,"Partner"),quote,
            new CommercePolicyService.AllocatedBatchQuote("A",1,new BigDecimal("20.00"),new BigDecimal("12.00"),new BigDecimal("32.00")));
        assertThat(order.statusCode()).isEqualTo("awaiting_inbound");assertThat(order.intakeCode()).isNotBlank();
        assertThat(order.totalAmount()).isEqualByComparingTo("32.00");assertThat(order.payments()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment_record WHERE order_id=?",Integer.class,order.id())).isZero();
        var unquoted=new CommercePolicyService.QuoteResult(1,"business","US","USD",1,null,null,"unquoted_business","global_shipping",
            BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,null,null,null,null,"economy_line","Economy Line",routing);
        var unpriced=portalService.createOrderWithBatchQuote(1,orderRequest(address.id(),"economy_line","EN",1,"Unquoted partner"),unquoted,
            new CommercePolicyService.AllocatedBatchQuote("B",1,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO));
        assertThat(unpriced.payments()).isEmpty();assertThat(unpriced.totalAmount()).isEqualByComparingTo("0");
        var weightQuote=new CommercePolicyService.QuoteResult(1,"business","US","CNY",1,null,42L,"unquoted_business","weight_policy",
            BigDecimal.ZERO,BigDecimal.ZERO,new BigDecimal("5.00"),new BigDecimal("5.00"),50,100,150,300,"weight_42","Weight return",routing);
        var weightRequest=new CustomerPortalService.CreateOrderRequest("basic_grading",address.id(),false,"weight_42",
            null,null,null,null,null,null,null,null,"Selected weight route",List.of(new CustomerPortalService.LanguageGroupRequest("EN",1)),List.of(),"CNY");
        var weightOrder=portalService.createOrderWithBatchQuote(1,weightRequest,weightQuote,
            new CommercePolicyService.AllocatedBatchQuote("C",1,BigDecimal.ZERO,new BigDecimal("5.00"),new BigDecimal("5.00")));
        assertThat(weightOrder.returnShippingOptionCode()).isEqualTo("weight_42");assertThat(weightOrder.currencyCode()).isEqualTo("CNY");
        assertThat(weightOrder.payments()).isEmpty();
        var direct=portalService.createOrder(1,orderRequest(address.id(),"economy_line","EN",1,"Direct order"));
        assertThat(direct.statusCode()).isEqualTo("awaiting_payment");assertThat(direct.payments()).hasSize(1);
        jdbcTemplate.update("INSERT INTO merchant_order_batch(id,batch_no,merchant_customer_id,batch_name,status_code,total_rows,accepted_rows) VALUES(90,'MB-NONPAYING',1,'Partner','open',1,1)");
        jdbcTemplate.update("INSERT INTO merchant_order_batch_item(batch_id,order_id,row_no,client_reference) VALUES(90,?,1,'A')",order.id());
        for(long id:List.of(order.id(),direct.id())) {
            jdbcTemplate.update("UPDATE grading_order SET status_code='completed' WHERE id=?",id);
            jdbcTemplate.update("INSERT INTO shipping_change_request(order_id,old_option_code,old_option_name,old_price_amount,new_option_code,new_option_name,new_price_amount,currency_code,difference_amount,status_code,reason) VALUES(?,'economy_line','Economy',12,'standard_express','Express',25,'USD',13,'awaiting_settlement','Approved upgrade')",id);
        }
        assertThat(fulfillmentService.assertOutboundReady(order.id()).optionCode()).isEqualTo("standard_express");
        assertThatThrownBy(()->fulfillmentService.assertOutboundReady(direct.id())).hasMessageContaining("Settle the return shipping change");
        assertThat(jdbcTemplate.queryForObject("SELECT status_code FROM shipping_change_request WHERE order_id=?",String.class,order.id())).isEqualTo("awaiting_settlement");
    }

    @Test
    void lateLegacyMerchantPaymentIsRecordedWithoutPausingOrAdvancingFulfillment() {
        var address=fulfillmentService.saveAddress(1,null,new OrderFulfillmentService.AddressRequest("Partner","Partner","123","Partner street",null,"City",null,"1","US",true));
        var order=portalService.createOrder(1,orderRequest(address.id(),"economy_line","EN",1,"Legacy partner"));
        var session=portalService.createPaymentSession(1,order.orderNo(),new CustomerPortalService.PaymentSessionRequest("stripe"));
        jdbcTemplate.update("INSERT INTO merchant_order_batch(id,batch_no,merchant_customer_id,batch_name,status_code,total_rows,accepted_rows) VALUES(91,'MB-LEGACY',1,'Legacy','open',1,1)");
        jdbcTemplate.update("INSERT INTO merchant_order_batch_item(batch_id,order_id,row_no,client_reference) VALUES(91,?,1,'A')",order.id());
        jdbcTemplate.update("UPDATE grading_order SET status_code='grading' WHERE id=?",order.id());
        portalService.receivePaymentCallback("stripe",new CustomerPortalService.PaymentCallbackRequest("legacy-event",session.paymentNo(),"legacy-transaction",order.totalAmount(),"USD","succeeded","{}"));
        assertThat(jdbcTemplate.queryForObject("SELECT status_code FROM grading_order WHERE id=?",String.class,order.id())).isEqualTo("grading");
        assertThat(jdbcTemplate.queryForObject("SELECT status_code FROM payment_record WHERE order_id=?",String.class,order.id())).isEqualTo("confirmed");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_timeline_event WHERE order_id=? AND event_code='merchant_payment_recorded'",Integer.class,order.id())).isEqualTo(1);
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

    @Test
    void returnShippingPriceChangeOnlyAffectsNewOrderAndPaymentSnapshots() {
        OrderFulfillmentService.CustomerAddress address = fulfillmentService.saveAddress(
            1L,
            null,
            new OrderFulfillmentService.AddressRequest(
                "Home", "Merchant User", "+1 555 0100", "1 Shipping Snapshot Street", null,
                "Los Angeles", "CA", "90001", "US", true
            )
        );
        CustomerPortalService.OrderDetailResponse original = portalService.createOrder(
            1L, orderRequest(address.id(), "economy_line", "EN", 1, "Original return price")
        );
        long optionId = fulfillmentService.listShippingOptions("US", true).stream()
            .filter(option -> option.optionCode().equals("economy_line"))
            .findFirst()
            .orElseThrow()
            .id();

        fulfillmentService.saveShippingOption(optionId, new OrderFulfillmentService.ShippingOptionRequest(
            "economy_line", "Economy Plus", "Updated tracked economy return", "*", "USD",
            new BigDecimal("18.50"), 10, true
        ));
        CustomerPortalService.OrderDetailResponse next = portalService.createOrder(
            1L, orderRequest(address.id(), "economy_line", "JA", 1, "Updated return price")
        );
        CustomerPortalService.OrderDetailResponse persistedOriginal =
            portalService.requireCustomerOrder(1L, original.orderNo());

        assertThat(persistedOriginal.returnShippingOptionName()).isEqualTo("Economy Line");
        assertThat(persistedOriginal.returnShippingFee()).isEqualByComparingTo("12.00");
        assertThat(persistedOriginal.totalAmount()).isEqualByComparingTo("32.00");
        assertThat(persistedOriginal.payments()).singleElement()
            .satisfies(payment -> assertThat(payment.amount()).isEqualByComparingTo("32.00"));

        assertThat(next.returnShippingOptionName()).isEqualTo("Economy Plus");
        assertThat(next.returnShippingFee()).isEqualByComparingTo("18.50");
        assertThat(next.totalAmount()).isEqualByComparingTo("38.50");
        assertThat(next.payments()).singleElement()
            .satisfies(payment -> assertThat(payment.amount()).isEqualByComparingTo("38.50"));
    }

    @Test
    void merchantBatchChildRejectsEveryIndividualShipmentMutation() {
        OrderFulfillmentService.CustomerAddress address = fulfillmentService.saveAddress(
            1L,
            null,
            new OrderFulfillmentService.AddressRequest(
                "Agent", "Merchant User", "+1 555 0100", "20 Merchant Way", null,
                "Seattle", "WA", "98101", "US", true
            )
        );
        CustomerPortalService.OrderDetailResponse order = portalService.createOrder(
            1L, orderRequest(address.id(), "economy_line", "EN", 1, "Batch child")
        );
        jdbcTemplate.update("UPDATE grading_order SET status_code = 'awaiting_inbound' WHERE id = ?", order.id());
        jdbcTemplate.update(
            "INSERT INTO order_shipment (order_id, direction_code, carrier_name, tracking_number, status_code) VALUES (?, 'outbound', 'DHL', 'OLD-OUT', 'shipped')",
            order.id()
        );
        long shipmentId = jdbcTemplate.queryForObject(
            "SELECT id FROM order_shipment WHERE order_id = ?", Long.class, order.id()
        );
        jdbcTemplate.update(
            "INSERT INTO merchant_order_batch (id, batch_no, merchant_customer_id, batch_name, status_code, total_rows, accepted_rows, rejected_rows) VALUES (91, 'MB-TEST', 1, 'Test batch', 'open', 1, 1, 0)"
        );
        jdbcTemplate.update(
            "INSERT INTO merchant_order_batch_item (batch_id, order_id, row_no, client_reference) VALUES (91, ?, 1, 'client-1')",
            order.id()
        );

        CustomerPortalService.CreateShipmentRequest inbound =
            new CustomerPortalService.CreateShipmentRequest("inbound", "UPS", "IN-CHILD", null);
        CustomerPortalService.CreateShipmentRequest outbound =
            new CustomerPortalService.CreateShipmentRequest("outbound", "DHL", "OUT-CHILD", null);

        assertThatThrownBy(() -> portalService.addInboundShipment(1L, order.orderNo(), inbound))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("shared shipment workflow");
        assertThatThrownBy(() -> portalService.createAdminShipment(order.id(), 901L, inbound))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("shared shipment workflow");
        assertThatThrownBy(() -> portalService.createAdminShipment(order.id(), 901L, outbound))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("shared shipment workflow");
        assertThatThrownBy(() -> portalService.markShipmentDelivered(order.id(), shipmentId, 901L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("shared shipment workflow");
        assertThatThrownBy(() -> fulfillmentService.addTrackingEvent(
            order.id(), shipmentId, 901L,
            new OrderFulfillmentService.TrackingEventRequest("delivered", null, null, null, null)
        ))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("shared shipment workflow");

        for (String target : List.of("inbound_shipped", "return_shipped", "delivered")) {
            assertThatThrownBy(() -> portalService.updateOrderStatusByAdmin(order.id(), 901L,
                new CustomerPortalService.UpdateOrderStatusRequest(target, "Cannot bypass the master parcel")))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("shared shipment workflow");
        }
        var link = portalService.requireAdminOrder(order.id()).merchantBatch();
        assertThat(link.batchId()).isEqualTo(91L);
        assertThat(link.batchNo()).isEqualTo("MB-TEST");
        assertThat(portalService.requireCustomerOrder(1L, order.orderNo()).merchantBatch()).isEqualTo(link);
        jdbcTemplate.update("UPDATE merchant_order_batch SET status_code='cancelled' WHERE id=91");
        assertThat(portalService.requireAdminOrder(order.id()).merchantBatch()).isNull();

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM order_shipment WHERE order_id = ?", Integer.class, order.id()
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status_code FROM order_shipment WHERE id = ?", String.class, shipmentId
        )).isEqualTo("shipped");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM shipment_tracking_event WHERE shipment_id = ?", Integer.class, shipmentId
        )).isZero();
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
