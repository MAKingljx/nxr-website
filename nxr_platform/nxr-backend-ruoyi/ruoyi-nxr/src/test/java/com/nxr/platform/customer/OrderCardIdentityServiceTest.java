package com.nxr.platform.customer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.nxr.platform.commerce.OrderAccessScopeService;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class OrderCardIdentityServiceTest {
    private JdbcTemplate jdbc;
    private OrderWorkbenchService workbench;
    private OrderCardIdentityService identities;
    private OrderAccessScopeService scope;
    private TransactionTemplate tx;
    @BeforeEach void setup() throws Exception {
        JdbcDataSource ds=new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:identity_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc=new JdbcTemplate(ds);
        try(Connection c=ds.getConnection()) {
            for(String script:List.of("order_fulfillment_h2.sql","order_workbench_h2.sql","order_card_identity_h2.sql"))
                ScriptUtils.executeSqlScript(c,new ClassPathResource(script));
        }
        jdbc.update("INSERT INTO customer_account(id,email,password_hash,display_name) VALUES(1,'one@example.invalid','unused','Same name'),(2,'two@example.invalid','unused','Same name')");
        jdbc.update("INSERT INTO sys_user VALUES(1,'0','0'),(2,'0','0'),(3,'0','0')");
        JdbcClient client=JdbcClient.create(jdbc);
        workbench=new OrderWorkbenchService(client);
        scope=mock(OrderAccessScopeService.class);
        identities=new OrderCardIdentityService(client,workbench,scope);
        tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
    }
    @Test void sameNamedDirectCardsHaveDifferentStableCodesBeforeShipmentWithoutOpeningWorkbench() {
        jdbc.update("UPDATE grading_order SET status_code='pending_payment' WHERE id=10");
        jdbc.update("UPDATE grading_order_item SET card_name='Same card',grading_submission_id=NULL WHERE order_id=10");
        assertThat(identities.customer(1,"NXR-WB-10").items()).allSatisfy(c->assertThat(c.receiptCode()).isNull());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_physical_item",Integer.class)).isZero();
        var first=tx.execute(s->identities.allocateForCustomer(1,"NXR-WB-10"));
        var second=tx.execute(s->identities.allocateForCustomer(1,"NXR-WB-10"));
        assertThat(first).isEqualTo(second);
        assertThat(first.items()).extracting(OrderCardIdentityService.CardIdentity::receiptCode).doesNotHaveDuplicates().allSatisfy(c->assertThat(c).startsWith("NXR-I-"));
        assertThat(first.sourceType()).isEqualTo("direct");
        assertThat(first.returnRoute()).isEqualTo("direct_to_customer");
        assertThat(first.items()).allSatisfy(c->{assertThat(c.ownerKey()).isEqualTo("customer:1");assertThat(c.ownerDisplayName()).isEqualTo("Same name");});
        assertThat(jdbc.queryForObject("SELECT status_code FROM grading_order WHERE id=10",String.class)).isEqualTo("pending_payment");
        for(String table:List.of("order_workbench_session","order_workbench_scan","order_print_job"))
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).isZero();
    }
    @Test void sameDisplayNameDoesNotGrantOwnershipAndCannotAllocateOtherCustomersCards() {
        assertThatThrownBy(()->identities.customer(2,"NXR-WB-10")).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        assertThatThrownBy(()->tx.execute(s->identities.allocateForCustomer(2,"NXR-WB-10"))).hasMessageContaining("404");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_physical_item",Integer.class)).isZero();
    }
    private void partner() {
        jdbc.update("UPDATE customer_account SET account_type_code='merchant',display_name='Partner billing account' WHERE id=1");
        jdbc.update("INSERT INTO merchant_company_profile VALUES(1,'Sub-partner A')");
        jdbc.update("INSERT INTO agent_client VALUES(101,1,'CUSTOMER-A','Original owner')");
        jdbc.update("INSERT INTO agent_intake VALUES(111,1,101,10)");
        jdbc.update("INSERT INTO agent_card VALUES(121,1,111,'AC-CARD-ONE',1001),(122,1,111,'AC-CARD-TWO',1002)");
        jdbc.update("INSERT INTO merchant_order_batch(id,batch_no,merchant_customer_id,batch_name,status_code,total_rows) VALUES(131,'BATCH-131',1,'Batch','created',1)");
        jdbc.update("INSERT INTO merchant_order_batch_item(batch_id,order_id,row_no,client_reference,client_display_name) VALUES(131,10,1,'INTAKE-111','Old snapshot owner')");
    }
    @Test void partnerInventoryCodesFollowOriginalOwnerThroughNxrAndBothNamesRemainDistinct() {
        partner();
        var read=identities.customer(1,"NXR-WB-10");
        assertThat(read.items()).extracting(OrderCardIdentityService.CardIdentity::receiptCode).containsExactly("AC-CARD-ONE","AC-CARD-TWO");
        assertThat(read.items()).allSatisfy(c->assertThat(c.physicalBarcode()).isNull());
        var allocated=tx.execute(s->identities.allocateForCustomer(1,"NXR-WB-10"));
        assertThat(allocated.sourceType()).isEqualTo("partner");
        assertThat(allocated.ownerDisplayName()).isEqualTo("Original owner");
        assertThat(allocated.partnerCompanyName()).isEqualTo("Sub-partner A");
        assertThat(allocated.returnRoute()).isEqualTo("via_partner");
        assertThat(allocated.batchNo()).isEqualTo("BATCH-131");
        assertThat(allocated.items()).allSatisfy(c->{
            assertThat(c.receiptCode()).isEqualTo(c.physicalBarcode());
            assertThat(c.ownerKey()).isEqualTo("partner:1:client:101");
            assertThat(c.clientReference()).isEqualTo("CUSTOMER-A");
        });
        assertThat(identities.lookup(1," ac-card-one ").certId()).isEqualTo("CERT-101");
    }
    @Test void existingPhysicalBarcodeStaysUnchangedAndPartnerAliasOnlyWorksAtIntake() {
        workbench.allocatePhysicalItems(10);
        String old=identities.customer(1,"NXR-WB-10").items().get(0).physicalBarcode();
        partner();
        var ids=tx.execute(s->identities.allocateForCustomer(1,"NXR-WB-10"));
        assertThat(ids.items().get(0).physicalBarcode()).isEqualTo(old);
        assertThat(ids.items().get(0).receiptCode()).isEqualTo("AC-CARD-ONE");
        assertThat(identities.lookup(1,old)).isEqualTo(identities.lookup(1,"AC-CARD-ONE"));
        var session=workbench.start(10,1).activeSession();
        workbench.scan(10,1,new OrderWorkbenchService.ScanRequest(session.id(),"intake","AC-CARD-ONE"));
        assertThatThrownBy(()->workbench.scan(10,1,new OrderWorkbenchService.ScanRequest(session.id(),"intake",old))).hasMessageContaining("already scanned");
        assertThatThrownBy(()->workbench.scan(10,1,new OrderWorkbenchService.ScanRequest(session.id(),"label","AC-CARD-ONE"))).hasMessageContaining("exported label barcode");
        workbench.scan(10,1,new OrderWorkbenchService.ScanRequest(session.id(),"intake","AC-CARD-TWO"));
        workbench.exportLabels(10,1,null);
        String label=workbench.snapshot(10).items().get(0).labelBarcode();
        assertThat(label).startsWith(old+"-L1-");
        workbench.scan(10,1,new OrderWorkbenchService.ScanRequest(session.id(),"label",label));
        jdbc.update("UPDATE grading_score SET final_grade_value=10 WHERE submission_id=101");
        assertThatThrownBy(()->workbench.scan(10,1,new OrderWorkbenchService.ScanRequest(session.id(),"packing",label))).hasMessageContaining("changed after label export");
    }
    @Test void partnerAliasRejectsAnotherOrderAndAmbiguousCollisions() {
        partner(); workbench.allocatePhysicalItems(10);
        var other=workbench.start(20,1);
        assertThatThrownBy(()->workbench.scan(20,1,new OrderWorkbenchService.ScanRequest(other.activeSession().id(),"intake","AC-CARD-ONE"))).hasMessageContaining("another order");
        // A collision with a pre-existing legacy barcode fails closed, never guesses a customer by the text label.
        jdbc.update("UPDATE agent_card SET inventory_code=? WHERE id=121",other.items().get(0).barcode());
        assertThatThrownBy(()->identities.lookup(1,other.items().get(0).barcode())).hasMessageContaining("ambiguous");
        assertThatThrownBy(()->workbench.scan(20,1,new OrderWorkbenchService.ScanRequest(other.activeSession().id(),"intake",other.items().get(0).barcode()))).hasMessageContaining("ambiguous");
    }
    @Test void globalLookupRejectsBoundPartnerEvenWithStalePermissionsAndAppliesStaffScope() {
        partner(); workbench.allocatePhysicalItems(10);
        jdbc.update("INSERT INTO agent_operator_binding VALUES(2,1,TRUE)");
        assertThatThrownBy(()->identities.lookup(2,"AC-CARD-ONE")).hasMessageContaining("403");
        assertThatThrownBy(()->identities.admin(2,10)).hasMessageContaining("403");
        assertThatThrownBy(()->identities.allocateForAdmin(2,10)).hasMessageContaining("403");
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found")).when(scope).requireAccessibleOrder(3,10);
        assertThatThrownBy(()->identities.lookup(3,"AC-CARD-ONE")).hasMessageContaining("404");
        assertThatThrownBy(()->identities.lookup(1,"Original owner")).hasMessageContaining("400");
        assertThatThrownBy(()->identities.lookup(1,"CERT-101")).hasMessageContaining("404");
        assertThatThrownBy(()->identities.lookup(1,"https://nxrgrading.com/card/CERT-101")).hasMessageContaining("400");
        verify(scope).requireAccessibleOrder(3,10);
    }
    @Test void legacyMerchantBatchKeepsItsClientReferenceWithoutInventingAgentClient() {
        jdbc.update("INSERT INTO merchant_order_batch(id,batch_no,merchant_customer_id,batch_name,status_code,total_rows) VALUES(131,'BATCH-131',1,'Batch','created',1)");
        jdbc.update("INSERT INTO merchant_order_batch_item(batch_id,order_id,row_no,client_reference,client_display_name) VALUES(131,10,1,'CUSTOMER-X','Original batch owner')");
        var order=identities.customer(1,"NXR-WB-10");
        assertThat(order.sourceType()).isEqualTo("partner");
        assertThat(order.ownerDisplayName()).isEqualTo("Original batch owner");
        assertThat(order.items().get(0).ownerKey()).isEqualTo("partner:1:reference:CUSTOMER-X");
    }
}
