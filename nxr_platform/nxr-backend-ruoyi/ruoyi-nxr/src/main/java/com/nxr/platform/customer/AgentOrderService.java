package com.nxr.platform.customer;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nxr.platform.admission.OrderAdmissionService;
import com.nxr.platform.shared.ProductTypePolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/** A partner's permanent customer Order, before and after its NXR submission. */
@Service
public class AgentOrderService {
    private final JdbcClient jdbc;
    private final AgentWorkbenchService workbench;
    private final OrderFulfillmentService fulfillment;
    private final CustomerOrderPhotoService photos;
    private final OrderAdmissionService admission;
    private final PartnerReturnService returns;
    private final ObjectMapper json;
    private final SimpleJdbcInsert clientInsert;
    private final SimpleJdbcInsert intakeInsert;
    private final SimpleJdbcInsert cardInsert;

    public AgentOrderService(JdbcClient jdbc, JdbcTemplate template, AgentWorkbenchService workbench,
        OrderFulfillmentService fulfillment, CustomerOrderPhotoService photos, OrderAdmissionService admission,
        PartnerReturnService returns, ObjectMapper json) {
        this.jdbc = jdbc; this.workbench = workbench; this.fulfillment = fulfillment;
        this.photos = photos; this.admission = admission; this.returns = returns; this.json = json;
        clientInsert = new SimpleJdbcInsert(template).withTableName("agent_client").usingGeneratedKeyColumns("id")
            .usingColumns("merchant_customer_id", "reference", "display_name", "phone", "email", "contact_name",
                "address_line1", "address_line2", "city", "region", "postal_code", "country", "active");
        intakeInsert = new SimpleJdbcInsert(template).withTableName("agent_intake").usingGeneratedKeyColumns("id")
            .usingColumns("merchant_customer_id", "intake_no", "client_id", "expected_card_count", "status_code", "notes",
                "request_key", "request_hash", "received_at", "partner_order_version", "client_name_snapshot", "client_phone_snapshot", "client_email_snapshot");
        cardInsert = new SimpleJdbcInsert(template).withTableName("agent_card").usingGeneratedKeyColumns("id")
            .usingColumns("merchant_customer_id", "intake_id", "inventory_code", "official_card_number", "card_name",
                "language_code", "brand_name", "set_name", "product_type", "category", "status_code", "checked_in_at", "front_photo_id", "back_photo_id");
    }

    public record CardInput(String cardName, String brandName, String setName, String officialCardNumber,
        String languageCode, String productType, String category, Long frontPhotoId, Long backPhotoId) {
        @JsonAnySetter public void rejectField(String key, Object value) { throw bad("Unsupported card field"); }
    }
    public record CreateRequest(String clientName, String phone, String email, String notes,
        List<CardInput> cards, String requestKey, AgentWorkbenchService.Address customerAddress) {
        public CreateRequest(String clientName, String phone, String email, String notes,
            List<CardInput> cards, String requestKey) {
            this(clientName, phone, email, notes, cards, requestKey, null);
        }
        @JsonAnySetter public void rejectField(String key, Object value) { throw bad("Unsupported order field"); }
    }
    public record Option(String value, String label) { }
    public record Options(List<Option> brands, List<Option> categories, List<Option> languages,
        List<Option> productTypes, int maxCardsPerOrder) { }
    public record WorkbenchOrder(long id, String orderNo, String clientName, String phone, String email,
        String statusCode, String intakeStatusCode, int cardCount, Long gradingOrderId, String gradingOrderNo,
        Long batchId, String batchNo, LocalDateTime createdAt, String notes, boolean canSubmit, boolean legacy,
        int gradedCardCount, int returnShippedCardCount, int deliveredCardCount, Integer returnableCardCount,
        PartnerReturnService.OrderReturnProgress returnProgress, AgentWorkbenchService.Address customerAddress) { }
    public record Detail(WorkbenchOrder order, List<AgentWorkbenchService.Card> cards, List<AgentWorkbenchService.Event> events) { }
    private record Row(long id, String orderNo, String clientName, String phone, String email,
        String statusCode, String intakeStatusCode, int cardCount, Long gradingOrderId, String gradingOrderNo,
        Long batchId, String batchNo, LocalDateTime createdAt, String notes, int partnerOrderVersion,
        String contactName, String addressPhone, String addressLine1, String addressLine2, String city, String region, String postalCode, String country) { }

    private static final String FROM = """
        FROM agent_intake i
        JOIN agent_client c ON c.id=i.client_id AND c.merchant_customer_id=i.merchant_customer_id
        LEFT JOIN grading_order o ON o.id=i.order_id AND o.customer_id=i.merchant_customer_id
        LEFT JOIN merchant_order_batch b ON b.id=i.batch_id AND b.merchant_customer_id=i.merchant_customer_id
        """;
    private static final String ORDER_STATUS="COALESCE("+MerchantOrderPolicy.visibleStatusSql("o")+",i.status_code)";
    private static final String SELECT = """
        SELECT i.id, i.intake_no AS order_no,
            COALESCE(i.client_name_snapshot,c.display_name) AS client_name,
            CASE WHEN i.partner_order_version>0 THEN i.client_phone_snapshot ELSE c.phone END AS phone,
            CASE WHEN i.partner_order_version>0 THEN i.client_email_snapshot ELSE c.email END AS email,
        """+ORDER_STATUS+"""
            AS status_code, i.status_code AS intake_status_code,
            i.expected_card_count AS card_count, o.id AS grading_order_id, o.order_no AS grading_order_no,
            b.id AS batch_id,b.batch_no,i.created_at,i.notes,i.partner_order_version,
            c.contact_name,c.phone AS address_phone,c.address_line1,c.address_line2,c.city,c.region,c.postal_code,c.country
        """;

    public AgentWorkbenchService.Page<WorkbenchOrder> list(long owner, int page, int pageSize, String query, String statusCode) {
        fulfillment.requireMerchant(owner);
        int safePage = Math.max(1, page), safeSize = Math.min(100, Math.max(1, pageSize));
        Map<String,Object> params = params("owner", owner);
        String where = " WHERE i.merchant_customer_id=:owner";
        String search = optional(query, "Search", 255), status = optional(statusCode, "Status", 32);
        if (status != null) { where += " AND "+ORDER_STATUS+"=:status"; params.put("status", status); }
        if (search != null) {
            where += """
                 AND (LOWER(i.intake_no) LIKE :query ESCAPE '!'
                   OR LOWER(COALESCE(i.client_name_snapshot,c.display_name)) LIKE :query ESCAPE '!'
                   OR LOWER(COALESCE(o.order_no,'')) LIKE :query ESCAPE '!'
                   OR LOWER(COALESCE(b.batch_no,'')) LIKE :query ESCAPE '!'
                   OR LOWER(COALESCE(CASE WHEN i.partner_order_version>0 THEN i.client_phone_snapshot ELSE c.phone END,'')) LIKE :query ESCAPE '!'
                   OR LOWER(COALESCE(CASE WHEN i.partner_order_version>0 THEN i.client_email_snapshot ELSE c.email END,'')) LIKE :query ESCAPE '!'
                   OR EXISTS (SELECT 1 FROM agent_card ac WHERE ac.intake_id=i.id AND ac.merchant_customer_id=i.merchant_customer_id
                       AND (LOWER(ac.card_name) LIKE :query ESCAPE '!' OR LOWER(COALESCE(ac.official_card_number,'')) LIKE :query ESCAPE '!')))
                """;
            params.put("query", "%" + search.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        }
        long total = jdbc.sql("SELECT COUNT(*) " + FROM + where).params(params).query(Long.class).single();
        params.put("limit",safeSize); params.put("offset",((long)safePage-1)*safeSize);
        List<Row> rows = jdbc.sql(SELECT+FROM+where+" ORDER BY i.id DESC LIMIT :limit OFFSET :offset")
            .params(params).query(Row.class).list();
        List<Long> gradingIds=rows.stream().map(Row::gradingOrderId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long,PartnerReturnService.OrderReturnProgress> progress=gradingIds.isEmpty()?Map.of():returns.progress(gradingIds);
        return new AgentWorkbenchService.Page<>(rows.stream().map(row->view(row,
            row.gradingOrderId()==null?null:progress.get(row.gradingOrderId()))).toList(),total,safePage,safeSize);
    }

    public Detail detail(long owner, long id) {
        fulfillment.requireMerchant(owner);
        Row row = jdbc.sql(SELECT+FROM+" WHERE i.merchant_customer_id=:owner AND i.id=:id")
            .params(params("owner",owner,"id",id)).query(Row.class).optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found"));
        AgentWorkbenchService.IntakeDetail detail = workbench.intakeDetail(owner,id);
        return new Detail(view(row,row.gradingOrderId()==null?null:returns.progress(row.gradingOrderId())),detail.cards(),detail.events());
    }

    private WorkbenchOrder view(Row row,PartnerReturnService.OrderReturnProgress savedProgress) {
        var progress=row.gradingOrderId()==null?new PartnerReturnService.OrderReturnProgress(row.cardCount(),0,0,0,0):savedProgress;
        if(progress==null) throw new IllegalStateException("Order return progress is unavailable");
        return new WorkbenchOrder(row.id(),row.orderNo(),row.clientName(),row.phone(),row.email(),
            row.statusCode(),row.intakeStatusCode(),row.cardCount(),row.gradingOrderId(),row.gradingOrderNo(),
            row.batchId(),row.batchNo(),row.createdAt(),row.notes(),
            "ready".equals(row.intakeStatusCode()) && row.gradingOrderId()==null,row.partnerOrderVersion()==0,
            progress.gradedCardCount(),progress.returningCardCount(),progress.deliveredCardCount(),progress.returnableCardCount(),progress,
            new AgentWorkbenchService.Address(row.contactName(),row.addressPhone(),row.addressLine1(),row.addressLine2(),
                row.city(),row.region(),row.postalCode(),row.country()));
    }

    public Options options(long owner) {
        fulfillment.requireMerchant(owner);
        List<Option> brands = jdbc.sql("SELECT name FROM brand_settings WHERE is_active=1 ORDER BY sort_order,name")
            .query((rs,n)->new Option(rs.getString("name"),rs.getString("name"))).list();
        List<Option> products = dictionary("nxr_product_type").stream()
            .filter(option -> Set.of(ProductTypePolicy.GRADED_CARD,ProductTypePolicy.VINTAGE_PRODUCT).contains(option.value()))
            .toList();
        return new Options(brands,dictionary("nxr_card_category"),dictionary("nxr_language"),products,
            Math.min(1000,Math.max(1,admission.maxCardsPerOrder())));
    }

    private List<Option> dictionary(String type) {
        return jdbc.sql("SELECT dict_value,dict_label FROM sys_dict_data WHERE dict_type=:type AND status='0' ORDER BY dict_sort,dict_code")
            .param("type",type).query((rs,n)->new Option(rs.getString("dict_value"),rs.getString("dict_label"))).list();
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Detail create(long owner, CreateRequest request) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Order creation requires a transaction");
        fulfillment.requireMerchant(owner);
        // Use the same per-partner lock as all existing intake and submission
        // writes, so retries cannot create duplicate customers or claim photos twice.
        jdbc.sql("SELECT id FROM customer_account WHERE id=:owner FOR UPDATE").param("owner",owner).query(Long.class).single();
        if (request==null) throw bad("Order details are required");
        String key=required(request.requestKey(),"Request key",128), hash=hash(request);
        var previous=jdbc.sql("SELECT id,request_hash,partner_order_version FROM agent_intake WHERE merchant_customer_id=:owner AND request_key=:key")
            .params(params("owner",owner,"key",key)).query().listOfRows();
        if(!previous.isEmpty()) {
            var row=previous.get(0);
            if(!hash.equals(row.get("request_hash")) || ((Number)row.get("partner_order_version")).intValue()!=1)
                throw conflict("This request key was already used for different order details");
            return detail(owner,((Number)row.get("id")).longValue());
        }
        String name=required(request.clientName(),"Customer name",128);
        String phone=optional(request.phone(),"Phone",64), email=optional(request.email(),"Email",191);
        if(phone==null && email==null) throw bad("Enter a customer phone number or email address");
        if(email!=null && !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw bad("Enter a valid email address");
        AgentWorkbenchService.Address address=customerAddress(request.customerAddress(),name,phone);
        String notes=optional(request.notes(),"Notes",2000);
        Options options=options(owner);
        if(request.cards()==null || request.cards().isEmpty() || request.cards().size()>options.maxCardsPerOrder())
            throw bad("An order must contain between 1 and "+options.maxCardsPerOrder()+" cards");
        List<CardInput> cards=new ArrayList<>(); Set<Long> photoIds=new HashSet<>();
        for(CardInput card:request.cards()) {
            if(card==null) throw bad("Card details are required");
            if(card.frontPhotoId()==null || card.frontPhotoId()<1 || card.backPhotoId()==null || card.backPhotoId()<1)
                throw bad("Every card requires front and back photos");
            if(!photoIds.add(card.frontPhotoId()) || !photoIds.add(card.backPhotoId()))
                throw bad("Use a different uploaded photo for every card side");
            cards.add(new CardInput(required(card.cardName(),"Card name",255),
                controlled(options.brands(),card.brandName(),"Brand"),required(card.setName(),"Card set",255),
                required(card.officialCardNumber(),"Official card number",128),controlled(options.languages(),card.languageCode(),"Language"),
                controlled(options.productTypes(),card.productType(),"Product type"),controlled(options.categories(),card.category(),"Category"),
                card.frontPhotoId(),card.backPhotoId()));
        }
        // Lock in a stable order and keep evidence ownership inside this same
        // transaction. A later failure rolls all preservation flags back.
        for(long photo:photoIds.stream().sorted().toList()) {
            photos.requireOwnedPhoto(owner,photo);
            photos.readOwned(owner,photo,true);
            photos.preserveForAgent(owner,photo);
        }
        String temporary="NEW-"+UUID.randomUUID().toString().replace("-", "");
        long clientId=clientInsert.executeAndReturnKey(params("merchant_customer_id",owner,"reference",temporary,
            "display_name",name,"phone",address.phone(),"email",email,"contact_name",address.contactName(),
            "address_line1",address.addressLine1(),"address_line2",address.addressLine2(),"city",address.city(),
            "region",address.region(),"postal_code",address.postalCode(),"country",address.country(),"active",1)).longValue();
        LocalDateTime now=jdbc.sql("SELECT CURRENT_TIMESTAMP").query((rs,n)->rs.getObject(1,LocalDateTime.class)).single();
        long id=intakeInsert.executeAndReturnKey(params("merchant_customer_id",owner,"intake_no",temporary,
            "client_id",clientId,"expected_card_count",cards.size(),"status_code","ready","notes",notes,
            "request_key",key,"request_hash",hash,"received_at",now,"partner_order_version",1,
            "client_name_snapshot",name,"client_phone_snapshot",phone,"client_email_snapshot",email)).longValue();
        String orderNo="NXR-"+String.format(Locale.ROOT,"%06d",id);
        jdbc.sql("UPDATE agent_intake SET intake_no=:number WHERE id=:id AND merchant_customer_id=:owner")
            .params(params("number",orderNo,"id",id,"owner",owner)).update();
        jdbc.sql("UPDATE agent_client SET reference=:number WHERE id=:id AND merchant_customer_id=:owner")
            .params(params("number",orderNo,"id",clientId,"owner",owner)).update();
        for(CardInput card:cards) {
            cardInsert.execute(params("merchant_customer_id",owner,"intake_id",id,
                "inventory_code","AC"+UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT),
                "official_card_number",card.officialCardNumber(),"card_name",card.cardName(),"language_code",card.languageCode(),
                "brand_name",card.brandName(),"set_name",card.setName(),"product_type",card.productType(),"category",card.category(),
                "status_code","in_stock","checked_in_at",now,"front_photo_id",card.frontPhotoId(),"back_photo_id",card.backPhotoId()));
        }
        jdbc.sql("INSERT INTO agent_event(merchant_customer_id,client_id,intake_id,event_code,note) VALUES(:owner,:client,:intake,'order_created',:note)")
            .params(params("owner",owner,"client",clientId,"intake",id,"note",notes)).update();
        return detail(owner,id);
    }

    private static String controlled(List<Option> options,String raw,String label) {
        String value=required(raw,label,255);
        return options.stream().filter(option->option.value().equalsIgnoreCase(value)).map(Option::value).findFirst()
            .orElseThrow(()->bad(label+" must be an active option"));
    }
    private static AgentWorkbenchService.Address customerAddress(AgentWorkbenchService.Address address,String name,String phone) {
        if(address==null) throw bad("Customer address is required");
        String contact=optional(address.contactName(),"Address contact name",128);
        String addressPhone=optional(address.phone(),"Address phone",64);
        // Each new order creates its own client row, preserving the customer's
        // address separately from the partner-owned NXR return address book.
        return new AgentWorkbenchService.Address(contact==null?name:contact,addressPhone==null?phone:addressPhone,
            required(address.addressLine1(),"Customer street address",255),optional(address.addressLine2(),"Customer address line 2",255),
            required(address.city(),"Customer city",128),optional(address.region(),"Customer region",128),
            optional(address.postalCode(),"Customer postal code",64),required(address.country(),"Customer country",128));
    }
    private String hash(CreateRequest request) {
        try {
            var body=json.valueToTree(request);
            if(request.customerAddress()==null)((com.fasterxml.jackson.databind.node.ObjectNode)body).remove("customerAddress");
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(("workbench-order-v1:"+json.writeValueAsString(body)).getBytes(StandardCharsets.UTF_8))); }
        catch(JsonProcessingException|java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("Cannot fingerprint order",e); }
    }
    private static String optional(String value,String label,int max) {
        if(value==null || value.isBlank()) return null;
        String normalized=value.strip();
        if(normalized.length()>max) throw bad(label+" is too long");
        return normalized;
    }
    private static String required(String value,String label,int max) {
        String normalized=optional(value,label,max);
        if(normalized==null) throw bad(label+" is required");
        return normalized;
    }
    private static Map<String,Object> params(Object... pairs) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(int i=0;i<pairs.length;i+=2) result.put((String)pairs[i],pairs[i+1]);
        return result;
    }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
}
