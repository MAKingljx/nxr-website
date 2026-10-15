package com.nxr.platform.customer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.nxr.platform.commerce.OrderAccessScopeService;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static com.nxr.platform.customer.PartnerReturnService.*;

class PartnerReturnServiceTest {
    JdbcTemplate jdbc;PartnerReturnService service;OrderAccessScopeService access;OrderWorkbenchService workbench;TransactionTemplate tx;
    @BeforeEach void setup()throws Exception {
        JdbcDataSource ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:partner_return;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");jdbc=new JdbcTemplate(ds);
        try(Connection c=ds.getConnection()){for(String file:List.of("order_fulfillment_h2.sql","order_workbench_h2.sql","order_card_identity_h2.sql","merchant_batch_h2.sql","partner_return_h2.sql"))ScriptUtils.executeSqlScript(c,new ClassPathResource(file));}
        JdbcClient client=JdbcClient.create(jdbc);access=mock(OrderAccessScopeService.class);when(access.scopeForUser(1)).thenReturn(new OrderAccessScopeService.AccessScope(true,List.of(),List.of()));
        workbench=new OrderWorkbenchService(client);service=new PartnerReturnService(client,access,workbench);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
    }
    Card check(long id){String cert=jdbc.queryForObject("SELECT s.cert_id FROM grading_order_item i JOIN grading_submission s ON s.id=i.grading_submission_id WHERE i.id=?",String.class,id);return tx.execute(s->service.check(1,id,new CheckRequest(cert,true,true,"Checked actual card and package")));}
    ParcelDetail create(List<Long> ids,String key){return tx.execute(s->service.create(1,new CreateRequest(ids,"DHL","TRACK-"+key,key,"")));}
    @Test void sameOrderCanReturnTwiceAndClosesOnlyWhenAllCardsDelivered(){
        jdbc.update("UPDATE grading_submission SET status_code='pending' WHERE id=102");
        assertThat(check(1001).eligible()).isTrue();ParcelDetail first=create(List.of(1001L),"first");
        assertThat(jdbc.queryForObject("SELECT status_code FROM grading_order WHERE id=10",String.class)).isEqualTo("grading");
        tx.execute(s->service.delivered(1,first.id()));
        assertThat(service.progress(10).deliveredCardCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status_code FROM grading_order WHERE id=10",String.class)).isEqualTo("grading");
        jdbc.update("UPDATE grading_submission SET status_code='approved' WHERE id=102");check(1002);ParcelDetail second=create(List.of(1002L),"second");
        assertThat(jdbc.queryForObject("SELECT status_code FROM grading_order WHERE id=10",String.class)).isEqualTo("return_shipped");
        tx.execute(s->service.delivered(1,second.id()));tx.execute(s->service.delivered(1,second.id()));
        assertThat(service.progress(10).deliveredCardCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status_code FROM grading_order WHERE id=10",String.class)).isEqualTo("delivered");
        assertThat(jdbc.queryForObject("SELECT status_code FROM merchant_order_batch WHERE id=1",String.class)).isEqualTo("delivered");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_timeline_event WHERE event_code='partner_return_delivered'",Integer.class)).isEqualTo(2);
    }
    @Test void combinesDifferentSubmitsButPublicLinkOnlyExposesOwnCards(){
        check(1001);check(2001);ParcelDetail parcel=create(List.of(1001L,2001L),"combined");assertThat(parcel.cardCount()).isEqualTo(2);
        assertThat(service.publicParcels(10)).hasSize(1);assertThat(service.publicParcels(10).get(0).cards()).extracting(PublicCard::certId).containsExactly("CERT-101");
        assertThat(service.partnerParcels(1,1,20,null,null,null).total()).isEqualTo(1);
        assertThat(service.partnerParcels(2,1,20,null,null,null).total()).isZero();
        assertThatThrownBy(()->service.partnerDetail(2,parcel.id())).isInstanceOf(ResponseStatusException.class);
    }
    @Test void rejectsUnfinishedMissingPhotosAndUnverifiedCardsWithoutWrites(){
        assertThatThrownBy(()->create(List.of(1001L),"unchecked")).hasMessageContaining("quality and packing");
        jdbc.update("DELETE FROM submission_media WHERE submission_id=101 AND media_side_code='back'");assertThatThrownBy(()->check(1001)).hasMessageContaining("Front and back");
        jdbc.update("UPDATE grading_submission SET status_code='pending' WHERE id=102");assertThatThrownBy(()->check(1002)).hasMessageContaining("grading must be approved");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM partner_return_parcel",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM partner_return_card_check",Integer.class)).isZero();
    }
    @Test void changedScoreOrImageInvalidatesCheck(){
        check(1001);jdbc.update("UPDATE grading_score SET final_grade_value=7 WHERE submission_id=101");assertThatThrownBy(()->create(List.of(1001L),"changed")).hasMessageContaining("quality and packing");
        check(1001);jdbc.update("UPDATE submission_media SET checksum_sha256='changed' WHERE submission_id=101 AND media_side_code='front'");assertThatThrownBy(()->create(List.of(1001L),"changed-image")).hasMessageContaining("quality and packing");
    }
    @Test void retainsPaymentTermsShippingChangesAndLegacyShippingGates(){
        jdbc.update("UPDATE payment_record SET status_code='pending' WHERE order_id=10");assertThatThrownBy(()->check(1001)).hasMessageContaining("payment");jdbc.update("UPDATE payment_record SET status_code='confirmed' WHERE order_id=10");
        jdbc.update("UPDATE grading_order SET terms_accepted_at=NULL WHERE id=10");assertThatThrownBy(()->check(1001)).hasMessageContaining("terms");jdbc.update("UPDATE grading_order SET terms_accepted_at=CURRENT_TIMESTAMP WHERE id=10");
        jdbc.update("INSERT INTO merchant_batch_shipment(batch_id,direction_code,carrier_name,tracking_number,status_code,created_by_type) VALUES(1,'outbound','DHL','OLD','delivered','admin')");assertThatThrownBy(()->check(1001)).hasMessageContaining("existing batch return");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM partner_return_item",Integer.class)).isZero();
    }
    @Test void rejectsMismatchedAddressAndTenantAndMaintainsAtomicity(){
        check(1001);check(2001);jdbc.update("UPDATE grading_order SET return_city='Other City' WHERE id=20");assertThatThrownBy(()->create(List.of(1001L,2001L),"address")).hasMessageContaining("same partner and return address");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM partner_return_parcel",Integer.class)).isZero();
        jdbc.update("UPDATE grading_order SET return_city='Test',customer_id=2 WHERE id=20");jdbc.update("UPDATE agent_card SET merchant_customer_id=2 WHERE id=3");jdbc.update("UPDATE agent_intake SET merchant_customer_id=2 WHERE id=2");jdbc.update("UPDATE merchant_order_batch SET merchant_customer_id=2 WHERE id=2");
        assertThatThrownBy(()->create(List.of(1001L,2001L),"tenant")).hasMessageContaining("same partner and return address");
    }
    @Test void identicalRetryIsIdempotentAndSecondParcelCannotReuseCard(){
        check(1001);ParcelDetail parcel=create(List.of(1001L),"idempotent");assertThat(create(List.of(1001L),"idempotent").id()).isEqualTo(parcel.id());
        assertThatThrownBy(()->create(List.of(1001L),"duplicate")).hasMessageContaining("already");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM partner_return_item",Integer.class)).isEqualTo(1);
        assertThatThrownBy(()->service.assertLegacyReturnAllowed(10)).hasMessageContaining("card-based return");
    }
    @Test void restrictedStaffCannotListOrMutateOtherCenter(){
        when(access.scopeForUser(8)).thenReturn(new OrderAccessScopeService.AccessScope(false,List.of(2L),List.of(2L)));
        assertThat(service.cards(8,1,20,null,null,null,null).total()).isZero();
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND)).when(access).requireAccessibleOrder(8,10);
        assertThatThrownBy(()->tx.execute(s->service.check(8,1001,new CheckRequest("CERT-101",true,true,"")))).isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM partner_return_card_check",Integer.class)).isZero();
    }
    @Test void workbenchRequiredCardStillNeedsAllThreeCurrentScans(){
        jdbc.update("UPDATE grading_order SET workbench_required=1 WHERE id=10");assertThatThrownBy(()->check(1001)).hasMessageContaining("Allocate and scan");
        OrderWorkbenchService.WorkbenchSnapshot session=tx.execute(s->workbench.start(10,1));
        jdbc.update("UPDATE grading_submission SET status_code='pending' WHERE id=102");
        tx.execute(s->workbench.exportLabels(10,1,new OrderWorkbenchService.ExportRequest(null,List.of(1001L))));
        var card=workbench.snapshot(10).items().get(0);
        for(String stage:List.of("intake","label","packing"))tx.execute(s->workbench.scan(10,1,new OrderWorkbenchService.ScanRequest(session.activeSession().id(),stage,"intake".equals(stage)?card.barcode():card.labelBarcode())));
        assertThat(check(1001).eligible()).isTrue();assertThat(create(List.of(1001L),"partial-scanned").cardCount()).isEqualTo(1);
        assertThat(workbench.snapshot(10).items().get(1).labelBarcode()).isNull();
    }
    @Test void separateCardLabelExportsDoNotInvalidateOtherCardScans(){
        jdbc.update("UPDATE grading_order SET workbench_required=1 WHERE id=10");
        var session=tx.execute(t->workbench.start(10,1));
        tx.execute(t->workbench.exportLabels(10,1,new OrderWorkbenchService.ExportRequest(null,List.of(1001L))));
        var first=workbench.snapshot(10).items().get(0);
        for(String stage:List.of("intake","label","packing"))tx.execute(t->workbench.scan(10,1,new OrderWorkbenchService.ScanRequest(session.activeSession().id(),stage,"intake".equals(stage)?first.barcode():first.labelBarcode())));
        check(1001);
        tx.execute(t->workbench.exportLabels(10,1,new OrderWorkbenchService.ExportRequest("Printing next card",List.of(1002L))));
        assertThat(workbench.snapshot(10).items().get(0).labelBarcode()).isEqualTo(first.labelBarcode());
        workbench.assertCardPackingReady(10,1001);
        assertThat(create(List.of(1001L),"first-label-retained").cardCount()).isEqualTo(1);
    }
    @Test void shippingChangeAndOpenExceptionsContinueToBlockRelease(){
        jdbc.update("INSERT INTO shipping_change_request(order_id,old_option_code,old_option_name,old_price_amount,new_option_code,new_option_name,new_price_amount,currency_code,difference_amount,status_code,reason) VALUES(10,'old','Old',1,'new','New',2,'USD',1,'awaiting_settlement','change')");
        assertThatThrownBy(()->check(1001)).hasMessageContaining("shipping change");
        jdbc.update("UPDATE shipping_change_request SET status_code='settled' WHERE order_id=10");
        jdbc.update("INSERT INTO order_exception(order_id,exception_type_code,status_code,title,detail,visible_to_customer) VALUES(10,'damaged','open','Damage','Inspect',1)");
        assertThatThrownBy(()->check(1001)).hasMessageContaining("open order exception");
    }
    @Test void unsettledShippingFeesRejectAtomicallyAndSettlementRestoresRelease(){
        // Fixture-only state models the independent shipping settlement workflow.
        jdbc.update("INSERT INTO shipping_change_request(order_id,old_option_code,old_option_name,old_price_amount,new_option_code,new_option_name,new_price_amount,currency_code,difference_amount,status_code,reason) VALUES(10,'old','Old',1,'new','New',2,'USD',1,'awaiting_settlement','Synthetic fee adjustment')");
        assertThatThrownBy(()->create(List.of(1001L),"unsettled-fees")).hasMessageContaining("shipping change");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM partner_return_parcel",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM partner_return_item",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM partner_return_card_check",Integer.class)).isZero();
        jdbc.update("UPDATE shipping_change_request SET status_code='settled' WHERE order_id=10");
        assertThat(check(1001).eligible()).isTrue();
        assertThat(create(List.of(1001L),"settled-fees").cardCount()).isEqualTo(1);
    }
    @Test void cardSearchIncludesShortOrderBatchAndCompanyWhileKeepingScopeAndPagination(){
        var first=service.cards(1,1,1,null,null,null,"nxr-1");
        assertThat(first.total()).isEqualTo(2);
        assertThat(first.items()).hasSize(1).extracting(Card::orderReference).containsExactly("NXR-1");
        assertThat(service.cards(1,2,1,null,null,null,"NXR-1").items()).hasSize(1);
        assertThat(service.cards(1,3,1,null,null,null,"NXR-1").items()).isEmpty();
        assertThat(service.cards(1,1,20,null,null,null,"batch-b").items()).extracting(Card::orderItemId).containsExactly(2001L);
        assertThat(service.cards(1,1,20,null,null,null,"merchant").total()).isEqualTo(3);
        assertThat(service.cards(1,1,20,2L,null,null,"NXR-1").total()).isZero();
        assertThat(service.cards(1,1,20,null,null,null,"NXR-%").total()).isZero();
        when(access.scopeForUser(8)).thenReturn(new OrderAccessScopeService.AccessScope(false,List.of(2L),List.of(2L)));
        assertThat(service.cards(8,1,20,null,null,null,"NXR-1").total()).isZero();
    }
    @Test void parcelSearchIncludesTrackingCarrierAndAssociatedOrderWithoutLeakingScopes(){
        check(1001);check(2001);
        ParcelDetail first=create(List.of(1001L),"search-a"),second=create(List.of(2001L),"search-b");
        for(String query:List.of(first.parcelNo().toLowerCase(java.util.Locale.ROOT),"track-search-a","NXR-WB-10","nxr-1")) {
            var result=service.adminParcels(1,1,20,null,null,null,null,query);
            assertThat(result.total()).isEqualTo(1);assertThat(result.items()).extracting(Parcel::id).containsExactly(first.id());
        }
        var page=service.adminParcels(1,1,1,null,null,null,"shipped","dhl");
        assertThat(page.total()).isEqualTo(2);assertThat(page.items()).extracting(Parcel::id).containsExactly(second.id());
        assertThat(service.adminParcels(1,2,1,null,null,null,"shipped","dhl").items()).extracting(Parcel::id).containsExactly(first.id());
        assertThat(service.adminParcels(1,1,20,2L,null,null,null,"search-a").total()).isZero();
        assertThat(service.adminParcels(1,1,20,null,20L,null,null,"search-a").total()).isZero();
        assertThat(service.adminParcels(1,1,20,null,null,null,"delivered","search-a").total()).isZero();
        assertThat(service.adminParcels(1,1,20,null,null,null,null,"TRACK-%").total()).isZero();
        when(access.scopeForUser(8)).thenReturn(new OrderAccessScopeService.AccessScope(false,List.of(2L),List.of(2L)));
        assertThat(service.adminParcels(8,1,20,null,null,null,null,"search-a").total()).isZero();
        assertThat(service.partnerParcels(2,1,20,null,null,null).total()).isZero();
    }
    @Test void shortReferenceIntakeLookupPreservesOriginalCodesAndRejectsAmbiguousOrWrongOwnerLinks(){
        var fulfillment=new OrderFulfillmentService(JdbcClient.create(jdbc),jdbc);
        jdbc.update("UPDATE grading_order SET intake_code='INTAKE-10' WHERE id=10");
        assertThat(fulfillment.lookupIntake("nxr-1").orderId()).isEqualTo(10);
        assertThat(fulfillment.lookupIntake("NXR-1").intakeCode()).isEqualTo("INTAKE-10");
        assertThat(fulfillment.lookupIntake("intake-10").orderId()).isEqualTo(10);
        jdbc.update("UPDATE agent_intake SET merchant_customer_id=2 WHERE id=1");
        assertThatThrownBy(()->fulfillment.lookupIntake("NXR-1")).hasMessageContaining("not found");
        jdbc.update("UPDATE agent_intake SET merchant_customer_id=1 WHERE id=1");
        jdbc.update("UPDATE agent_intake SET intake_no='NXR-1' WHERE id=2");
        assertThatThrownBy(()->fulfillment.lookupIntake("NXR-1")).hasMessageContaining("ambiguous");
        assertThat(fulfillment.lookupIntake("INTAKE-10").orderId()).isEqualTo(10);
    }
    @Test void shortReferenceLookupStillChecksStaffOrderScope(){
        var fulfillment=new OrderFulfillmentService(JdbcClient.create(jdbc),jdbc);
        var controller=new com.nxr.platform.admin.AdminOrderController(null,fulfillment,null,access);
        try(var security=mockStatic(com.ruoyi.common.utils.SecurityUtils.class)) {
            security.when(com.ruoyi.common.utils.SecurityUtils::getUserId).thenReturn(8L);
            doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"Order not found")).when(access).requireAccessibleOrder(8,10);
            assertThatThrownBy(()->controller.lookupIntake("NXR-1")).hasMessageContaining("Order not found");
            verify(access).requireAccessibleOrder(8,10);
        }
    }
    @Test void adminDetailIncludesSameOwnerBatchClientReference(){
        var portal=new CustomerPortalService(JdbcClient.create(jdbc),jdbc);
        jdbc.update("UPDATE merchant_order_batch_item SET client_reference='NXR-1' WHERE order_id=10");
        assertThat(portal.requireAdminOrder(10).merchantBatch().clientReference()).isEqualTo("NXR-1");
        jdbc.update("UPDATE merchant_order_batch SET merchant_customer_id=2 WHERE id=1");
        assertThat(portal.requireAdminOrder(10).merchantBatch()).isNull();
    }
    @Test void concurrentDoubleShippingCommitsOnlyOneParcel()throws Exception {
        check(1001);ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try{var jobs=List.of(pool.submit(()->{start.await();try{create(List.of(1001L),"race-a");return true;}catch(ResponseStatusException e){return false;}}),pool.submit(()->{start.await();try{create(List.of(1001L),"race-b");return true;}catch(ResponseStatusException e){return false;}}));start.countDown();int passed=0;for(var job:jobs)if(job.get(10,TimeUnit.SECONDS))passed++;assertThat(passed).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM partner_return_parcel",Integer.class)).isEqualTo(1);}finally{pool.shutdownNow();}
    }
}
