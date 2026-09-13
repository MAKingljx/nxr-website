package com.nxr.platform.customer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.nxr.platform.customer.AgentWorkbenchService.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nxr.platform.admission.OrderAdmissionService;
import com.nxr.platform.admin.storage.MediaCapacityService;
import com.nxr.platform.commerce.CommercePolicyService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class AgentWorkbenchServiceTest {
    @Test void returnQrNormalizationNeverFollowsForeignHostsOrEncodedPaths() {
        assertThat(AgentWorkbenchService.normalizeReturnScan("nxrgrading.com/card/00123")).isEqualTo("00123");
        assertThat(AgentWorkbenchService.normalizeReturnScan("https://www.nxrgrading.com/card/VRA001/")).isEqualTo("VRA001");
        for(String value:List.of("https://evil.example/card/00123", "https://nxrgrading.com.evil.example/card/00123",
            "https://nxrgrading.com/card/%30%30%31", "https://user@nxrgrading.com/card/00123", "https://nxrgrading.com/card/../00123")) {
            assertThatThrownBy(()->AgentWorkbenchService.normalizeReturnScan(value)).hasMessageContaining("400");
        }
    }
    @TempDir Path directory;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private AgentWorkbenchService service;
    private CustomerPortalService portal;
    private MerchantBatchService batches;
    private CustomerOrderPhotoService photos;
    private OrderAdmissionService admission;
    private final AtomicInteger sequence=new AtomicInteger(100);

    @BeforeEach void setup() throws Exception {
        JdbcDataSource ds=new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:agent_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        jdbc=new JdbcTemplate(ds);
        try(Connection c=ds.getConnection()) {
            ScriptUtils.executeSqlScript(c,new ClassPathResource("order_fulfillment_h2.sql"));
            ScriptUtils.executeSqlScript(c,new ClassPathResource("merchant_batch_h2.sql"));
        }
        jdbc.execute("CREATE TABLE customer_order_photo(id BIGINT AUTO_INCREMENT PRIMARY KEY, customer_id BIGINT NOT NULL, order_id BIGINT, storage_key VARCHAR(64), original_filename VARCHAR(255), mime_type VARCHAR(64), byte_size BIGINT, width_px INT, height_px INT, checksum_sha256 VARCHAR(64), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, attached_at TIMESTAMP)");
        jdbc.execute("ALTER TABLE grading_order_item ADD front_photo_id BIGINT");
        jdbc.execute("ALTER TABLE grading_order_item ADD back_photo_id BIGINT");
        try(Connection c=ds.getConnection()) { ScriptUtils.executeSqlScript(c,new ClassPathResource("agent_workbench_h2.sql")); }
        jdbc.update("INSERT INTO customer_account(id,email,password_hash,display_name,account_type_code,is_active) VALUES(3,'normal@example.test','x','Normal','customer',1)");
        jdbc.update("INSERT INTO customer_address(id,customer_id,label,contact_name,contact_phone,address_line1,city,postal_code,country,is_default) VALUES(10,1,'Return','Agent','123','Agent road','City','1','US',1),(20,2,'Return','Other agent','321','Other road','City','1','US',1)");
        var manager=new DataSourceTransactionManager(ds); tx=new TransactionTemplate(manager); tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        JdbcClient client=JdbcClient.create(jdbc);
        var fulfillment=new OrderFulfillmentService(client,jdbc);
        portal=mock(CustomerPortalService.class);
        var commerce=mock(CommercePolicyService.class);
        when(commerce.quoteBatch(anyLong(),any(),any(),any(),any())).thenAnswer(call -> {
            List<CommercePolicyService.BatchPartRequest> parts=call.getArgument(3);
            int cards=parts.stream().mapToInt(CommercePolicyService.BatchPartRequest::cardCount).sum();
            var aggregate=new CommercePolicyService.QuoteResult(1,"business","US","USD",cards,1L,2L,"policy","policy",
                BigDecimal.TEN,BigDecimal.TEN.multiply(BigDecimal.valueOf(cards)),BigDecimal.ZERO,BigDecimal.TEN.multiply(BigDecimal.valueOf(cards)),
                50,100,200,500,"agent_return","Agent return",null);
            return new CommercePolicyService.BatchQuoteResult(aggregate,parts.stream().map(p->new CommercePolicyService.AllocatedBatchQuote(p.reference(),p.cardCount(),
                BigDecimal.TEN.multiply(BigDecimal.valueOf(p.cardCount())),BigDecimal.ZERO,BigDecimal.TEN.multiply(BigDecimal.valueOf(p.cardCount())))).toList());
        });
        doAnswer(call -> insertOrder(call.getArgument(0),call.getArgument(1),false)).when(portal).createOrderWithBatchQuote(anyLong(),any(),any(),any());
        batches=new MerchantBatchService(client,portal,fulfillment,commerce,manager);
        photos=new CustomerOrderPhotoService(client,jdbc,new MediaCapacityService(0),directory.toString());
        admission=mock(OrderAdmissionService.class); when(admission.maxCardsPerOrder()).thenReturn(200);
        service=new AgentWorkbenchService(client,fulfillment,batches,photos,new ObjectMapper().findAndRegisterModules(),admission);
    }

    @Test void tenantAndMerchantChecksApplyToEveryEntryAndClientArchivesRetainHistory() {
        Client client=createClient(1,"A"); IntakeDetail intake=createIntake(1,client.id(),1,"intake-a");
        assertThat(service.clients(2,1,20,null,null).items()).isEmpty();
        assertThat(service.intakes(2,1,20,client.id(),null,null).items()).isEmpty();
        assertThat(service.cards(2,1,20,client.id(),null,null,null).items()).isEmpty();
        assertThatThrownBy(()->service.clientDetail(2,client.id())).hasMessageContaining("404");
        assertThatThrownBy(()->tx.execute(s->service.receive(2,intake.intake().id(),null))).hasMessageContaining("404");
        assertThatThrownBy(()->service.clients(3,1,20,null,null)).hasMessageContaining("403");
        assertThatThrownBy(()->tx.execute(s->service.createClient(3,clientRequest("C","key")))).hasMessageContaining("403");
        assertThatThrownBy(()->tx.execute(s->service.returnCheck(3,intake.cards().get(0).id(),new ReturnCheckRequest("x",null,null)))).hasMessageContaining("403");
        var archived=new ClientRequest("A","Client A","555","a@example.test","Recipient","One Road",null,"City",null,"123","US","Historical",false,null);
        tx.execute(s->service.updateClient(1,client.id(),archived));
        assertThat(service.clientDetail(1,client.id()).intakes()).hasSize(1);
        assertThat(service.clientDetail(1,client.id()).events()).extracting(Event::eventCode).contains("client_created","intake_created","client_updated");
        assertThatThrownBy(()->createIntake(1,client.id(),1,"archived")).hasMessageContaining("archived");
    }

    @Test void clientAndIntakeCreateKeysReplayAndConflictingPayloadsAreRejected() {
        var r=clientRequest("A","client-a");
        Client a=tx.execute(s->service.createClient(1,r));
        assertThat(tx.execute(s->service.createClient(1,r)).id()).isEqualTo(a.id());
        assertThatThrownBy(()->tx.execute(s->service.createClient(1,clientRequest("B","client-a")))).hasMessageContaining("409");
        IntakeDetail i=createIntake(1,a.id(),1,"intake-a");
        assertThat(createIntake(1,a.id(),1,"intake-a").intake().id()).isEqualTo(i.intake().id());
        assertThatThrownBy(()->createIntake(1,a.id(),2,"intake-a")).hasMessageContaining("409");
        assertThat(count("agent_intake")).isEqualTo(1); assertThat(count("agent_card")).isEqualTo(1);
        when(admission.maxCardsPerOrder()).thenReturn(1);
        assertThatThrownBy(()->createIntake(1,a.id(),2,"over-limit")).hasMessageContaining("at most 1");
    }

    @Test void intakeMustBeReceivedFullyCountedAndFreeOfExceptionsBeforeSubmit() {
        Client c=createClient(1,"A"); IntakeDetail i=createIntake(1,c.id(),2,"i"); Card first=i.cards().get(0),second=i.cards().get(1);
        assertThatThrownBy(()->check(i,first,false)).hasMessageContaining("Receive the parcel");
        receive(i); check(i,first,false);
        assertThatThrownBy(()->submit(i,"s")).hasMessageContaining("fully received");
        check(i,second,true);
        assertThat(service.intakeDetail(1,i.intake().id()).intake().statusCode()).isEqualTo("exception");
        assertThatThrownBy(()->submit(i,"s")).hasMessageContaining("free of exceptions");
        check(i,second,false);
        assertThat(service.intakeDetail(1,i.intake().id()).intake().statusCode()).isEqualTo("ready");
        assertThat(submit(i,"s").batchNo()).isNotBlank();
        assertThatThrownBy(()->check(i,first,false)).hasMessageContaining("cannot be changed");
        assertThat(count("grading_order")).isEqualTo(1);
    }

    @Test void sameClientMultipleIntakesCreateDistinctChildOrdersAndConcurrentReplayCannotDuplicateThem() throws Exception {
        Client c=createClient(1,"A"); IntakeDetail a=ready(c.id(),2,"a"),b=ready(c.id(),1,"b");
        var request=new SubmissionRequest(List.of(a.intake().id(),b.intake().id()),10L,"agent_return","USD","September","same-request");
        Callable<Submission> work=()->tx.execute(s->service.submit(1,request));
        var pool=Executors.newFixedThreadPool(2);
        try {
            var f1=pool.submit(work); var f2=pool.submit(work);
            assertThat(f1.get(15,TimeUnit.SECONDS).batchId()).isEqualTo(f2.get(15,TimeUnit.SECONDS).batchId());
        } finally { pool.shutdownNow(); }
        assertThat(count("grading_order")).isEqualTo(2); assertThat(count("agent_submission")).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT client_reference FROM merchant_order_batch_item ORDER BY row_no",String.class))
            .containsExactly(a.intake().intakeNo(),b.intake().intakeNo());
        assertThat(jdbc.queryForList("SELECT order_item_id FROM agent_card",Long.class)).doesNotContainNull().doesNotHaveDuplicates();
        assertThatThrownBy(()->tx.execute(s->service.submit(1,new SubmissionRequest(List.of(a.intake().id()),10L,"agent_return","USD","Other","same-request"))))
            .hasMessageContaining("409");
        assertThatThrownBy(()->tx.execute(s->service.submit(2,request))).hasMessageContaining("404");
    }

    @Test void failureAfterBatchCreationRollsBackOrdersTokensInventoryLinksAndAllowsSafeRetry() {
        Client c=createClient(1,"A"); IntakeDetail i=ready(c.id(),2,"a");
        doAnswer(call -> insertOrder(call.getArgument(0),call.getArgument(1),true)).when(portal).createOrderWithBatchQuote(anyLong(),any(),any(),any());
        assertThatThrownBy(()->submit(i,"atomic")).hasMessageContaining("card count does not match");
        for(String table:List.of("grading_order","grading_order_item","merchant_order_batch","merchant_order_batch_item","merchant_batch_tracking_token","agent_submission"))
            assertThat(count(table)).as(table).isZero();
        assertThat(service.intakeDetail(1,i.intake().id()).intake().statusCode()).isEqualTo("ready");
        assertThat(service.intakeDetail(1,i.intake().id()).cards()).allMatch(ca->ca.orderItemId()==null);
        doAnswer(call -> insertOrder(call.getArgument(0),call.getArgument(1),false)).when(portal).createOrderWithBatchQuote(anyLong(),any(),any(),any());
        assertThat(submit(i,"atomic").batchId()).isPositive();
        assertThatThrownBy(()->batches.createBatchInCurrentTransaction(1,null)).hasMessageContaining("active transaction");
    }

    @Test void changedPresentedQuoteRollsBackTheEntireBatchBeforeInventoryIsSubmitted() {
        Client c=createClient(1,"A"); IntakeDetail i=ready(c.id(),1,"a");
        var stale=new SubmissionRequest(List.of(i.intake().id()),10L,"agent_return","USD","Batch","quote-key",new BigDecimal("9.99"),"USD");
        assertThatThrownBy(()->tx.execute(s->service.submit(1,stale))).hasMessageContaining("quote has changed");
        for(String table:List.of("grading_order","grading_order_item","merchant_order_batch","merchant_order_batch_item","merchant_batch_tracking_token","agent_submission"))
            assertThat(count(table)).as(table).isZero();
        assertThat(service.intakeDetail(1,i.intake().id()).intake().statusCode()).isEqualTo("ready");
        var wrongCurrency=new SubmissionRequest(List.of(i.intake().id()),10L,"agent_return","USD","Batch","quote-key",new BigDecimal("10.00"),"EUR");
        assertThatThrownBy(()->tx.execute(s->service.submit(1,wrongCurrency))).hasMessageContaining("quote has changed");
        var current=new SubmissionRequest(List.of(i.intake().id()),10L,"agent_return","USD","Batch","quote-key",new BigDecimal("10.00"),"USD");
        assertThat(tx.execute(s->service.submit(1,current)).batchId()).isPositive();
        assertThat(count("grading_order")).isEqualTo(1);
    }

    @Test void returnChecksRequireDeliveredMasterParcelAndExactCardOrItsCertificateIdentity() {
        Client c=createClient(1,"A"); IntakeDetail i=ready(c.id(),2,"a"); Submission sub=submit(i,"s");
        Card first=service.intakeDetail(1,i.intake().id()).cards().get(0),second=service.intakeDetail(1,i.intake().id()).cards().get(1);
        assertThatThrownBy(()->returnCard(first,first.inventoryCode())).hasMessageContaining("must be delivered");
        markNxrDelivered(sub);
        jdbc.update("INSERT INTO grading_submission(id,cert_id) VALUES(501,'NXR-CERT-501'),(502,'NXR-CERT-502')");
        jdbc.update("UPDATE grading_order_item SET grading_submission_id=501 WHERE id=?",first.orderItemId());
        jdbc.update("UPDATE grading_order_item SET grading_submission_id=502 WHERE id=?",second.orderItemId());
        assertThat(service.cards(1,1,20,null,null,null,"nxr-cert-501").items()).extracting(Card::id).containsExactly(first.id());
        assertThat(service.cards(2,1,20,null,null,null,"nxr-cert-501").items()).isEmpty();
        assertThatThrownBy(()->returnCard(first,"NXR-CERT-502")).hasMessageContaining("does not match");
        assertThat(returnCard(first,"nxr-cert-501").statusCode()).isEqualTo("returned");
        assertThat(returnCard(first,first.inventoryCode()).statusCode()).isEqualTo("returned");
        assertThat(returnCard(second,second.inventoryCode()).statusCode()).isEqualTo("returned");
        assertThat(service.events(1,c.id(),null,null,1,100).items().stream().filter(e->e.eventCode().equals("card_return_checked"))).hasSize(2);
    }

    @Test void finalMileRejectsUnreturnedCrossClientCrossTenantAndDuplicateCardsAndKeepsAddressSnapshot() {
        Client a=createClient(1,"A"),b=createClient(1,"B"),other=createClient(2,"C");
        IntakeDetail ia=ready(a.id(),2,"a"),ib=ready(b.id(),1,"b"),ic=createIntake(2,other.id(),1,"c");
        Submission sa=submit(ia,"sa"),sb=submit(ib,"sb");
        List<Card> ca=service.intakeDetail(1,ia.intake().id()).cards(); Card cb=service.intakeDetail(1,ib.intake().id()).cards().get(0);
        assertThatThrownBy(()->ship(a.id(),List.of(ca.get(0).id()),"early")).hasMessageContaining("checked returned");
        markNxrDelivered(sa); markNxrDelivered(sb);
        for(Card card:ca) returnCard(card,card.inventoryCode()); returnCard(cb,cb.inventoryCode());
        assertThatThrownBy(()->ship(a.id(),List.of(ca.get(0).id(),cb.id()),"mixed")).hasMessageContaining("same client");
        assertThatThrownBy(()->ship(a.id(),List.of(ca.get(0).id(),ic.cards().get(0).id()),"other")).hasMessageContaining("404");
        assertThatThrownBy(()->ship(a.id(),List.of(ca.get(0).id(),ca.get(0).id()),"duplicates")).hasMessageContaining("unique cards");
        ShipmentDetail shipment=ship(a.id(),ca.stream().map(Card::id).toList(),"ship-a");
        assertThat(ship(a.id(),ca.stream().map(Card::id).toList(),"ship-a").shipment().id()).isEqualTo(shipment.shipment().id());
        assertThatThrownBy(()->ship(a.id(),List.of(ca.get(0).id()),"second-parcel")).hasMessageContaining("have not been shipped");
        assertThatThrownBy(()->service.shipmentDetail(2,shipment.shipment().id())).hasMessageContaining("404");
        jdbc.update("UPDATE agent_client SET address_line1='Changed road' WHERE id=?",a.id());
        assertThat(service.shipmentDetail(1,shipment.shipment().id()).shipment().address().addressLine1()).isEqualTo("One Road");
        tx.execute(s->service.delivered(1,shipment.shipment().id(),new NoteRequest("Client confirmed",null)));
        assertThat(service.shipmentDetail(1,shipment.shipment().id()).cards()).allMatch(card->card.statusCode().equals("delivered"));
        assertThat(jdbc.queryForList("SELECT status_code FROM grading_order",String.class)).containsOnly("delivered");
        assertThat(jdbc.queryForList("SELECT status_code FROM merchant_order_batch",String.class)).containsOnly("delivered");
    }

    @Test void simultaneousReturnParcelsCannotShipTheSameInventoryTwice() throws Exception {
        Client c=createClient(1,"A"); IntakeDetail i=ready(c.id(),1,"a"); Submission s=submit(i,"s"); markNxrDelivered(s);
        Card card=service.intakeDetail(1,i.intake().id()).cards().get(0); returnCard(card,card.inventoryCode());
        var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> one=()->{try {ship(c.id(),List.of(card.id()),UUID.randomUUID().toString());return true;} catch(ResponseStatusException expected) {assertThat(expected.getStatusCode().value()).isEqualTo(409);return false;}};
            var f1=pool.submit(one); var f2=pool.submit(one);
            assertThat(List.of(f1.get(15,TimeUnit.SECONDS),f2.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        } finally { pool.shutdownNow(); }
        assertThat(count("agent_return_shipment")).isEqualTo(1);
    }

    @Test void photosArePrivatePermanentEvidenceAndBindOnlyToTheMappedNxrItem() throws Exception {
        Client c=createClient(1,"A"); IntakeDetail i=ready(c.id(),1,"a"); Card card=i.cards().get(0);
        ByteArrayOutputStream buffer=new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(20,30,BufferedImage.TYPE_INT_RGB),"png",buffer);
        MockMultipartFile file=new MockMultipartFile("file","card.png","image/png",buffer.toByteArray());
        assertThatThrownBy(()->tx.execute(s->service.uploadPhoto(2,card.id(),"front",file))).hasMessageContaining("404");
        Card withPhoto=tx.execute(s->service.uploadPhoto(1,card.id(),"front",file)); long photo=withPhoto.frontPhotoId();
        assertThat(photos.readOwned(1,photo,true).getContentAsByteArray()).isEqualTo(buffer.toByteArray());
        assertThat(photos.unused(1)).isEmpty();
        assertThatThrownBy(()->photos.readOwned(2,photo,false)).hasMessageContaining("404");
        assertThatThrownBy(()->tx.executeWithoutResult(s->photos.removeUnused(1,photo))).hasMessageContaining("preserved");
        assertThatThrownBy(()->photos.requireOwnedPhoto(1,photo)).hasMessageContaining("already attached");
        assertThatThrownBy(()->tx.executeWithoutResult(s->photos.attachAgentEvidence(2,card.id(),1,List.of(photo)))).hasMessageContaining("404");
        submit(i,"photo-submission"); Card submitted=service.intakeDetail(1,i.intake().id()).cards().get(0);
        assertThat(photos.attachedOrderId(photo)).isEqualTo(submitted.orderId());
        assertThat(jdbc.queryForObject("SELECT front_photo_id FROM grading_order_item WHERE id=?",Long.class,submitted.orderItemId())).isEqualTo(photo);
        assertThatThrownBy(()->tx.execute(s->service.uploadPhoto(1,card.id(),"front",file))).hasMessageContaining("cannot be replaced");
        assertThat(photos.readOwned(1,photo,true).exists()).isTrue();
    }

    @Test void replayedCheckInCannotUndoLaterCorrectionsAndReusedKeysRejectDifferentDetails() {
        Client client=createClient(1,"A"); IntakeDetail intake=createIntake(1,client.id(),1,"i"); receive(intake);
        Card card=intake.cards().get(0);
        var exception=new CheckInRequest(card.inventoryCode(),"Damaged sleeve",true,"exception-key");
        var cleared=new CheckInRequest(card.inventoryCode(),null,false,"clear-key");
        tx.execute(s->service.checkIn(1,intake.intake().id(),exception));
        tx.execute(s->service.checkIn(1,intake.intake().id(),cleared));
        assertThat(tx.execute(s->service.checkIn(1,intake.intake().id(),exception)).intake().statusCode()).isEqualTo("ready");
        assertThatThrownBy(()->tx.execute(s->service.checkIn(1,intake.intake().id(),new CheckInRequest(card.inventoryCode(),"Other damage",true,"exception-key"))))
            .hasMessageContaining("409");
        var newer=new CheckInRequest(card.inventoryCode(),"New damage",true,"new-exception");
        tx.execute(s->service.checkIn(1,intake.intake().id(),newer));
        assertThat(tx.execute(s->service.checkIn(1,intake.intake().id(),cleared)).intake().statusCode()).isEqualTo("exception");
        assertThat(count("agent_operation")).isEqualTo(3);
        assertThat(service.events(1,null,intake.intake().id(),null,1,100).items().stream().filter(e->e.eventCode().startsWith("card_"))).hasSize(3);
        assertThatThrownBy(()->tx.execute(s->service.checkIn(1,intake.intake().id(),new CheckInRequest(card.inventoryCode(),null,false,null))))
            .hasMessageContaining("Request key is required");
    }

    @Test void archivedClientCanReceiveExistingCardsAndHistoryCanBePaged() {
        Client c=createClient(1,"A"); IntakeDetail i=ready(c.id(),1,"a"); Submission sub=submit(i,"s"); markNxrDelivered(sub);
        Card card=service.intakeDetail(1,i.intake().id()).cards().get(0); returnCard(card,card.inventoryCode());
        jdbc.update("UPDATE agent_client SET active=0 WHERE id=?",c.id());
        assertThat(ship(c.id(),List.of(card.id()),"return-archived").shipment().cardCount()).isEqualTo(1);
        assertThat(service.clients(1,1,20,null,true).items()).isEmpty();
        assertThat(service.clients(1,1,20,"Client A",false).items()).hasSize(1);
        assertThat(service.events(1,c.id(),null,null,1,2).items()).hasSize(2);
        assertThat(service.events(1,c.id(),null,null,2,2).items()).doesNotContainAnyElementsOf(service.events(1,c.id(),null,null,1,2).items());
    }

    private ClientRequest clientRequest(String reference,String key) {
        return new ClientRequest(reference,"Client "+reference,"555",reference+"@example.test","Recipient","One Road",null,"City",null,"123","US",null,true,key);
    }
    private Client createClient(long owner,String reference) { return tx.execute(s->service.createClient(owner,clientRequest(reference,"client-"+reference))); }
    private IntakeDetail createIntake(long owner,long client,int count,String key) {
        var cards=java.util.stream.IntStream.range(0,count).mapToObj(n->new IntakeCardRequest("Card "+n,"EN","Inventory note")).toList();
        return tx.execute(s->service.createIntake(owner,new IntakeRequest(client,"UPS","INBOUND",count,null,cards,key)));
    }
    private void receive(IntakeDetail i) { tx.execute(s->service.receive(1,i.intake().id(),null)); }
    private void check(IntakeDetail i,Card card,boolean exception) { tx.execute(s->service.checkIn(1,i.intake().id(),new CheckInRequest(card.inventoryCode(),exception?"Damaged sleeve":null,exception,UUID.randomUUID().toString()))); }
    private IntakeDetail ready(long client,int count,String key) {
        IntakeDetail i=createIntake(1,client,count,key); receive(i); for(Card c:i.cards()) check(i,c,false); return service.intakeDetail(1,i.intake().id());
    }
    private Submission submit(IntakeDetail i,String key) { return tx.execute(s->service.submit(1,new SubmissionRequest(List.of(i.intake().id()),10L,"agent_return","USD","Batch",key))); }
    private Card returnCard(Card c,String scan) { return tx.execute(s->service.returnCheck(1,c.id(),new ReturnCheckRequest(scan,null,null))); }
    private ShipmentDetail ship(long client,List<Long> cards,String key) { return tx.execute(s->service.createShipment(1,new ShipmentRequest(client,cards,"DHL","RETURN",key,null,null))); }
    private void markNxrDelivered(Submission s) {
        jdbc.update("UPDATE merchant_order_batch SET status_code='delivered' WHERE id=?",s.batchId());
        jdbc.update("UPDATE grading_order SET status_code='delivered' WHERE id IN(SELECT order_id FROM merchant_order_batch_item WHERE batch_id=?)",s.batchId());
    }
    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class); }
    private CustomerPortalService.OrderDetailResponse insertOrder(long owner,CustomerPortalService.CreateOrderRequest r,boolean omitLast) {
        int id=sequence.incrementAndGet();
        jdbc.update("INSERT INTO grading_order(id,order_no,customer_id,status_code,service_level_code,total_card_count,service_fee,return_shipping_fee,total_amount,currency_code,contact_name,contact_phone,return_address_line1,return_city,return_postal_code,return_country) VALUES(?,?,?,'admission_review','basic_grading',?,10,0,10,'USD','Agent','1','Agent road','City','1','US')",id,"NXR-TEST-"+id,owner,r.items().size());
        int limit=r.items().size()-(omitLast?1:0);
        for(int n=0;n<limit;n++) jdbc.update("INSERT INTO grading_order_item(order_id,item_no,card_name,language_code,status_code) VALUES(?,?,?,?,'submitted')",id,n+1,r.items().get(n).cardName(),r.items().get(n).languageCode());
        var result=mock(CustomerPortalService.OrderDetailResponse.class); when(result.id()).thenReturn((long)id); when(result.orderNo()).thenReturn("NXR-TEST-"+id); return result;
    }
}
