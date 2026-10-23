package com.nxr.platform.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nxr.platform.admin.AdminOrderController;
import com.nxr.platform.commerce.OrderAccessScopeService;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CustomerPortalOrderChannelTest {
    private JdbcTemplate jdbc;
    private CustomerPortalService service;

    @BeforeEach void setup() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:order_channel_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("order_fulfillment_h2.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("merchant_batch_h2.sql"));
        }
        jdbc.execute("ALTER TABLE grading_order ADD COLUMN business_line_id BIGINT");
        jdbc.execute("ALTER TABLE grading_order ADD COLUMN work_center_id BIGINT");
        jdbc.execute("CREATE TABLE commerce_business_line(id BIGINT PRIMARY KEY,is_active TINYINT)");
        jdbc.execute("CREATE TABLE commerce_work_center(id BIGINT PRIMARY KEY,is_active TINYINT)");
        jdbc.update("INSERT INTO customer_account(id,email,password_hash,display_name,account_type_code) VALUES(3,'person@example.test','unused','Individual','customer')");
        jdbc.update("INSERT INTO merchant_order_batch(id,batch_no,merchant_customer_id,batch_name,status_code,total_rows,accepted_rows) VALUES(10,'B-10',1,'Partner parcel','created',2,2),(20,'B-20',2,'Other owner','created',1,1),(30,'B-30',3,'Invalid individual batch','created',1,1)");
        service = new CustomerPortalService(JdbcClient.create(jdbc), jdbc);
    }

    @ParameterizedTest
    @CsvSource(value={
        "NULL|false|awaiting_inbound",
        "pending_review|false|admission_review",
        "rejected|true|admission_review",
        "needs_information|true|admission_review",
        "approved|false|terms_confirmation",
        "approved|true|awaiting_inbound"
    }, delimiter='|', nullValues="NULL")
    void historicalPartnerPaymentStatesAreProjectedConsistentlyAndReadsNeverRewriteHistory(String admission,boolean accepted,String expected) {
        insertOrder(101,1,"payment_expired");
        link(101,10);
        jdbc.update("UPDATE grading_order SET admission_status_code=?,approved_terms_version='v1',accepted_terms_version=?,terms_accepted_at=? WHERE id=101",
            admission, accepted?"v1":null, accepted?java.sql.Timestamp.valueOf("2026-10-01 10:00:00"):null);
        var before=jdbc.queryForList("SELECT * FROM grading_order");
        var list=service.listAdminOrders(1,20,expected,null,"partner");
        assertThat(list.total()).isEqualTo(1);
        assertThat(list.items()).singleElement().satisfies(row -> {
            assertThat(row.statusCode()).isEqualTo(expected);
            assertThat(row.orderChannel()).isEqualTo("partner");
            assertThat(row.admissionStatus()).isEqualTo(admission);
        });
        assertThat(service.listAdminOrders(1,20,"payment_expired",null,"partner").total()).isZero();
        var detail=service.requireAdminOrder(101);
        assertThat(detail.statusCode()).isEqualTo(expected);
        assertThat(detail.orderChannel()).isEqualTo("partner");
        assertThat(service.requireCustomerOrder(1,"NXR-101").statusCode()).isEqualTo(expected);
        assertThat(service.listCustomerOrders(1,1,20).items()).singleElement()
            .extracting(CustomerPortalService.OrderListItem::statusCode).isEqualTo(expected);
        assertThat(jdbc.queryForList("SELECT * FROM grading_order")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_record",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_timeline_event",Integer.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings={"awaiting_payment","payment_review","payment_expired","payment_exception","terms_confirmation"})
    void individualPaymentStatesAndMerchantOrdersWithoutAValidParcelStayUntouched(String paymentStatus) {
        insertOrder(101,1,paymentStatus); // Merchant account with an individual order.
        insertOrder(102,1,paymentStatus); // Damaged cross-company link is not a partner order.
        insertOrder(103,3,paymentStatus); // A collector account cannot become a partner through a batch row.
        link(102,20);
        link(103,30);
        assertThat(service.listAdminOrders(1,20,null,null,"partner").total()).isZero();
        assertThat(service.listAdminOrders(1,20,paymentStatus,null,"individual").items()).hasSize(3)
            .allSatisfy(row -> {
                assertThat(row.statusCode()).isEqualTo(paymentStatus);
                assertThat(row.orderChannel()).isEqualTo("individual");
            });
        for(long id:List.of(101L,102L,103L)) {
            assertThat(service.requireAdminOrder(id).statusCode()).isEqualTo(paymentStatus);
            assertThat(service.requireAdminOrder(id).orderChannel()).isEqualTo("individual");
        }
    }

    @Test void partnerFilteringHappensBeforeCountAndPaginationAndRespectsExistingStaffScope() {
        for(long id:List.of(101L,102L,103L,104L))insertOrder(id,1,"payment_expired");
        link(101,10);link(103,10);
        var first=service.listAdminOrders(1,1,"awaiting_inbound",null,"partner");
        var second=service.listAdminOrders(2,1,"awaiting_inbound",null,"partner");
        assertThat(first.total()).isEqualTo(2);
        assertThat(second.total()).isEqualTo(2);
        assertThat(first.items()).extracting(CustomerPortalService.OrderListItem::id).containsExactly(103L);
        assertThat(second.items()).extracting(CustomerPortalService.OrderListItem::id).containsExactly(101L);
        assertThat(service.listAdminOrders(3,1,null,null,"partner").items()).isEmpty();
        assertThat(service.listAdminOrders(1,20,null,null).total()).isEqualTo(4);
        assertThat(service.listAdminOrders(1,20,null,"NXR-000103","partner").items())
            .extracting(CustomerPortalService.OrderListItem::id).containsExactly(103L);
        jdbc.update("INSERT INTO commerce_business_line VALUES(1,1)");
        jdbc.update("INSERT INTO commerce_work_center VALUES(5,1)");
        jdbc.update("UPDATE grading_order SET business_line_id=1,work_center_id=5 WHERE id=101");
        var scope=new OrderAccessScopeService.AccessScope(false,List.of(1L),List.of(5L));
        assertThat(service.listAdminOrders(1,1,null,null,scope,"partner").items())
            .extracting(CustomerPortalService.OrderListItem::id).containsExactly(101L);
        jdbc.update("UPDATE commerce_work_center SET is_active=0 WHERE id=5");
        assertThat(service.listAdminOrders(1,1,null,null,scope,"partner").total()).isZero();
    }

    @Test void changedOrMissingAcceptedTermsStayAtTermsConfirmationAndCancelledOrdersStayCancelled() {
        insertOrder(101,1,"payment_expired");link(101,10);
        jdbc.update("UPDATE grading_order SET admission_status_code='approved',approved_terms_version='v2',accepted_terms_version='v1',terms_accepted_at=CURRENT_TIMESTAMP WHERE id=101");
        assertThat(service.requireAdminOrder(101).statusCode()).isEqualTo("terms_confirmation");
        jdbc.update("UPDATE grading_order SET approved_terms_version=NULL WHERE id=101");
        assertThat(service.requireAdminOrder(101).statusCode()).isEqualTo("terms_confirmation");
        jdbc.update("UPDATE grading_order SET status_code='cancelled' WHERE id=101");
        jdbc.update("UPDATE merchant_order_batch SET status_code='cancelled' WHERE id=10");
        assertThat(service.requireAdminOrder(101).statusCode()).isEqualTo("cancelled");
        assertThat(service.requireAdminOrder(101).orderChannel()).isEqualTo("partner");
    }

    @Test void httpContractCarriesChannelAndRejectsInvalidFilters() throws Exception {
        insertOrder(101,1,"payment_expired");link(101,10);
        var mvc=MockMvcBuilders.standaloneSetup(new AdminOrderController(service,
            mock(OrderFulfillmentService.class),mock(OrderWorkbenchService.class))).build();
        mvc.perform(get("/api/admin/orders").param("orderChannel","partner").param("pageSize","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].orderChannel").value("partner"))
            .andExpect(jsonPath("$.data.items[0].statusCode").value("awaiting_inbound"));
        mvc.perform(get("/api/admin/orders/101"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.orderChannel").value("partner"));
        assertThatThrownBy(() -> service.listAdminOrders(1,20,null,null,"partner' OR 1=1"))
            .hasMessageContaining("400");
    }

    private void insertOrder(long id,long customerId,String status) {
        jdbc.update("INSERT INTO grading_order(id,order_no,customer_id,status_code,service_level_code,total_card_count,service_fee,return_shipping_fee,total_amount,currency_code,contact_name,contact_phone,return_address_line1,return_city,return_postal_code,return_country,created_at) VALUES(?,?,?,?,'basic',1,10,0,10,'USD','Test','123','One Road','City','100001','CN','2026-10-01 00:00:00')",
            id,"NXR-"+id,customerId,status);
    }
    private void link(long orderId,long batchId) {
        jdbc.update("INSERT INTO merchant_order_batch_item(batch_id,order_id,row_no,client_reference) VALUES(?,?,?,?)",
            batchId,orderId,(int)orderId,String.format("NXR-%06d",orderId));
    }
}
