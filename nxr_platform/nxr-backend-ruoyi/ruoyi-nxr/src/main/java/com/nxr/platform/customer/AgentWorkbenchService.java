package com.nxr.platform.customer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Agent custody is separate from NXR fulfillment and never changes its payment or final-mile state. */
@Service
public class AgentWorkbenchService {
    private final JdbcClient jdbc;
    private final OrderFulfillmentService fulfillment;
    private final MerchantBatchService batches;
    private final CustomerOrderPhotoService photos;
    private final ObjectMapper json;
    private final com.nxr.platform.admission.OrderAdmissionService admission;

    public AgentWorkbenchService(JdbcClient jdbc, OrderFulfillmentService fulfillment,
        MerchantBatchService batches, CustomerOrderPhotoService photos, ObjectMapper json,
        com.nxr.platform.admission.OrderAdmissionService admission) {
        this.jdbc = jdbc; this.fulfillment = fulfillment; this.batches = batches; this.photos = photos; this.json = json; this.admission = admission;
    }

    public record Page<T>(List<T> items, long total, int page, int pageSize) { }
    public record Address(String contactName, String phone, String addressLine1, String addressLine2,
        String city, String region, String postalCode, String country) { }
    public record ClientRequest(String reference, String displayName, String phone, String email, String contactName,
        String addressLine1, String addressLine2, String city, String region, String postalCode, String country,
        String notes, Boolean active, String requestKey) { }
    public record Client(long id, String reference, String displayName, String phone, String email, String contactName,
        String addressLine1, String addressLine2, String city, String region, String postalCode, String country,
        String notes, boolean active, LocalDateTime createdAt, LocalDateTime updatedAt) { }
    public record ClientDetail(Client client, List<Intake> intakes, List<Shipment> shipments, List<Event> events) { }
    public record IntakeCardRequest(String cardName, String languageCode, String notes, String officialCardNumber) {
        public IntakeCardRequest(String cardName, String languageCode, String notes) {
            this(cardName, languageCode, notes, null);
        }
    }
    public record IntakeRequest(Long clientId, String carrierName, String trackingNumber, Integer expectedCardCount,
        String notes, List<IntakeCardRequest> cards, String requestKey) { }
    public record Intake(long id, String intakeNo, long clientId, String clientName, String carrierName,
        String trackingNumber, int expectedCardCount, int checkedInCardCount, int exceptionCardCount,
        String statusCode, Long batchId, String batchNo, Long orderId, String orderNo, String notes,
        LocalDateTime receivedAt, LocalDateTime createdAt) { }
    public record IntakeDetail(Intake intake, List<Card> cards, List<Event> events) { }
    public record Card(long id, long intakeId, String intakeNo, long clientId, String clientName,
        String inventoryCode, String officialCardNumber, String cardName, String languageCode, String notes, String statusCode,
        String conditionNote, String gradingCertId, Long frontPhotoId, Long backPhotoId, Long orderItemId, Long orderId, String orderNo,
        Long batchId, String batchNo, String batchStatusCode, Long returnShipmentId,
        LocalDateTime checkedInAt, LocalDateTime returnedAt) { }
    public record NoteRequest(String note, String requestKey) { }
    public record CheckInRequest(String inventoryCode, String conditionNote, Boolean hasException, String requestKey) { }
    public record ReturnCheckRequest(String inventoryCode, String note, String requestKey) { }
    public record SubmissionRequest(List<Long> intakeIds, Long returnAddressId, String returnShippingOptionCode,
        String currencyCode, String batchName, String requestKey, BigDecimal quotedTotalAmount, String quotedCurrencyCode) {
        public SubmissionRequest(List<Long> intakeIds, Long returnAddressId, String returnShippingOptionCode,
            String currencyCode, String batchName, String requestKey) {
            this(intakeIds,returnAddressId,returnShippingOptionCode,currencyCode,batchName,requestKey,null,null);
        }
    }
    public record Submission(long id, long batchId, String batchNo, List<Long> intakeIds, LocalDateTime createdAt) { }
    public record ShipmentRequest(Long clientId, List<Long> cardIds, String carrierName, String trackingNumber,
        String requestKey, Address address, String note) { }
    public record Shipment(long id, String shipmentNo, long clientId, String clientName, String carrierName,
        String trackingNumber, String statusCode, int cardCount, Address address, String notes,
        LocalDateTime shippedAt, LocalDateTime deliveredAt) { }
    public record ShipmentDetail(Shipment shipment, List<Card> cards, List<Event> events) { }
    public record Event(long id, String eventCode, String note, String inventoryCode, LocalDateTime createdAt) { }

    private static final String CLIENT_SELECT = """
        SELECT id, reference, display_name, phone, email, contact_name, address_line1, address_line2,
               city, region, postal_code, country, notes, active, created_at, updated_at FROM agent_client
        """;
    private static final String INTAKE_SELECT = """
        SELECT i.id, i.intake_no, i.client_id, cl.display_name AS client_name, i.carrier_name, i.tracking_number,
               i.expected_card_count,
               (SELECT COUNT(*) FROM agent_card c WHERE c.intake_id = i.id AND c.merchant_customer_id = i.merchant_customer_id AND c.checked_in_at IS NOT NULL) AS checked_in_card_count,
               (SELECT COUNT(*) FROM agent_card c WHERE c.intake_id = i.id AND c.merchant_customer_id = i.merchant_customer_id AND c.status_code = 'exception') AS exception_card_count,
               i.status_code, i.batch_id, b.batch_no, i.order_id, o.order_no, i.notes, i.received_at, i.created_at
        FROM agent_intake i JOIN agent_client cl ON cl.id = i.client_id AND cl.merchant_customer_id = i.merchant_customer_id
        LEFT JOIN merchant_order_batch b ON b.id = i.batch_id AND b.merchant_customer_id = i.merchant_customer_id
        LEFT JOIN grading_order o ON o.id = i.order_id AND o.customer_id = i.merchant_customer_id
        """;
    private static final String CARD_SELECT = """
        SELECT c.id, c.intake_id, i.intake_no, i.client_id, cl.display_name AS client_name,
               c.inventory_code, c.official_card_number, c.card_name, c.language_code, c.notes, c.status_code, c.condition_note,
               gs.cert_id AS grading_cert_id, c.front_photo_id, c.back_photo_id, c.order_item_id, i.order_id, o.order_no,
               i.batch_id, b.batch_no, b.status_code AS batch_status_code, c.return_shipment_id, c.checked_in_at, c.returned_at
        FROM agent_card c JOIN agent_intake i ON i.id = c.intake_id AND i.merchant_customer_id = c.merchant_customer_id
        JOIN agent_client cl ON cl.id = i.client_id AND cl.merchant_customer_id = c.merchant_customer_id
        LEFT JOIN merchant_order_batch b ON b.id = i.batch_id AND b.merchant_customer_id = c.merchant_customer_id
        LEFT JOIN grading_order o ON o.id = i.order_id AND o.customer_id = c.merchant_customer_id
        LEFT JOIN grading_order_item oi ON oi.id=c.order_item_id AND oi.order_id=o.id
        LEFT JOIN grading_submission gs ON gs.id=oi.grading_submission_id
        """;
    private static final String SHIPMENT_SELECT = """
        SELECT s.*, cl.display_name AS client_name,
               (SELECT COUNT(*) FROM agent_card c WHERE c.return_shipment_id = s.id AND c.merchant_customer_id = s.merchant_customer_id) AS card_count
        FROM agent_return_shipment s JOIN agent_client cl ON cl.id = s.client_id AND cl.merchant_customer_id = s.merchant_customer_id
        """;

    public Page<Client> clients(long owner, int page, int size, String query, Boolean active) {
        fulfillment.requireMerchant(owner);
        Map<String, Object> p = params("owner", owner);
        String where = " WHERE merchant_customer_id = :owner";
        if (active != null) { where += " AND active = :active"; p.put("active", active ? 1 : 0); }
        if (optional(query, 255) != null) {
            where += " AND (LOWER(reference) LIKE :q OR LOWER(display_name) LIKE :q OR LOWER(phone) LIKE :q OR LOWER(email) LIKE :q)";
            p.put("q", search(query));
        }
        return page(CLIENT_SELECT, "SELECT COUNT(*) FROM agent_client", where, "id", p, page, size, Client.class);
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Client createClient(long owner, ClientRequest r) {
        lockOwner(owner); require(r, "Customer details");
        String hash = hash(r), key = optional(r.requestKey(), 128);
        Long prior = replay("agent_client", owner, key, hash);
        if (prior != null) return client(owner, prior);
        Map<String, Object> p = clientParams(owner, r);
        if (count("SELECT COUNT(*) FROM agent_client WHERE merchant_customer_id = :owner AND reference = :reference", p) > 0)
            throw conflict("This client reference already exists");
        p.put("key", key); p.put("hash", hash);
        jdbc.sql("""
            INSERT INTO agent_client (merchant_customer_id,reference,display_name,phone,email,contact_name,
                address_line1,address_line2,city,region,postal_code,country,notes,active,request_key,request_hash)
            VALUES (:owner,:reference,:name,:phone,:email,:contact,:line1,:line2,:city,:region,:postal,:country,:notes,:active,:key,:hash)
            """).params(p).update();
        long id = jdbc.sql("SELECT id FROM agent_client WHERE merchant_customer_id = :owner AND reference = :reference").params(p).query(Long.class).single();
        event(owner, id, null, null, null, "client_created", optional(r.notes(), 2000), null);
        return client(owner, id);
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Client updateClient(long owner, long id, ClientRequest r) {
        lockOwner(owner); client(owner, id); require(r, "Customer details");
        Map<String, Object> p = clientParams(owner, r); p.put("id", id);
        if (count("SELECT COUNT(*) FROM agent_client WHERE merchant_customer_id = :owner AND reference = :reference AND id <> :id", p) > 0)
            throw conflict("This client reference already exists");
        jdbc.sql("""
            UPDATE agent_client SET reference=:reference,display_name=:name,phone=:phone,email=:email,contact_name=:contact,
                address_line1=:line1,address_line2=:line2,city=:city,region=:region,postal_code=:postal,country=:country,
                notes=:notes,active=:active,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND merchant_customer_id=:owner
            """).params(p).update();
        event(owner, id, null, null, null, "client_updated", r.active() != null && !r.active() ? "Client archived" : "Client details updated", null);
        return client(owner, id);
    }

    public ClientDetail clientDetail(long owner, long id) {
        fulfillment.requireMerchant(owner);
        return new ClientDetail(client(owner, id), intakes(owner, 1, 100, id, null, null).items(),
            shipments(owner, 1, 100, id, null, null).items(), events(owner, id, null, null, 1, 100).items());
    }

    public Page<Intake> intakes(long owner, int page, int size, Long clientId, String status, String query) {
        fulfillment.requireMerchant(owner);
        Map<String, Object> p = params("owner", owner);
        String where = " WHERE i.merchant_customer_id = :owner";
        if (clientId != null) { where += " AND i.client_id = :client"; p.put("client", clientId); }
        if (optional(status, 32) != null) { where += " AND i.status_code = :status"; p.put("status", status); }
        if (optional(query, 255) != null) {
            where += " AND (LOWER(i.intake_no) LIKE :q OR LOWER(i.tracking_number) LIKE :q OR LOWER(cl.display_name) LIKE :q)"; p.put("q", search(query));
        }
        return page(INTAKE_SELECT, "SELECT COUNT(*) FROM agent_intake i JOIN agent_client cl ON cl.id=i.client_id AND cl.merchant_customer_id=i.merchant_customer_id",
            where, "i.id", p, page, size, Intake.class);
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public IntakeDetail createIntake(long owner, IntakeRequest r) {
        lockOwner(owner); require(r, "Intake details");
        String hash = hash(r), key = optional(r.requestKey(), 128);
        Long prior = replay("agent_intake", owner, key, hash);
        if (prior != null) return intakeDetail(owner, prior);
        if (r.clientId() == null) throw bad("Select a client");
        Client client = client(owner, r.clientId());
        if (!client.active()) throw conflict("This client is archived");
        if (r.expectedCardCount() == null || r.expectedCardCount() < 1 || r.expectedCardCount() > 1000 || r.cards() == null
            || r.cards().size() != r.expectedCardCount()) throw bad("Register exactly the expected number of cards (1 to 1000)");
        if (r.expectedCardCount() > admission.maxCardsPerOrder()) throw bad("Each intake may contain at most " + admission.maxCardsPerOrder() + " cards under the current NXR policy");
        String number = number("AI");
        jdbc.sql("""
            INSERT INTO agent_intake (merchant_customer_id,intake_no,client_id,carrier_name,tracking_number,expected_card_count,notes,request_key,request_hash)
            VALUES (:owner,:number,:client,:carrier,:tracking,:expected,:notes,:key,:hash)
            """).params(params("owner",owner,"number",number,"client",client.id(),"carrier",optional(r.carrierName(),128),
                "tracking",optional(r.trackingNumber(),255),"expected",r.expectedCardCount(),"notes",optional(r.notes(),2000),"key",key,"hash",hash)).update();
        long id = jdbc.sql("SELECT id FROM agent_intake WHERE intake_no=:number AND merchant_customer_id=:owner")
            .param("number",number).param("owner",owner).query(Long.class).single();
        for (IntakeCardRequest c : r.cards()) {
            require(c, "Card details");
            jdbc.sql("""
                INSERT INTO agent_card (merchant_customer_id,intake_id,inventory_code,official_card_number,card_name,language_code,notes)
                VALUES (:owner,:intake,:code,:official,:name,:language,:notes)
                """).params(params("owner",owner,"intake",id,"code",number("AC"),"name",required(c.cardName(),"Card name",255),
                    "official",optional(c.officialCardNumber(),128),"language",required(c.languageCode(),"Language",32).toUpperCase(Locale.ROOT),"notes",optional(c.notes(),2000))).update();
        }
        event(owner, client.id(), id, null, null, "intake_created", optional(r.notes(),2000), null);
        return intakeDetail(owner, id);
    }

    public IntakeDetail intakeDetail(long owner, long id) {
        fulfillment.requireMerchant(owner);
        return new IntakeDetail(intake(owner,id), cardsForIntake(owner,id), events(owner,null,id,null,1,100).items());
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public IntakeDetail receive(long owner, long id, NoteRequest r) {
        lockOwner(owner); Intake i = intake(owner,id);
        if (i.receivedAt() != null) return intakeDetail(owner,id);
        if (!"expected".equals(i.statusCode())) throw conflict("This intake cannot be received");
        jdbc.sql("UPDATE agent_intake SET status_code='received', received_at=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP WHERE id=:id AND merchant_customer_id=:owner")
            .param("id",id).param("owner",owner).update();
        event(owner,i.clientId(),id,null,null,"intake_received",note(r),null);
        return intakeDetail(owner,id);
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public IntakeDetail checkIn(long owner, long id, CheckInRequest r) {
        lockOwner(owner); require(r,"Card check-in");
        String key=required(r.requestKey(),"Request key",128),hash=hash(r);
        var replay=jdbc.sql("SELECT target_id,request_hash FROM agent_operation WHERE merchant_customer_id=:owner AND operation_code='check_in' AND request_key=:key")
            .params(params("owner",owner,"key",key)).query().listOfRows();
        if(!replay.isEmpty()) {
            if(((Number)replay.get(0).get("target_id")).longValue()!=id || !hash.equals(replay.get(0).get("request_hash")))
                throw conflict("This request key was already used for different details");
            return intakeDetail(owner,id);
        }
        Intake i = intake(owner,id);
        if (i.receivedAt() == null) throw conflict("Receive the parcel before checking in cards");
        if (i.orderId() != null) throw conflict("Submitted intake cards cannot be changed");
        String code = required(r.inventoryCode(),"Inventory code",64).toUpperCase(Locale.ROOT);
        Card card = jdbc.sql(CARD_SELECT+" WHERE c.merchant_customer_id=:owner AND c.intake_id=:intake AND c.inventory_code=:code")
            .params(params("owner",owner,"intake",id,"code",code)).query(Card.class).optional().orElseThrow(AgentWorkbenchService::missing);
        boolean exception = Boolean.TRUE.equals(r.hasException());
        String condition = exception ? required(r.conditionNote(),"Exception details",2000) : optional(r.conditionNote(),2000);
        String state = exception ? "exception" : "in_stock";
        // Remember successful no-ops as well: their later replay must never undo a newer correction.
        jdbc.sql("INSERT INTO agent_operation(merchant_customer_id,operation_code,request_key,target_id,request_hash) VALUES(:owner,'check_in',:key,:id,:hash)")
            .params(params("owner",owner,"key",key,"id",id,"hash",hash)).update();
        if (state.equals(card.statusCode()) && Objects.equals(condition,card.conditionNote())) return intakeDetail(owner,id);
        jdbc.sql("UPDATE agent_card SET status_code=:state,condition_note=:note,checked_in_at=COALESCE(checked_in_at,CURRENT_TIMESTAMP),updated_at=CURRENT_TIMESTAMP WHERE id=:id AND merchant_customer_id=:owner")
            .params(params("state",state,"note",condition,"id",card.id(),"owner",owner)).update();
        List<Card> all = cardsForIntake(owner,id);
        String intakeState = all.stream().anyMatch(c -> "exception".equals(c.statusCode())) ? "exception"
            : all.size() == i.expectedCardCount() && all.stream().allMatch(c -> "in_stock".equals(c.statusCode())) ? "ready" : "received";
        jdbc.sql("UPDATE agent_intake SET status_code=:state,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND merchant_customer_id=:owner")
            .params(params("state",intakeState,"id",id,"owner",owner)).update();
        event(owner,i.clientId(),id,card.id(),null,exception ? "card_exception" : "card_checked_in",condition,code);
        return intakeDetail(owner,id);
    }

    public Page<Card> cards(long owner,int page,int size,Long clientId,Long intakeId,String status,String query) {
        fulfillment.requireMerchant(owner);
        Map<String,Object> p=params("owner",owner);
        String where=" WHERE c.merchant_customer_id=:owner";
        if(clientId!=null) { where+=" AND i.client_id=:client"; p.put("client",clientId); }
        if(intakeId!=null) { where+=" AND c.intake_id=:intake"; p.put("intake",intakeId); }
        if(optional(status,32)!=null) { where+=" AND c.status_code=:status"; p.put("status",status); }
        if(optional(query,255)!=null) { where+=" AND (LOWER(c.inventory_code) LIKE :q OR LOWER(c.card_name) LIKE :q OR LOWER(gs.cert_id) LIKE :q OR LOWER(i.intake_no) LIKE :q OR LOWER(cl.display_name) LIKE :q)"; p.put("q",search(query)); }
        String count="SELECT COUNT(*) FROM agent_card c JOIN agent_intake i ON i.id=c.intake_id AND i.merchant_customer_id=c.merchant_customer_id JOIN agent_client cl ON cl.id=i.client_id AND cl.merchant_customer_id=c.merchant_customer_id LEFT JOIN grading_order o ON o.id=i.order_id AND o.customer_id=c.merchant_customer_id LEFT JOIN grading_order_item oi ON oi.id=c.order_item_id AND oi.order_id=o.id LEFT JOIN grading_submission gs ON gs.id=oi.grading_submission_id";
        return page(CARD_SELECT,count,where,"c.id",p,page,size,Card.class);
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Card uploadPhoto(long owner,long cardId,String side,MultipartFile file) {
        lockOwner(owner); Card c=card(owner,cardId);
        if(!List.of("front","back").contains(side)) throw bad("Photo side must be front or back");
        if(c.orderItemId()!=null) throw conflict("Submitted intake evidence cannot be replaced");
        CustomerOrderPhotoService.Photo photo=photos.upload(owner,file);
        photos.preserveForAgent(owner,photo.id());
        jdbc.sql("UPDATE agent_card SET "+("front".equals(side)?"front_photo_id":"back_photo_id")+"=:photo,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND merchant_customer_id=:owner")
            .params(params("photo",photo.id(),"id",cardId,"owner",owner)).update();
        event(owner,c.clientId(),c.intakeId(),cardId,null,"photo_added",side+" photo #"+photo.id(),c.inventoryCode());
        return card(owner,cardId);
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Submission submit(long owner,SubmissionRequest r) {
        lockOwner(owner); require(r,"Submission details");
        String key=required(r.requestKey(),"Request key",128), hash=hash(r);
        Long prior=replay("agent_submission",owner,key,hash);
        if(prior!=null) return submission(owner,prior);
        List<Long> ids=ids(r.intakeIds(),200,"intakes");
        if(r.returnAddressId()==null) throw bad("Select the agent return address");
        // The shared NXR route and quote service remains authoritative for return shipping and pricing.
        fulfillment.requireAddress(owner,r.returnAddressId());
        String option=required(r.returnShippingOptionCode(),"Return shipping option",32);
        String currency=required(r.currencyCode(),"Currency",8).toUpperCase(Locale.ROOT);
        List<Intake> intakes=new ArrayList<>();
        List<List<Card>> inventory=new ArrayList<>();
        List<MerchantBatchService.BatchOrderRequest> orders=new ArrayList<>();
        for(long id:ids) {
            Intake intake=intake(owner,id); Client client=client(owner,intake.clientId());
            List<Card> cards=cardsForIntake(owner,id);
            if(!"ready".equals(intake.statusCode()) || intake.receivedAt()==null || intake.orderId()!=null
                || cards.size()!=intake.expectedCardCount() || cards.stream().anyMatch(c -> !"in_stock".equals(c.statusCode()) || c.checkedInAt()==null || c.orderItemId()!=null))
                throw conflict("Every selected intake must be fully received, counted and free of exceptions");
            List<CustomerPortalService.OrderItemRequest> items=cards.stream().map(c -> new CustomerPortalService.OrderItemRequest(
                c.cardName(),null,null,c.officialCardNumber(),c.languageCode(),BigDecimal.ZERO,c.notes())).toList();
            var order=new CustomerPortalService.CreateOrderRequest("basic_grading",r.returnAddressId(),false,option,
                null,null,null,null,null,null,null,null,intake.intakeNo(),List.of(),items,currency);
            // Intake number makes two parcels from the same client distinct within a master batch.
            // NXR communicates with the agent; end-client contact details stay in the agent directory.
            orders.add(new MerchantBatchService.BatchOrderRequest(intake.intakeNo(),client.displayName(),null,order));
            intakes.add(intake); inventory.add(cards);
        }
        var batch=batches.createBatchInCurrentTransaction(owner,new MerchantBatchService.BatchCreateRequest("Agent workbench",optional(r.batchName(),191),orders));
        if(r.quotedTotalAmount()!=null || r.quotedCurrencyCode()!=null) {
            var amounts=jdbc.sql("""
                SELECT o.currency_code,SUM(o.total_amount) AS total_amount FROM grading_order o
                JOIN merchant_order_batch_item bi ON bi.order_id=o.id
                JOIN merchant_order_batch b ON b.id=bi.batch_id AND b.merchant_customer_id=o.customer_id
                WHERE b.id=:batch AND o.customer_id=:owner GROUP BY o.currency_code
                """).params(params("batch",batch.batchId(),"owner",owner)).query().listOfRows();
            if(r.quotedTotalAmount()==null || r.quotedCurrencyCode()==null || amounts.size()!=1
                || r.quotedTotalAmount().compareTo((BigDecimal)amounts.get(0).get("total_amount"))!=0
                || !r.quotedCurrencyCode().strip().equalsIgnoreCase((String)amounts.get(0).get("currency_code")))
                throw conflict("The batch quote has changed. Refresh the quote before submitting");
        }
        if(batch.rows().size()!=intakes.size()) throw new IllegalStateException("Batch row count does not match agent intakes");
        for(int index=0;index<intakes.size();index++) {
            Intake intake=intakes.get(index); var row=batch.rows().get(index);
            if(row.orderId()==null || !intake.intakeNo().equals(row.clientReference())) throw new IllegalStateException("Batch intake mapping is inconsistent");
            List<Long> orderItems=jdbc.sql("""
                SELECT oi.id FROM grading_order_item oi JOIN grading_order o ON o.id=oi.order_id
                WHERE oi.order_id=:orderId AND o.customer_id=:owner ORDER BY oi.item_no,oi.id
                """).params(params("orderId",row.orderId(),"owner",owner)).query(Long.class).list();
            List<Card> cards=inventory.get(index);
            if(orderItems.size()!=cards.size()) throw new IllegalStateException("Created order card count does not match inventory");
            jdbc.sql("UPDATE agent_intake SET status_code='submitted',batch_id=:batch,order_id=:orderId,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND merchant_customer_id=:owner")
                .params(params("batch",batch.batchId(),"orderId",row.orderId(),"id",intake.id(),"owner",owner)).update();
            for(int n=0;n<cards.size();n++) {
                Card card=cards.get(n); long itemId=orderItems.get(n);
                jdbc.sql("UPDATE agent_card SET status_code='submitted',order_item_id=:item,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND merchant_customer_id=:owner AND order_item_id IS NULL")
                    .params(params("item",itemId,"id",card.id(),"owner",owner)).update();
                List<Long> photoIds=Arrays.asList(card.frontPhotoId(),card.backPhotoId());
                if(photoIds.stream().anyMatch(Objects::nonNull)) {
                    photos.attachAgentEvidence(owner,card.id(),row.orderId(),photoIds);
                    jdbc.sql("UPDATE grading_order_item SET front_photo_id=:front,back_photo_id=:back WHERE id=:item AND order_id=:orderId AND EXISTS (SELECT 1 FROM grading_order o WHERE o.id=:orderId AND o.customer_id=:owner)")
                        .params(params("front",card.frontPhotoId(),"back",card.backPhotoId(),"item",itemId,"orderId",row.orderId(),"owner",owner)).update();
                }
            }
            event(owner,intake.clientId(),intake.id(),null,null,"intake_submitted",batch.batchNo(),null);
        }
        jdbc.sql("INSERT INTO agent_submission(merchant_customer_id,batch_id,request_key,request_hash) VALUES(:owner,:batch,:key,:hash)")
            .params(params("owner",owner,"batch",batch.batchId(),"key",key,"hash",hash)).update();
        return submission(owner,replay("agent_submission",owner,key,hash));
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Card returnCheck(long owner,long id,ReturnCheckRequest r) {
        lockOwner(owner); require(r,"Return check"); Card c=card(owner,id);
        String scan=normalizeReturnScan(r.inventoryCode());
        if(!c.inventoryCode().equalsIgnoreCase(scan) && (c.gradingCertId()==null || !c.gradingCertId().equalsIgnoreCase(scan)))
            throw conflict("Inventory code or certificate number does not match this card");
        requireBatchReturned(owner,c);
        if(List.of("returned","return_shipped","delivered").contains(c.statusCode())) return c;
        if(!"submitted".equals(c.statusCode()) || c.orderItemId()==null) throw conflict("This card is not awaiting return from NXR");
        jdbc.sql("UPDATE agent_card SET status_code='returned',returned_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND merchant_customer_id=:owner")
            .params(params("id",id,"owner",owner)).update();
        event(owner,c.clientId(),c.intakeId(),id,null,"card_return_checked",optional(r.note(),2000),c.inventoryCode());
        return card(owner,id);
    }

    public Page<Shipment> shipments(long owner,int page,int size,Long clientId,String status,String query) {
        fulfillment.requireMerchant(owner);
        Map<String,Object> p=params("owner",owner);
        String where=" WHERE s.merchant_customer_id=:owner";
        if(clientId!=null) { where+=" AND s.client_id=:client"; p.put("client",clientId); }
        if(optional(status,32)!=null) { where+=" AND s.status_code=:status"; p.put("status",status); }
        if(optional(query,255)!=null) { where+=" AND (LOWER(s.shipment_no) LIKE :q OR LOWER(s.tracking_number) LIKE :q OR LOWER(cl.display_name) LIKE :q)"; p.put("q",search(query)); }
        int safePage=Math.max(1,page), safeSize=Math.min(100,Math.max(1,size));
        long total=count("SELECT COUNT(*) FROM agent_return_shipment s JOIN agent_client cl ON cl.id=s.client_id AND cl.merchant_customer_id=s.merchant_customer_id"+where,p);
        p.put("limit",safeSize); p.put("offset",((long)safePage-1)*safeSize);
        return new Page<>(jdbc.sql(SHIPMENT_SELECT+where+" ORDER BY s.id DESC LIMIT :limit OFFSET :offset").params(p).query(this::mapShipment).list(),total,safePage,safeSize);
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public ShipmentDetail createShipment(long owner,ShipmentRequest r) {
        lockOwner(owner); require(r,"Shipment details");
        String key=required(r.requestKey(),"Request key",128),hash=hash(r);
        Long prior=replay("agent_return_shipment",owner,key,hash);
        if(prior!=null) return shipmentDetail(owner,prior);
        if(r.clientId()==null) throw bad("Select a client");
        Client client=client(owner,r.clientId());
        List<Long> ids=ids(r.cardIds(),1000,"cards");
        List<Card> selected=new ArrayList<>();
        for(long id:ids) {
            Card card=card(owner,id);
            if(card.clientId()!=client.id()) throw conflict("Every card in a return parcel must belong to the same client");
            if(!"returned".equals(card.statusCode()) || card.returnedAt()==null || card.returnShipmentId()!=null)
                throw conflict("Only checked returned cards that have not been shipped can be selected");
            requireBatchReturned(owner,card); selected.add(card);
        }
        Address address=validateAddress(r.address()!=null?r.address():new Address(client.contactName()==null?client.displayName():client.contactName(),
            client.phone(),client.addressLine1(),client.addressLine2(),client.city(),client.region(),client.postalCode(),client.country()));
        String number=number("AR");
        jdbc.sql("""
            INSERT INTO agent_return_shipment(merchant_customer_id,shipment_no,client_id,carrier_name,tracking_number,
                contact_name,phone,address_line1,address_line2,city,region,postal_code,country,notes,request_key,request_hash)
            VALUES(:owner,:number,:client,:carrier,:tracking,:contact,:phone,:line1,:line2,:city,:region,:postal,:country,:notes,:key,:hash)
            """).params(params("owner",owner,"number",number,"client",client.id(),"carrier",required(r.carrierName(),"Carrier",128),
                "tracking",required(r.trackingNumber(),"Tracking number",255),"contact",address.contactName(),"phone",address.phone(),
                "line1",address.addressLine1(),"line2",address.addressLine2(),"city",address.city(),"region",address.region(),
                "postal",address.postalCode(),"country",address.country(),"notes",optional(r.note(),2000),"key",key,"hash",hash)).update();
        long id=replay("agent_return_shipment",owner,key,hash);
        for(Card card:selected) {
            int changed=jdbc.sql("UPDATE agent_card SET return_shipment_id=:shipment,status_code='return_shipped',updated_at=CURRENT_TIMESTAMP WHERE id=:id AND merchant_customer_id=:owner AND status_code='returned' AND return_shipment_id IS NULL")
                .params(params("shipment",id,"id",card.id(),"owner",owner)).update();
            if(changed!=1) throw conflict("This card has already been shipped");
            event(owner,client.id(),card.intakeId(),card.id(),id,"card_return_shipped",number,card.inventoryCode());
        }
        event(owner,client.id(),null,null,id,"shipment_created",optional(r.note(),2000),null);
        return shipmentDetail(owner,id);
    }

    public ShipmentDetail shipmentDetail(long owner,long id) {
        fulfillment.requireMerchant(owner);
        Shipment shipment=shipment(owner,id);
        List<Card> cards=jdbc.sql(CARD_SELECT+" WHERE c.merchant_customer_id=:owner AND c.return_shipment_id=:id ORDER BY c.id")
            .params(params("owner",owner,"id",id)).query(Card.class).list();
        return new ShipmentDetail(shipment,cards,events(owner,null,null,id,1,100).items());
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public ShipmentDetail delivered(long owner,long id,NoteRequest r) {
        lockOwner(owner); Shipment shipment=shipment(owner,id);
        if("delivered".equals(shipment.statusCode())) return shipmentDetail(owner,id);
        if(!"shipped".equals(shipment.statusCode())) throw conflict("This parcel has not been shipped");
        jdbc.sql("UPDATE agent_return_shipment SET status_code='delivered',delivered_at=CURRENT_TIMESTAMP WHERE id=:id AND merchant_customer_id=:owner")
            .params(params("id",id,"owner",owner)).update();
        for(Card card:shipmentDetail(owner,id).cards()) {
            jdbc.sql("UPDATE agent_card SET status_code='delivered',updated_at=CURRENT_TIMESTAMP WHERE id=:id AND merchant_customer_id=:owner AND return_shipment_id=:shipment")
                .params(params("id",card.id(),"owner",owner,"shipment",id)).update();
            event(owner,shipment.clientId(),card.intakeId(),card.id(),id,"client_received_card",note(r),card.inventoryCode());
        }
        event(owner,shipment.clientId(),null,null,id,"shipment_delivered",note(r),null);
        return shipmentDetail(owner,id);
    }

    public Page<Event> events(long owner,Long clientId,Long intakeId,Long shipmentId,int page,int size) {
        fulfillment.requireMerchant(owner);
        Map<String,Object> p=params("owner",owner); String where=" WHERE merchant_customer_id=:owner";
        if(clientId!=null) { where+=" AND client_id=:client"; p.put("client",clientId); }
        if(intakeId!=null) { where+=" AND intake_id=:intake"; p.put("intake",intakeId); }
        if(shipmentId!=null) { where+=" AND shipment_id=:shipment"; p.put("shipment",shipmentId); }
        return page("SELECT id,event_code,note,inventory_code,created_at FROM agent_event","SELECT COUNT(*) FROM agent_event",where,"id",p,page,size,Event.class);
    }

    private void lockOwner(long owner) {
        fulfillment.requireMerchant(owner);
        // Serialize one agent's mutations and replay checks, including simultaneous retries and card selections.
        jdbc.sql("SELECT id FROM customer_account WHERE id=:owner FOR UPDATE").param("owner",owner).query(Long.class).single();
    }
    private Client client(long owner,long id) {
        return jdbc.sql(CLIENT_SELECT+" WHERE id=:id AND merchant_customer_id=:owner").params(params("id",id,"owner",owner))
            .query(Client.class).optional().orElseThrow(AgentWorkbenchService::missing);
    }
    private Intake intake(long owner,long id) {
        return jdbc.sql(INTAKE_SELECT+" WHERE i.id=:id AND i.merchant_customer_id=:owner").params(params("id",id,"owner",owner))
            .query(Intake.class).optional().orElseThrow(AgentWorkbenchService::missing);
    }
    private Card card(long owner,long id) {
        return jdbc.sql(CARD_SELECT+" WHERE c.id=:id AND c.merchant_customer_id=:owner").params(params("id",id,"owner",owner))
            .query(Card.class).optional().orElseThrow(AgentWorkbenchService::missing);
    }
    private List<Card> cardsForIntake(long owner,long id) {
        return jdbc.sql(CARD_SELECT+" WHERE c.intake_id=:id AND c.merchant_customer_id=:owner ORDER BY c.id")
            .params(params("id",id,"owner",owner)).query(Card.class).list();
    }
    private Shipment shipment(long owner,long id) {
        return jdbc.sql(SHIPMENT_SELECT+" WHERE s.id=:id AND s.merchant_customer_id=:owner").params(params("id",id,"owner",owner))
            .query(this::mapShipment).optional().orElseThrow(AgentWorkbenchService::missing);
    }
    private Submission submission(long owner,long id) {
        var row=jdbc.sql("SELECT s.id,s.batch_id,b.batch_no,s.created_at FROM agent_submission s JOIN merchant_order_batch b ON b.id=s.batch_id AND b.merchant_customer_id=s.merchant_customer_id WHERE s.id=:id AND s.merchant_customer_id=:owner")
            .params(params("id",id,"owner",owner)).query((rs,n)->new Submission(rs.getLong("id"),rs.getLong("batch_id"),rs.getString("batch_no"),List.of(),rs.getObject("created_at",LocalDateTime.class)))
            .optional().orElseThrow(AgentWorkbenchService::missing);
        return new Submission(row.id(),row.batchId(),row.batchNo(),jdbc.sql("SELECT id FROM agent_intake WHERE batch_id=:batch AND merchant_customer_id=:owner ORDER BY id")
            .params(params("batch",row.batchId(),"owner",owner)).query(Long.class).list(),row.createdAt());
    }
    private void requireBatchReturned(long owner,Card card) {
        if(card.batchId()==null) throw conflict("The NXR return parcel has not arrived at the agent");
        String state=jdbc.sql("SELECT status_code FROM merchant_order_batch WHERE id=:id AND merchant_customer_id=:owner FOR UPDATE")
            .params(params("id",card.batchId(),"owner",owner)).query(String.class).optional().orElseThrow(AgentWorkbenchService::missing);
        if(!"delivered".equals(state)) throw conflict("The NXR return parcel must be delivered to the agent before cards can be released");
    }
    static String normalizeReturnScan(String value) {
        String scan=required(value,"Inventory code or certificate number",256);
        if(scan.matches("[A-Za-z0-9_-]{1,64}")) return scan;
        try {
            String link=scan.matches("(?i)^(www\\.)?nxrgrading\\.com/.*")?"https://"+scan:scan;
            java.net.URI uri=java.net.URI.create(link);
            String host=uri.getHost(), path=uri.getRawPath();
            if(("https".equalsIgnoreCase(uri.getScheme())||"http".equalsIgnoreCase(uri.getScheme()))
                && host!=null && (host.equalsIgnoreCase("nxrgrading.com")||host.equalsIgnoreCase("www.nxrgrading.com"))
                && uri.getUserInfo()==null && uri.getPort()==-1 && path!=null && path.matches("/card/[A-Za-z0-9_-]{1,64}/?")) {
                return path.substring(6).replaceFirst("/$", "");
            }
        } catch(IllegalArgumentException ignored) { }
        throw bad("Scan an inventory code, certificate number or NXR certificate QR code");
    }
    private Long replay(String table,long owner,String key,String hash) {
        if(key==null) return null;
        // Table names are private call-site constants; no user input is interpolated into SQL.
        var row=jdbc.sql("SELECT id,request_hash FROM "+table+" WHERE merchant_customer_id=:owner AND request_key=:key")
            .params(params("owner",owner,"key",key)).query().listOfRows();
        if(row.isEmpty()) return null;
        if(!hash.equals(row.get(0).get("request_hash"))) throw conflict("This request key was already used for different details");
        return ((Number)row.get(0).get("id")).longValue();
    }
    private void event(long owner,long client,Long intake,Long card,Long shipment,String code,String note,String inventory) {
        jdbc.sql("INSERT INTO agent_event(merchant_customer_id,client_id,intake_id,card_id,shipment_id,event_code,note,inventory_code) VALUES(:owner,:client,:intake,:card,:shipment,:code,:note,:inventory)")
            .params(params("owner",owner,"client",client,"intake",intake,"card",card,"shipment",shipment,"code",code,"note",note,"inventory",inventory)).update();
    }
    private Map<String,Object> clientParams(long owner,ClientRequest r) {
        return params("owner",owner,"reference",required(r.reference(),"Client reference",64).toUpperCase(Locale.ROOT),
            "name",required(r.displayName(),"Client name",128),"phone",optional(r.phone(),64),"email",optional(r.email(),191),
            "contact",optional(r.contactName(),128),"line1",optional(r.addressLine1(),255),"line2",optional(r.addressLine2(),255),
            "city",optional(r.city(),128),"region",optional(r.region(),128),"postal",optional(r.postalCode(),64),
            "country",optional(r.country(),128),"notes",optional(r.notes(),2000),"active",Boolean.FALSE.equals(r.active())?0:1);
    }
    private Address validateAddress(Address a) {
        return new Address(required(a.contactName(),"Recipient",128),required(a.phone(),"Recipient phone",64),
            required(a.addressLine1(),"Street address",255),optional(a.addressLine2(),255),required(a.city(),"City",128),
            optional(a.region(),128),required(a.postalCode(),"Postal code",64),required(a.country(),"Country",128));
    }
    private Shipment mapShipment(java.sql.ResultSet rs,int n) throws java.sql.SQLException {
        return new Shipment(rs.getLong("id"),rs.getString("shipment_no"),rs.getLong("client_id"),rs.getString("client_name"),
            rs.getString("carrier_name"),rs.getString("tracking_number"),rs.getString("status_code"),rs.getInt("card_count"),
            new Address(rs.getString("contact_name"),rs.getString("phone"),rs.getString("address_line1"),rs.getString("address_line2"),
                rs.getString("city"),rs.getString("region"),rs.getString("postal_code"),rs.getString("country")),rs.getString("notes"),
            rs.getObject("shipped_at",LocalDateTime.class),rs.getObject("delivered_at",LocalDateTime.class));
    }
    private <T> Page<T> page(String select,String count,String where,String order,Map<String,Object> p,int page,int size,Class<T> type) {
        int safePage=Math.max(1,page),safeSize=Math.min(100,Math.max(1,size)); long total=count(count+where,p);
        p.put("limit",safeSize); p.put("offset",((long)safePage-1)*safeSize);
        return new Page<>(jdbc.sql(select+where+" ORDER BY "+order+" DESC LIMIT :limit OFFSET :offset").params(p).query(type).list(),total,safePage,safeSize);
    }
    private long count(String sql,Map<String,Object> p) { return jdbc.sql(sql).params(p).query(Long.class).single(); }
    private String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8))); }
        catch(JsonProcessingException|java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("Cannot fingerprint request",e); }
    }
    private static List<Long> ids(List<Long> ids,int max,String label) {
        if(ids==null||ids.isEmpty()||ids.size()>max||ids.stream().anyMatch(x->x==null||x<1)||ids.stream().distinct().count()!=ids.size())
            throw bad("Select between 1 and "+max+" unique "+label);
        return ids.stream().sorted().toList();
    }
    private static Map<String,Object> params(Object... pairs) {
        Map<String,Object> result=new LinkedHashMap<>(); for(int i=0;i<pairs.length;i+=2) result.put((String)pairs[i],pairs[i+1]); return result;
    }
    private static String number(String prefix) { return prefix+UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT); }
    private static String note(NoteRequest r) { return r==null?null:optional(r.note(),2000); }
    private static String search(String value) { return "%"+value.strip().toLowerCase(Locale.ROOT)+"%"; }
    private static String optional(String value,int max) {
        if(value==null||value.isBlank()) return null;
        String text=value.strip(); if(text.length()>max) throw bad("Text exceeds "+max+" characters"); return text;
    }
    private static String required(String value,String field,int max) { String text=optional(value,max); if(text==null) throw bad(field+" is required"); return text; }
    private static void require(Object value,String field) { if(value==null) throw bad(field+" are required"); }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"Agent record not found"); }
}
