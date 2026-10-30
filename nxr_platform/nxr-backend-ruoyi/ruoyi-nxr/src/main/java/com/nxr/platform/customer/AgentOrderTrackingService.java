package com.nxr.platform.customer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Read-only customer projection, bound to the original partner order rather than its current submission. */
@Service
public class AgentOrderTrackingService {
    private static final SecureRandom RANDOM=new SecureRandom();
    private final JdbcClient jdbc;
    private final MerchantBatchService batches;
    private final CustomerOrderPhotoService photos;
    @Value("${nxr.public-site.base-url:https://nxrgrading.com}") private String publicSiteBaseUrl="https://nxrgrading.com";
    public AgentOrderTrackingService(JdbcClient jdbc,MerchantBatchService batches,CustomerOrderPhotoService photos){this.jdbc=jdbc;this.batches=batches;this.photos=photos;}

    public record State(boolean active,Long version,boolean legacyActive) {}
    public record CreateRequest(String clientOrigin) {}
    public record ExpectedVersion(Long expectedVersion,String clientOrigin) {}
    public record Link(String trackingUrl) {}
    public record Card(long id,String cardName,String brandName,String setName,String cardNumber,String languageCode,
                       String productType,String category,String statusCode,String certificateId,boolean hasFrontPhoto,boolean hasBackPhoto) {}
    private record Order(long id,long owner,String number,String status,Long gradingOrderId,LocalDateTime createdAt,LocalDateTime updatedAt) {}
    private record Active(long id) {}

    private Order owned(long owner,long id,boolean lock) {
        if(lock)jdbc.sql("SELECT id FROM customer_account WHERE id=:owner FOR UPDATE").param("owner",owner).query(Long.class).optional().orElseThrow(AgentOrderTrackingService::missing);
        return jdbc.sql("SELECT id,merchant_customer_id,intake_no,status_code,order_id,created_at,updated_at FROM agent_intake WHERE id=:id AND merchant_customer_id=:owner"+(lock?" FOR UPDATE":""))
            .param("id",id).param("owner",owner).query((rs,n)->new Order(rs.getLong("id"),rs.getLong("merchant_customer_id"),rs.getString("intake_no"),rs.getString("status_code"),rs.getObject("order_id",Long.class),rs.getObject("created_at",LocalDateTime.class),rs.getObject("updated_at",LocalDateTime.class)))
            .optional().orElseThrow(AgentOrderTrackingService::missing);
    }
    private Optional<Active> active(long owner,long id) {
        return jdbc.sql("SELECT id FROM agent_order_tracking_token WHERE intake_id=:id AND merchant_customer_id=:owner AND status_code='active' ORDER BY id DESC LIMIT 1")
            .param("id",id).param("owner",owner).query((rs,n)->new Active(rs.getLong(1))).optional();
    }
    private boolean legacyActive(long owner,long id) {
        return jdbc.sql("""
            SELECT COUNT(*) FROM merchant_batch_tracking_token t
            JOIN merchant_order_batch_item bi ON bi.id=t.batch_item_id
            JOIN merchant_order_batch b ON b.id=bi.batch_id
            JOIN agent_intake i ON i.id=:id AND i.merchant_customer_id=:owner
              AND b.merchant_customer_id=i.merchant_customer_id
              AND (bi.order_id=i.order_id OR CAST(bi.intake_no AS CHAR(128))=CAST(i.intake_no AS CHAR(128)) OR CAST(bi.client_reference AS CHAR(128))=CAST(i.intake_no AS CHAR(128)))
            WHERE t.status_code='active' AND t.revoked_at IS NULL
            """).param("id",id).param("owner",owner).query(Integer.class).single()>0;
    }
    public State state(long owner,long id){owned(owner,id,false);var current=active(owner,id);return new State(current.isPresent(),current.map(Active::id).orElse(null),legacyActive(owner,id));}
    public MerchantBatchService.BatchDetail withWorkbenchOrderIds(long owner,MerchantBatchService.BatchDetail batch){
        var rows=batch.orders().stream().map(row -> {
            Long id=jdbc.sql("SELECT id FROM agent_intake WHERE merchant_customer_id=:owner AND order_id=:order AND batch_id=:batch")
                .param("owner",owner).param("order",row.orderId()).param("batch",batch.id()).query(Long.class).optional().orElse(null);
            return row.withWorkbenchOrderId(id);
        }).toList();return batch.withOrders(rows);
    }
    @Transactional public Link create(long owner,long id,CreateRequest request,String requestHost) {
        validateClientAddress(request==null?null:request.clientOrigin(),requestHost);
        owned(owner,id,true);if(active(owner,id).isPresent())throw conflict("A customer link already exists; refresh before replacing it");return issue(owner,id);
    }
    @Transactional public Link replace(long owner,long id,ExpectedVersion request,String requestHost) {
        validateClientAddress(request==null?null:request.clientOrigin(),requestHost);
        owned(owner,id,true);requireVersion(owner,id,request);revokeActive(owner,id);return issue(owner,id);
    }
    @Transactional public void revoke(long owner,long id,ExpectedVersion request) {
        owned(owner,id,true);requireVersion(owner,id,request);revokeActive(owner,id);
    }
    @Transactional public void revokeLegacy(long owner,long id) {
        owned(owner,id,true);
        jdbc.sql("""
            UPDATE merchant_batch_tracking_token SET status_code='revoked',revoked_at=CURRENT_TIMESTAMP
            WHERE status_code='active' AND batch_item_id IN (
              SELECT bi.id FROM merchant_order_batch_item bi JOIN merchant_order_batch b ON b.id=bi.batch_id
              JOIN agent_intake i ON i.id=:id AND i.merchant_customer_id=:owner
                AND b.merchant_customer_id=i.merchant_customer_id
                AND (bi.order_id=i.order_id OR CAST(bi.intake_no AS CHAR(128))=CAST(i.intake_no AS CHAR(128)) OR CAST(bi.client_reference AS CHAR(128))=CAST(i.intake_no AS CHAR(128)))
            )
            """).param("id",id).param("owner",owner).update();
    }
    private void requireVersion(long owner,long id,ExpectedVersion request){
        Active current=active(owner,id).orElseThrow(()->conflict("Customer link changed; refresh before continuing"));
        if(request==null || request.expectedVersion()==null || current.id()!=request.expectedVersion())throw conflict("Customer link changed; refresh before continuing");
    }
    private void revokeActive(long owner,long id){jdbc.sql("UPDATE agent_order_tracking_token SET status_code='revoked',revoked_at=CURRENT_TIMESTAMP WHERE intake_id=:id AND merchant_customer_id=:owner AND status_code='active'").param("id",id).param("owner",owner).update();}
    private Link issue(long owner,long id){String base=validatedPublicBase();byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);String raw=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.sql("INSERT INTO agent_order_tracking_token(intake_id,merchant_customer_id,token_hash,status_code) VALUES(:id,:owner,:hash,'active')")
            .param("id",id).param("owner",owner).param("hash",hash(raw)).update();return new Link(base+"/track#"+raw);}
    private String validatedPublicBase(){
        try {
            String value=publicSiteBaseUrl==null?"":publicSiteBaseUrl.trim();java.net.URI uri=java.net.URI.create(value);
            if(uri.getScheme()==null || !java.util.Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null)
                throw new IllegalArgumentException();
            String path=uri.normalize().getPath();
            if(path!=null && (path.equals("/x7k9m2q4r8v6c3p1") || path.startsWith("/x7k9m2q4r8v6c3p1/")))throw new IllegalArgumentException();
            return value.replaceAll("/+$","");
        } catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Customer site address is not configured correctly");}
    }
    private void validateClientAddress(String clientOrigin,String requestHost){
        String base=validatedPublicBase();java.net.URI site=java.net.URI.create(base);
        try {
            java.net.URI client=java.net.URI.create(clientOrigin==null?"":clientOrigin);
            if(client.getScheme()==null || !java.util.Set.of("http","https").contains(client.getScheme()) || client.getHost()==null || client.getUserInfo()!=null || client.getRawQuery()!=null || client.getRawFragment()!=null || !(client.getPath()==null || client.getPath().isEmpty() || client.getPath().equals("/"))) throw new IllegalArgumentException();
            if(loopback(site.getHost()) && (!loopback(client.getHost()) || !loopback(requestHost)))throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Customer site address is not reachable from this device");
        } catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Browser address is invalid");}
    }
    private static boolean loopback(String host){if(host==null)return false;String value=host.toLowerCase(java.util.Locale.ROOT);return value.equals("localhost")||value.endsWith(".localhost")||value.equals("::1")||value.equals("[::1]")||value.startsWith("127.");}
    private static String hash(String token){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    public Optional<MerchantBatchService.PublicTrackingResponse> publicView(String token){
        Optional<Order> resolved=resolve(token);if(resolved.isEmpty())return Optional.empty();Order order=resolved.get();
        List<Card> cards=agentCards(order);
        if(order.gradingOrderId()!=null){var current=batches.publicTrackingForOrder(order.gradingOrderId());return Optional.of(new MerchantBatchService.PublicTrackingResponse(order.number(),order.number(),current.statusCode(),current.admissionStatus(),cards.size(),order.createdAt(),order.updatedAt(),current.timeline(),current.shipments(),current.returnParcels(),cards));}
        List<MerchantBatchService.PublicTimelineEvent> timeline=jdbc.sql("SELECT event_code,created_at FROM agent_event WHERE merchant_customer_id=:owner AND intake_id=:id AND event_code IN ('order_created','intake_order_received') ORDER BY created_at,id")
            .param("id",order.id()).param("owner",order.owner()).query((rs,n)->new MerchantBatchService.PublicTimelineEvent(rs.getString("event_code"),"intake_order_received".equals(rs.getString("event_code"))?"Intake received":"Order created",order.status(),rs.getObject("created_at",LocalDateTime.class))).list();
        return Optional.of(new MerchantBatchService.PublicTrackingResponse(order.number(),order.number(),order.status(),null,cards.size(),order.createdAt(),order.updatedAt(),timeline,List.of(),List.of(),cards));
    }
    private Optional<Order> resolve(String token){if(token==null || !token.matches("[A-Za-z0-9_-]{43}"))return Optional.empty();
        return jdbc.sql("SELECT i.id,i.merchant_customer_id,i.intake_no,i.status_code,i.order_id,i.created_at,i.updated_at FROM agent_order_tracking_token t JOIN agent_intake i ON i.id=t.intake_id AND i.merchant_customer_id=t.merchant_customer_id WHERE t.token_hash=:hash AND t.status_code='active' AND t.revoked_at IS NULL")
            .param("hash",hash(token)).query((rs,n)->new Order(rs.getLong("id"),rs.getLong("merchant_customer_id"),rs.getString("intake_no"),rs.getString("status_code"),rs.getObject("order_id",Long.class),rs.getObject("created_at",LocalDateTime.class),rs.getObject("updated_at",LocalDateTime.class))).optional();}
    private List<Card> agentCards(Order order){return jdbc.sql("""
        SELECT c.id,c.card_name,c.brand_name,c.set_name,c.official_card_number,c.language_code,c.product_type,c.category,
               COALESCE(oi.status_code,c.status_code) AS card_status,
               CASE WHEN gs.status_code IN ('approved','published') THEN gs.cert_id ELSE NULL END AS cert_id,
               c.front_photo_id,c.back_photo_id
        FROM agent_card c LEFT JOIN grading_order_item oi ON oi.id=c.order_item_id AND oi.order_id=:orderId
        LEFT JOIN grading_submission gs ON gs.id=oi.grading_submission_id
        WHERE c.intake_id=:id AND c.merchant_customer_id=:owner ORDER BY c.id
        """).param("id",order.id()).param("owner",order.owner()).param("orderId",order.gradingOrderId()).query((rs,n)->card(rs)).list();}
    public List<Card> legacyCards(String token){long orderId=batches.resolveTrackingOrder(token);return jdbc.sql("""
        SELECT i.id,i.card_name,i.brand_name,i.set_name,i.card_number AS official_card_number,i.language_code,i.product_type,i.category,
               i.status_code AS card_status,CASE WHEN s.status_code IN ('approved','published') THEN s.cert_id ELSE NULL END AS cert_id,
               i.front_photo_id,i.back_photo_id FROM grading_order_item i LEFT JOIN grading_submission s ON s.id=i.grading_submission_id
        WHERE i.order_id=:id ORDER BY i.item_no,i.id
        """).param("id",orderId).query((rs,n)->card(rs)).list();}
    private static Card card(java.sql.ResultSet rs)throws java.sql.SQLException{return new Card(rs.getLong("id"),rs.getString("card_name"),rs.getString("brand_name"),rs.getString("set_name"),rs.getString("official_card_number"),rs.getString("language_code"),rs.getString("product_type"),rs.getString("category"),rs.getString("card_status"),rs.getString("cert_id"),rs.getObject("front_photo_id")!=null,rs.getObject("back_photo_id")!=null);}
    public Resource photo(String token,long cardId,String side){
        if(token==null || !token.matches("[A-Za-z0-9_-]{43}"))throw missing();
        if(!"front".equals(side) && !"back".equals(side))throw missing();
        String column="front".equals(side)?"front_photo_id":"back_photo_id";
        Optional<Order> order=resolve(token);
        if(order.isPresent()){
            Order value=order.get();Long id=jdbc.sql("SELECT "+column+" FROM agent_card WHERE id=:card AND intake_id=:intake AND merchant_customer_id=:owner")
                .param("card",cardId).param("intake",value.id()).param("owner",value.owner()).query(Long.class).optional().orElseThrow(AgentOrderTrackingService::missing);
            return photos.readOwned(value.owner(),id,false);
        }
        long orderId=batches.resolveTrackingOrder(token);
        Long photoId=jdbc.sql("SELECT "+column+" FROM grading_order_item WHERE id=:card AND order_id=:order")
            .param("card",cardId).param("order",orderId).query(Long.class).optional().orElseThrow(AgentOrderTrackingService::missing);
        return photos.readAttached(orderId,photoId);
    }
    private static ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"Private tracking link or image not found");}
    private static ResponseStatusException conflict(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}
}
