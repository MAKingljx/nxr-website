package com.nxr.platform.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nxr.platform.commerce.CommercePolicyService;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.web.server.ResponseStatusException;

class MerchantBatchServiceTest {

    private JdbcTemplate jdbc;
    private CustomerPortalService portal;
    private OrderFulfillmentService fulfillment;
    private CommercePolicyService commerce;
    private MerchantBatchService service;
    private final AtomicInteger orderSequence = new AtomicInteger(100);
    private final List<CommercePolicyService.AllocatedBatchQuote> usedAllocations = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:nxr_merchant_batch;MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("order_fulfillment_h2.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("merchant_batch_h2.sql"));
        }
        portal = mock(CustomerPortalService.class);
        fulfillment = mock(OrderFulfillmentService.class);
        commerce = mock(CommercePolicyService.class);
        service = new MerchantBatchService(
            JdbcClient.create(jdbc), portal, fulfillment, commerce, new DataSourceTransactionManager(dataSource)
        );
        when(commerce.quoteBatch(anyLong(), any(), any(), any(), any())).thenReturn(batchQuote());
        when(portal.createOrderWithBatchQuote(anyLong(), any(), any(), any())).thenAnswer(invocation -> {
            usedAllocations.add(invocation.getArgument(3));
            return insertOrder();
        });
    }

    @Test
    void createsOneAtomicBatchWithExactAllocatedQuoteAndRevocablePrivateTokens() {
        MerchantBatchService.BatchCreateResult result = service.createBatch(1, request());

        assertThat(result.acceptedRows()).isEqualTo(2);
        assertThat(result.rejectedRows()).isZero();
        assertThat(result.rows()).extracting(MerchantBatchService.BatchCreateRow::trackingToken)
            .doesNotContainNull().doesNotHaveDuplicates();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM grading_order", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM merchant_order_batch_item", Integer.class)).isEqualTo(2);
        assertThat(usedAllocations).extracting(CommercePolicyService.AllocatedBatchQuote::reference)
            .containsExactly("CLIENT-A", "CLIENT-B");
        assertThat(usedAllocations).extracting(CommercePolicyService.AllocatedBatchQuote::returnShippingFee)
            .containsExactly(new BigDecimal("3.00"), new BigDecimal("3.00"));
        assertThat(usedAllocations.stream().map(CommercePolicyService.AllocatedBatchQuote::totalAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("26.00");

        MerchantBatchService.BatchCreateRow first = result.rows().get(0);
        MerchantBatchService.PublicTrackingResponse publicView = service.publicTracking(first.trackingToken());
        assertThat(publicView.orderNo()).isEqualTo(first.orderNo());
        assertThat(publicView.clientReference()).isEqualTo("CLIENT-A");
        assertThat(publicView.timeline()).isEmpty();
        assertThat(publicView.shipments()).isEmpty();
        org.springframework.http.ResponseEntity<MerchantBatchService.PublicTrackingResponse> http =
            new MerchantBatchPublicController(service).tracking(first.trackingToken());
        assertThat(http.getHeaders().getCacheControl()).contains("no-store");
        assertThat(http.getHeaders().getFirst("Referrer-Policy")).isEqualTo("no-referrer");
        assertThatThrownBy(() -> service.requireMerchantBatch(2, result.batchNo()))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("not found");

        service.revokeTrackingToken(1, result.batchNo(), first.orderNo());
        assertThatThrownBy(() -> service.publicTracking(first.trackingToken()))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("invalid or revoked");
    }

    @Test
    void rollsBackHeaderOrdersAndTokensWhenAnyChildCreationFails() {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            CustomerPortalService.OrderDetailResponse inserted = insertOrder();
            if (calls.incrementAndGet() == 2) throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "bad row");
            return inserted;
        }).when(portal).createOrderWithBatchQuote(anyLong(), any(), any(), any());

        assertThatThrownBy(() -> service.createBatch(1, request()))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("bad row");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM grading_order", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM merchant_order_batch", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM merchant_order_batch_item", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM merchant_batch_tracking_token", Integer.class)).isZero();
    }

    @Test
    void rejectsMixedReturnDestinationsBeforeCreatingAnything() {
        MerchantBatchService.BatchCreateRequest mixed = new MerchantBatchService.BatchCreateRequest(
            "mixed.csv", "Mixed", List.of(
                row("CLIENT-A", "1 Agent Way"), row("CLIENT-B", "2 Other Way")
            )
        );
        assertThatThrownBy(() -> service.createBatch(1, mixed))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("same return address");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM merchant_order_batch", Integer.class)).isZero();
    }

    @Test
    void sharedBatchShipmentWorkflowStillMovesEveryChildTogether() {
        MerchantBatchService.BatchCreateResult created = service.createBatch(1, request());
        jdbc.update(
            "UPDATE grading_order SET status_code = 'awaiting_inbound' WHERE id IN (SELECT order_id FROM merchant_order_batch_item WHERE batch_id = ?)",
            created.batchId()
        );

        MerchantBatchService.BatchDetail inbound = service.createInboundShipment(
            1, created.batchNo(), new MerchantBatchService.BatchShipmentRequest("UPS", "MASTER-IN", null)
        );
        assertThat(inbound.statusCode()).isEqualTo("inbound_shipped");
        assertThat(inbound.orders()).extracting(MerchantBatchService.BatchOrderItem::statusCode)
            .containsOnly("inbound_shipped");

        jdbc.update(
            "UPDATE grading_order SET status_code = 'completed' WHERE id IN (SELECT order_id FROM merchant_order_batch_item WHERE batch_id = ?)",
            created.batchId()
        );
        MerchantBatchService.BatchDetail outbound = service.createOutboundShipment(
            created.batchId(), 901, new MerchantBatchService.BatchShipmentRequest("DHL", "MASTER-OUT", null)
        );
        assertThat(outbound.statusCode()).isEqualTo("return_shipped");
        assertThat(outbound.orders()).extracting(MerchantBatchService.BatchOrderItem::statusCode)
            .containsOnly("return_shipped");
        MerchantBatchService.BatchShipment outboundShipment = outbound.shipments().stream()
            .filter(shipment -> shipment.directionCode().equals("outbound"))
            .findFirst()
            .orElseThrow();

        MerchantBatchService.BatchDetail delivered = service.markShipmentDelivered(
            created.batchId(), outboundShipment.id(), 901
        );
        assertThat(delivered.statusCode()).isEqualTo("delivered");
        assertThat(delivered.orders()).extracting(MerchantBatchService.BatchOrderItem::statusCode)
            .containsOnly("delivered");
    }

    private CustomerPortalService.OrderDetailResponse insertOrder() {
        int id = orderSequence.incrementAndGet();
        String orderNo = "NXR-BATCH-" + id;
        jdbc.update(
            """
            INSERT INTO grading_order (
              id,order_no,customer_id,status_code,service_level_code,total_card_count,
              service_fee,return_shipping_fee,total_amount,currency_code,contact_name,contact_phone,
              return_address_line1,return_city,return_postal_code,return_country
            ) VALUES (?,?,1,'pending_review','basic_grading',1,10,3,13,'USD','Agent','1',?,'City','1','US')
            """,
            id, orderNo, "1 Agent Way"
        );
        CustomerPortalService.OrderDetailResponse response = mock(CustomerPortalService.OrderDetailResponse.class);
        when(response.id()).thenReturn((long) id);
        when(response.orderNo()).thenReturn(orderNo);
        return response;
    }

    private MerchantBatchService.BatchCreateRequest request() {
        return new MerchantBatchService.BatchCreateRequest(
            "orders.csv", "September intake", List.of(row("CLIENT-A", "1 Agent Way"), row("CLIENT-B", "1 Agent Way"))
        );
    }

    private MerchantBatchService.BatchOrderRequest row(String reference, String address) {
        CustomerPortalService.OrderItemRequest item = new CustomerPortalService.OrderItemRequest(
            "Card", "Brand", "Set", "1", "EN", BigDecimal.ONE, null
        );
        CustomerPortalService.CreateOrderRequest order = new CustomerPortalService.CreateOrderRequest(
            "basic_grading", null, false, "agent_return", "Agent", "1", address, null,
            "City", null, "1", "US", null, List.of(), List.of(item), "USD"
        );
        return new MerchantBatchService.BatchOrderRequest(reference, "Private client", "contact hint", order);
    }

    private CommercePolicyService.BatchQuoteResult batchQuote() {
        CommercePolicyService.QuoteResult aggregate = new CommercePolicyService.QuoteResult(
            1, "business", "US", "USD", 2, 1L, 2L, "policy", "policy", new BigDecimal("10.00"),
            new BigDecimal("20.00"), new BigDecimal("6.00"), new BigDecimal("26.00"),
            50, 100, 200, 500, "agent_return", "Agent return", null
        );
        return new CommercePolicyService.BatchQuoteResult(aggregate, List.of(
            new CommercePolicyService.AllocatedBatchQuote("CLIENT-A", 1, new BigDecimal("10.00"), new BigDecimal("3.00"), new BigDecimal("13.00")),
            new CommercePolicyService.AllocatedBatchQuote("CLIENT-B", 1, new BigDecimal("10.00"), new BigDecimal("3.00"), new BigDecimal("13.00"))
        ));
    }
}
