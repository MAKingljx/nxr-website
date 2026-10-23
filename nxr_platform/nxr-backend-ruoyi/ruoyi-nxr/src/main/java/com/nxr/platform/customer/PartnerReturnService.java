package com.nxr.platform.customer;

import com.nxr.platform.commerce.OrderAccessScopeService;
import com.nxr.platform.commerce.OrderAccessScopeService.AccessScope;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** NXR-to-partner custody is allocated to individual cards, independently of the original parcel. */
@Service
public class PartnerReturnService {
    private final JdbcClient jdbc;
    private final OrderAccessScopeService access;
    private final OrderWorkbenchService workbench;
    public PartnerReturnService(JdbcClient jdbc,OrderAccessScopeService access,OrderWorkbenchService workbench) {
        this.jdbc=jdbc; this.access=access; this.workbench=workbench;
    }
    private static final String JOINS="""
        FROM grading_order_item i JOIN grading_order o ON o.id=i.order_id
        JOIN customer_account ca ON ca.id=o.customer_id
        JOIN agent_card a ON a.order_item_id=i.id AND a.merchant_customer_id=o.customer_id
        JOIN agent_intake ai ON ai.id=a.intake_id AND ai.order_id=o.id AND ai.merchant_customer_id=o.customer_id
        JOIN merchant_order_batch_item bi ON bi.order_id=o.id
        JOIN merchant_order_batch b ON b.id=bi.batch_id AND b.merchant_customer_id=o.customer_id
        LEFT JOIN grading_submission s ON s.id=i.grading_submission_id
        LEFT JOIN grading_score g ON g.submission_id=s.id
        """;
    private static final String SELECT="""
        SELECT i.id AS order_item_id,a.id AS agent_card_id,o.id AS order_id,o.order_no,ai.intake_no AS order_reference,
        b.id AS batch_id,b.batch_no,o.customer_id AS company_id,i.card_name,i.status_code AS item_status,
        o.status_code AS order_status,o.admission_status_code,o.workbench_required,i.grading_submission_id,
        s.cert_id,s.status_code AS grading_status,s.product_type_code,s.vintage_classification_code,g.final_grade_value,g.final_grade_label,
        o.contact_name,o.contact_phone,o.return_address_line1,o.return_address_line2,o.return_city,o.return_region,o.return_postal_code,o.return_country,
        o.currency_code,o.return_shipping_option_code,ca.display_name AS company_name
        """;
    private static String scopeSql(AccessScope s) {
        return s.unrestricted()?"":" AND o.business_line_id IN (:lines) AND o.work_center_id IN (:centers)";
    }
    private JdbcClient.StatementSpec scope(JdbcClient.StatementSpec q,AccessScope s) {
        return s.unrestricted()?q:q.param("lines",s.safeBusinessLineIds()).param("centers",s.safeWorkCenterIds());
    }
    public List<Company> companies(long userId,String query) {
        AccessScope sc=access.scopeForUser(userId);
        return scope(jdbc.sql("SELECT DISTINCT ca.id,ca.display_name FROM customer_account ca JOIN grading_order o ON o.customer_id=ca.id JOIN agent_intake ai ON ai.order_id=o.id AND ai.merchant_customer_id=ca.id WHERE ca.is_active=1 AND ca.display_name LIKE :q"+scopeSql(sc)+" ORDER BY ca.display_name LIMIT 100").param("q","%"+clean(query)+"%"),sc)
            .query((rs,n)->new Company(rs.getLong(1),rs.getString(2))).list();
    }
    public Page<Card> cards(long userId,int page,int pageSize,Long companyId,Long orderId,Long batchId,String query) {
        AccessScope sc=access.scopeForUser(userId); int size=size(pageSize),p=Math.max(1,page);
        String where=" WHERE (:company IS NULL OR o.customer_id=:company) AND (:orderId IS NULL OR o.id=:orderId) AND (:batch IS NULL OR b.id=:batch) AND (LOWER(o.order_no) LIKE :q ESCAPE '!' OR LOWER(ai.intake_no) LIKE :q ESCAPE '!' OR LOWER(i.card_name) LIKE :q ESCAPE '!' OR LOWER(s.cert_id) LIKE :q ESCAPE '!' OR LOWER(b.batch_no) LIKE :q ESCAPE '!' OR LOWER(ca.display_name) LIKE :q ESCAPE '!') AND o.status_code<>'cancelled'"+scopeSql(sc);
        Map<String,Object> ps=new HashMap<>();ps.put("company",companyId);ps.put("orderId",orderId);ps.put("batch",batchId);ps.put("q",searchPattern(query));
        long total=scope(jdbc.sql("SELECT COUNT(*) "+JOINS+where).params(ps),sc).query(Long.class).single();
        List<Row> rows=scope(jdbc.sql(SELECT+JOINS+where+" ORDER BY o.id DESC,i.item_no LIMIT :limit OFFSET :offset").params(ps).param("limit",size).param("offset",(p-1)*size),sc).query(this::mapRow).list();
        return new Page<>(rows.stream().map(this::card).toList(),total,p,size);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Card check(long userId,long itemId,CheckRequest request) {
        Row row=lockCard(userId,itemId);
        if(request==null || !Boolean.TRUE.equals(request.qualityPassed()) || !Boolean.TRUE.equals(request.packingPassed())) throw bad("Confirm final quality and packing checks");
        String reason=baseBlocked(row); if(reason!=null) throw conflict(reason);
        if(!clean(row.certId()).equalsIgnoreCase(clean(request.certId()))) throw conflict("The physical certificate number does not match this card");
        workbench.assertCardPackingReady(row.orderId(),itemId);
        List<Media> images=media(row.submissionId());
        jdbc.sql("INSERT INTO partner_return_card_check(order_item_id,content_fingerprint,checked_by_user_id,note) VALUES(:id,:fp,:user,:note)")
            .param("id",itemId).param("fp",fingerprint(row,images)).param("user",userId).param("note",text(request.note(),2000)).update();
        jdbc.sql("UPDATE grading_order_item SET status_code='quality_passed',updated_at=CURRENT_TIMESTAMP WHERE id=:id").param("id",itemId).update();
        return card(requireRow(itemId));
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public ParcelDetail create(long userId,CreateRequest request) {
        if(request==null) throw bad("Return parcel details are required");
        List<Long> ids=ids(request.orderItemIds()); String carrier=required(request.carrierName(),"Carrier",128),tracking=required(request.trackingNumber(),"Tracking number",255);
        String key=required(request.requestKey(),"Request key",128);
        List<Row> rows=new ArrayList<>();
        // All return writers lock orders, then items in the same deterministic order.
        List<Long> orderIds=jdbc.sql("SELECT DISTINCT order_id FROM grading_order_item WHERE id IN (:ids) ORDER BY order_id").param("ids",ids).query(Long.class).list();
        for(long orderId:orderIds)access.requireAccessibleOrder(userId,orderId);
        lockBatches(orderIds);
        for(long orderId:orderIds)lockOrder(orderId);
        for(long id:ids){jdbc.sql("SELECT id FROM grading_order_item WHERE id=:id FOR UPDATE").param("id",id).query(Long.class).optional().orElseThrow(()->notFound());rows.add(requireRow(id));}
        Row first=rows.get(0);String requestHash=hash(ids+"|"+carrier+"|"+tracking+"|"+text(request.note(),2000));
        List<Existing> existing=jdbc.sql("SELECT id,request_hash FROM partner_return_parcel WHERE merchant_customer_id=:company AND request_key=:key FOR UPDATE")
            .param("company",first.companyId()).param("key",key).query((rs,n)->new Existing(rs.getLong(1),rs.getString(2))).list();
        if(!existing.isEmpty()) {if(!existing.get(0).hash().equals(requestHash))throw conflict("This request was already used for a different parcel");return adminDetail(userId,existing.get(0).id());}
        for(Row row:rows){
            if(row.companyId()!=first.companyId() || !addressKey(row).equals(addressKey(first))) throw conflict("Combine only cards for the same partner and return address");
            if(!Objects.equals(row.currency(),first.currency()) || !Objects.equals(row.shippingOption(),first.shippingOption())) throw conflict("Cards use different return services or currencies; confirm a shipping change before combining them");
            Card state=card(row);if(!state.eligible())throw conflict(row.orderNo()+": "+state.blockedReason());
            lockGrading(row);
            // Re-read after locking the mutable grading rows, then revalidate every release gate.
            row=requireRow(row.itemId());state=card(row);if(!state.eligible())throw conflict(row.orderNo()+": "+state.blockedReason());
            workbench.assertCardPackingReady(row.orderId(),row.itemId());
        }
        if(jdbc.sql("SELECT COUNT(*) FROM partner_return_parcel WHERE carrier_name=:carrier AND tracking_number=:tracking").param("carrier",carrier).param("tracking",tracking).query(Integer.class).single()>0)
            throw conflict("This tracking number already belongs to a return parcel");
        GeneratedKeyHolder holder=new GeneratedKeyHolder();String parcelNo="NXR-R-"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(Locale.ROOT);
        try { jdbc.sql("""
            INSERT INTO partner_return_parcel(parcel_no,merchant_customer_id,carrier_name,tracking_number,return_address,address_fingerprint,request_key,request_hash,note,created_by_user_id)
            VALUES(:no,:company,:carrier,:tracking,:address,:addressKey,:key,:hash,:note,:user)
            """).param("no",parcelNo).param("company",first.companyId()).param("carrier",carrier).param("tracking",tracking)
            .param("address",address(first)).param("addressKey",addressKey(first)).param("key",key).param("hash",requestHash).param("note",text(request.note(),2000)).param("user",userId).update(holder,"id"); }
        catch(org.springframework.dao.DuplicateKeyException duplicate){throw conflict("A return parcel was created concurrently; refresh before trying again");}
        long parcelId=Objects.requireNonNull(holder.getKey()).longValue();
        for(Row row:rows){Check check=latestCheck(row.itemId());
            jdbc.sql("INSERT INTO partner_return_item(parcel_id,order_item_id,check_id,cert_id,content_fingerprint) VALUES(:parcel,:item,:check,:cert,:fp)")
                .param("parcel",parcelId).param("item",row.itemId()).param("check",check.id()).param("cert",row.certId()).param("fp",check.fingerprint()).update();
            jdbc.sql("UPDATE grading_order_item SET status_code='return_shipped',updated_at=CURRENT_TIMESTAMP WHERE id=:id").param("id",row.itemId()).update();
            jdbc.sql("UPDATE grading_order SET shipping_label_created_at=COALESCE(shipping_label_created_at,CURRENT_TIMESTAMP),updated_at=CURRENT_TIMESTAMP WHERE id=:id").param("id",row.orderId()).update();
        }
        for(long orderId:orderIds){refreshStatus(orderId);timeline(orderId,userId,"partner_return_shipped","Cards shipped to partner",parcelNo);}
        return adminDetail(userId,parcelId);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public ParcelDetail delivered(long userId,long parcelId) {
        ParcelDetail before=adminDetail(userId,parcelId);
        List<Long> orderIds=before.items().stream().map(ParcelItem::orderId).distinct().sorted().toList();
        lockBatches(orderIds);
        for(long orderId:orderIds)lockOrder(orderId);
        String status=jdbc.sql("SELECT status_code FROM partner_return_parcel WHERE id=:id FOR UPDATE").param("id",parcelId).query(String.class).single();
        if("delivered".equals(status))return adminDetail(userId,parcelId);
        if(!"shipped".equals(status))throw conflict("This parcel has not been shipped");
        jdbc.sql("UPDATE partner_return_parcel SET status_code='delivered',delivered_at=CURRENT_TIMESTAMP,delivered_by_user_id=:user WHERE id=:id").param("id",parcelId).param("user",userId).update();
        jdbc.sql("UPDATE grading_order_item SET status_code='delivered',updated_at=CURRENT_TIMESTAMP WHERE id IN (SELECT order_item_id FROM partner_return_item WHERE parcel_id=:id)").param("id",parcelId).update();
        for(long orderId:orderIds){refreshStatus(orderId);timeline(orderId,userId,"partner_return_delivered","Cards delivered to partner",before.parcelNo());}
        return adminDetail(userId,parcelId);
    }
    public Page<Parcel> adminParcels(long userId,int page,int pageSize,Long companyId,Long orderId,Long batchId,String status) {
        return adminParcels(userId,page,pageSize,companyId,orderId,batchId,status,null);
    }
    public Page<Parcel> adminParcels(long userId,int page,int pageSize,Long companyId,Long orderId,Long batchId,String status,String query) {
        return parcels(null,access.scopeForUser(userId),page,pageSize,companyId,orderId,batchId,status,query);
    }
    public Page<Parcel> partnerParcels(long companyId,int page,int pageSize,Long orderId,Long batchId,String status) {
        return parcels(companyId,null,page,pageSize,companyId,orderId,batchId,status,null);
    }
    private Page<Parcel> parcels(Long owner,AccessScope sc,int page,int pageSize,Long companyId,Long orderId,Long batchId,String status,String query) {
        int size=size(pageSize),p=Math.max(1,page);Map<String,Object> ps=new HashMap<>();ps.put("company",companyId);ps.put("orderId",orderId);ps.put("batch",batchId);ps.put("status",clean(status));
        String where=" WHERE (:company IS NULL OR r.merchant_customer_id=:company) AND (:status='' OR r.status_code=:status) AND EXISTS (SELECT 1 FROM partner_return_item ri JOIN grading_order_item i ON i.id=ri.order_item_id JOIN merchant_order_batch_item bi ON bi.order_id=i.order_id WHERE ri.parcel_id=r.id AND (:orderId IS NULL OR i.order_id=:orderId) AND (:batch IS NULL OR bi.batch_id=:batch))";
        if(!clean(query).isEmpty()) {
            ps.put("query",searchPattern(query));
            where += """
                AND (LOWER(r.parcel_no) LIKE :query ESCAPE '!'
                  OR LOWER(r.carrier_name) LIKE :query ESCAPE '!'
                  OR LOWER(r.tracking_number) LIKE :query ESCAPE '!'
                  OR EXISTS (SELECT 1 FROM partner_return_item search_ri
                    JOIN grading_order_item search_i ON search_i.id=search_ri.order_item_id
                    JOIN grading_order search_o ON search_o.id=search_i.order_id
                    JOIN agent_intake search_ai ON search_ai.order_id=search_o.id
                      AND search_ai.merchant_customer_id=r.merchant_customer_id
                    WHERE search_ri.parcel_id=r.id
                      AND (LOWER(search_o.order_no) LIKE :query ESCAPE '!'
                        OR LOWER(search_ai.intake_no) LIKE :query ESCAPE '!')))
                """;
        }
        if(owner==null && !sc.unrestricted()){where+=" AND NOT EXISTS (SELECT 1 FROM partner_return_item ri JOIN grading_order_item i ON i.id=ri.order_item_id JOIN grading_order o ON o.id=i.order_id WHERE ri.parcel_id=r.id AND (o.business_line_id IS NULL OR o.work_center_id IS NULL OR o.business_line_id NOT IN (:lines) OR o.work_center_id NOT IN (:centers)))";ps.put("lines",sc.safeBusinessLineIds());ps.put("centers",sc.safeWorkCenterIds());}
        long total=jdbc.sql("SELECT COUNT(*) FROM partner_return_parcel r"+where).params(ps).query(Long.class).single();
        List<Parcel> items=jdbc.sql("SELECT r.*,(SELECT display_name FROM customer_account ca WHERE ca.id=r.merchant_customer_id) AS company_name,(SELECT COUNT(*) FROM partner_return_item x WHERE x.parcel_id=r.id) AS card_count FROM partner_return_parcel r"+where+" ORDER BY r.id DESC LIMIT :limit OFFSET :offset").params(ps).param("limit",size).param("offset",(p-1)*size).query(this::mapParcel).list();
        return new Page<>(items,total,p,size);
    }
    public ParcelDetail adminDetail(long userId,long parcelId) {
        ParcelDetail detail=detail(parcelId);
        detail.items().stream().map(ParcelItem::orderId).distinct().forEach(id->access.requireAccessibleOrder(userId,id));return detail;
    }
    public ParcelDetail partnerDetail(long owner,long parcelId) {
        ParcelDetail detail=detail(parcelId);if(detail.companyId()!=owner)throw notFound();return detail;
    }
    private ParcelDetail detail(long id) {
        Parcel p=jdbc.sql("SELECT r.*,(SELECT display_name FROM customer_account ca WHERE ca.id=r.merchant_customer_id) AS company_name,(SELECT COUNT(*) FROM partner_return_item x WHERE x.parcel_id=r.id) AS card_count FROM partner_return_parcel r WHERE r.id=:id")
            .param("id",id).query(this::mapParcel).optional().orElseThrow(()->notFound());
        List<ParcelItem> items=jdbc.sql("SELECT i.id,a.id AS agent_card_id,o.id AS order_id,o.order_no,ai.intake_no AS order_reference,b.id AS batch_id,b.batch_no,i.card_name,ri.cert_id,i.status_code "+JOINS+" JOIN partner_return_item ri ON ri.order_item_id=i.id WHERE ri.parcel_id=:id ORDER BY o.id,i.item_no")
            .param("id",id).query((rs,n)->new ParcelItem(rs.getLong("id"),rs.getLong("agent_card_id"),rs.getLong("order_id"),rs.getString("order_no"),rs.getString("order_reference"),rs.getLong("batch_id"),rs.getString("batch_no"),rs.getString("card_name"),rs.getString("cert_id"),rs.getString("status_code"))).list();
        return new ParcelDetail(p.id(),p.parcelNo(),p.companyId(),p.companyName(),p.carrierName(),p.trackingNumber(),p.statusCode(),p.cardCount(),p.shippedAt(),p.deliveredAt(),p.returnAddress(),items);
    }
    public List<PublicParcel> publicParcels(long orderId) {
        List<Long> ids=jdbc.sql("SELECT DISTINCT r.parcel_id FROM partner_return_item r JOIN grading_order_item i ON i.id=r.order_item_id WHERE i.order_id=:id ORDER BY r.parcel_id").param("id",orderId).query(Long.class).list();
        return ids.stream().map(id->{ParcelDetail p=detail(id);List<PublicCard> cards=p.items().stream().filter(i->i.orderId()==orderId).map(i->new PublicCard(i.cardName(),i.certId(),i.itemStatus())).toList();return new PublicParcel(p.parcelNo(),p.carrierName(),p.trackingNumber(),p.statusCode(),p.shippedAt(),p.deliveredAt(),cards);}).toList();
    }
    public OrderReturnProgress progress(long orderId) {
        return progress(List.of(orderId)).getOrDefault(orderId,new OrderReturnProgress(0,0,0,0,null));
    }
    /** List summaries are one aggregate query; release eligibility is checked on the paginated packing screen. */
    public Map<Long,OrderReturnProgress> progress(Collection<Long> orderIds) {
        if(orderIds==null || orderIds.isEmpty())return Map.of();
        if(orderIds.size()>200)throw bad("Too many orders requested");
        Map<Long,OrderReturnProgress> result=new HashMap<>();
        jdbc.sql("""
            SELECT i.order_id,COUNT(*) AS cards,
              SUM(CASE WHEN s.status_code IN ('approved','published') THEN 1 ELSE 0 END) AS graded,
              SUM(CASE WHEN i.status_code='return_shipped' THEN 1 ELSE 0 END) AS returning,
              SUM(CASE WHEN i.status_code='delivered' THEN 1 ELSE 0 END) AS delivered
            FROM grading_order_item i LEFT JOIN grading_submission s ON s.id=i.grading_submission_id
            WHERE i.order_id IN (:ids) AND i.status_code<>'cancelled' GROUP BY i.order_id
            """).param("ids",orderIds).query((rs,n)->{result.put(rs.getLong("order_id"),new OrderReturnProgress(rs.getInt("cards"),rs.getInt("graded"),rs.getInt("returning"),rs.getInt("delivered"),null));return 1;}).list();
        return Map.copyOf(result);
    }
    public void assertLegacyReturnAllowed(long orderId) {
        if(jdbc.sql("SELECT COUNT(*) FROM partner_return_item r JOIN grading_order_item i ON i.id=r.order_item_id WHERE i.order_id=:id").param("id",orderId).query(Integer.class).single()>0)throw conflict("This order uses card-based return parcels; manage delivery from its return parcels");
    }
    public void assertManualTransition(long orderId,String status) {
        if(Set.of("return_shipped","delivered","cancelled","received","intake_exception").contains(clean(status)))assertLegacyReturnAllowed(orderId);
    }
    private Row lockCard(long userId,long id){Row row=requireRow(id);access.requireAccessibleOrder(userId,row.orderId());lockOrder(row.orderId());jdbc.sql("SELECT id FROM grading_order_item WHERE id=:id FOR UPDATE").param("id",id).query(Long.class).single();lockGrading(row);return requireRow(id);}
    private void lockBatches(List<Long> orderIds){
        if(orderIds.isEmpty())return;
        List<Long> batchIds=jdbc.sql("SELECT DISTINCT batch_id FROM merchant_order_batch_item WHERE order_id IN (:ids) ORDER BY batch_id").param("ids",orderIds).query(Long.class).list();
        for(long id:batchIds)jdbc.sql("SELECT id FROM merchant_order_batch WHERE id=:id FOR UPDATE").param("id",id).query(Long.class).single();
    }
    private void lockOrder(long id){jdbc.sql("SELECT id FROM grading_order WHERE id=:id FOR UPDATE").param("id",id).query(Long.class).optional().orElseThrow(()->notFound());}
    private void lockGrading(Row row){if(row.submissionId()!=null){jdbc.sql("SELECT id FROM grading_submission WHERE id=:id FOR UPDATE").param("id",row.submissionId()).query(Long.class).list();jdbc.sql("SELECT submission_id FROM grading_score WHERE submission_id=:id FOR UPDATE").param("id",row.submissionId()).query(Long.class).list();jdbc.sql("SELECT id FROM submission_media WHERE submission_id=:id FOR UPDATE").param("id",row.submissionId()).query(Long.class).list();}}
    private Row requireRow(long id){return jdbc.sql(SELECT+JOINS+" WHERE i.id=:id").param("id",id).query(this::mapRow).optional().orElseThrow(()->notFound());}
    private String baseBlocked(Row row) {
        if(!Set.of("received","grading","review","quality_check","completed").contains(clean(row.orderStatus())))return "Order is not ready for return shipping";
        if(Set.of("return_shipped","delivered","cancelled").contains(clean(row.itemStatus())))return "Card has already left NXR or was cancelled";
        if(jdbc.sql("SELECT COUNT(*) FROM partner_return_item WHERE order_item_id=:id").param("id",row.itemId()).query(Integer.class).single()>0)return "Card already belongs to a return parcel";
        if(jdbc.sql("SELECT COUNT(*) FROM merchant_batch_shipment WHERE batch_id=:id AND direction_code='outbound'").param("id",row.batchId()).query(Integer.class).single()>0)return "Card was included in an existing batch return";
        if(jdbc.sql("SELECT COUNT(*) FROM order_shipment WHERE order_id=:id AND direction_code='outbound'").param("id",row.orderId()).query(Integer.class).single()>0)return "Order already has an outbound shipment";
        if(!"approved".equals(row.admission()))return "Order review must be approved";
        if(jdbc.sql("SELECT COUNT(*) FROM grading_order WHERE id=:id AND terms_accepted_at IS NOT NULL AND accepted_terms_version=approved_terms_version").param("id",row.orderId()).query(Integer.class).single()!=1)return "The approved order terms must be accepted";
        if(jdbc.sql("SELECT COUNT(*) FROM order_exception WHERE order_id=:id AND status_code='open'").param("id",row.orderId()).query(Integer.class).single()>0)return "Resolve the open order exception";
        if(jdbc.sql("SELECT COUNT(*) FROM shipping_change_request WHERE order_id=:id AND status_code='requested'").param("id",row.orderId()).query(Integer.class).single()>0)return "Review the return shipping change before shipping";
        if(row.submissionId()==null || !Set.of("approved","published").contains(clean(row.gradingStatus())) || clean(row.certId()).isEmpty())return "Card grading must be approved";
        if((row.productType()==null || "graded_card".equals(row.productType())) && row.grade()==null)return "Card needs its final grade";
        if("vintage_product".equals(row.productType()) && clean(row.vintage()).isEmpty())return "Card needs its vintage classification";
        if(jdbc.sql("SELECT COUNT(*) FROM grading_order_item WHERE grading_submission_id=:id").param("id",row.submissionId()).query(Integer.class).single()!=1)return "Grading entry is linked to multiple cards";
        if(jdbc.sql("SELECT COUNT(*) FROM order_work_task WHERE order_id=:orderId AND (order_item_id IS NULL OR order_item_id=:itemId) AND task_type_code IN ('preprocess','vision','manual_review','encapsulation') AND status_code<>'completed'").param("orderId",row.orderId()).param("itemId",row.itemId()).query(Integer.class).single()>0)return "Complete this card's grading and encapsulation tasks";
        List<Media> images=media(row.submissionId());if(images.stream().noneMatch(i->"front".equals(i.side())) || images.stream().noneMatch(i->"back".equals(i.side())))return "Front and back card images are required";
        return null;
    }
    private Card card(Row row){String reason=baseBlocked(row);List<Media> images=media(row.submissionId());Check check=latestCheck(row.itemId());boolean checked=check!=null && check.fingerprint().equals(fingerprint(row,images));
        if(reason==null){try{workbench.assertCardPackingReady(row.orderId(),row.itemId());}catch(ResponseStatusException e){reason=e.getReason();}}
        if(reason==null && !checked)reason="Complete the card quality and packing check";
        return new Card(row.itemId(),row.agentCardId(),row.orderId(),row.orderNo(),row.orderReference(),row.batchId(),row.batchNo(),row.companyId(),row.companyName(),row.cardName(),row.certId(),row.itemStatus(),reason==null,reason,checked,address(row),imageUrl(images,"front"),imageUrl(images,"back"));}
    private List<Media> media(Long id){if(id==null)return List.of();return jdbc.sql("SELECT id,media_side_code,storage_key,COALESCE(checksum_sha256,'') AS checksum,COALESCE(public_url,'') AS url FROM submission_media WHERE submission_id=:id AND is_active=1 AND media_stage_code IN ('staged','published') ORDER BY media_side_code,id")
        .param("id",id).query((rs,n)->new Media(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5))).list();}
    private static String imageUrl(List<Media> rows,String side){return rows.stream().filter(i->side.equals(i.side())).map(Media::url).filter(s->!s.isBlank()).reduce((a,b)->b).orElse(null);}
    private Check latestCheck(long id){return jdbc.sql("SELECT id,content_fingerprint FROM partner_return_card_check WHERE order_item_id=:id ORDER BY id DESC LIMIT 1").param("id",id).query((rs,n)->new Check(rs.getLong(1),rs.getString(2))).optional().orElse(null);}
    private static String fingerprint(Row row,List<Media> media){return hash(row.submissionId()+"|"+row.certId()+"|"+row.gradingStatus()+"|"+row.productType()+"|"+row.vintage()+"|"+(row.grade()==null?"":row.grade().stripTrailingZeros().toPlainString())+"|"+row.gradeLabel()+"|"+row.cardName()+"|"+media);}
    private void refreshStatus(long orderId){
        int total=jdbc.sql("SELECT COUNT(*) FROM grading_order_item WHERE order_id=:id AND status_code<>'cancelled'").param("id",orderId).query(Integer.class).single();
        int delivered=jdbc.sql("SELECT COUNT(*) FROM grading_order_item WHERE order_id=:id AND status_code='delivered'").param("id",orderId).query(Integer.class).single();
        int moving=jdbc.sql("SELECT COUNT(*) FROM grading_order_item WHERE order_id=:id AND status_code IN ('return_shipped','delivered')").param("id",orderId).query(Integer.class).single();
        String status=total>0 && delivered==total?"delivered":total>0 && moving==total?"return_shipped":null;
        if(status!=null)jdbc.sql("UPDATE grading_order SET status_code=:status,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND status_code<>'cancelled'").param("id",orderId).param("status",status).update();
        List<Long> batchIds=jdbc.sql("SELECT batch_id FROM merchant_order_batch_item WHERE order_id=:id").param("id",orderId).query(Long.class).list();
        for(long batchId:batchIds){List<String> states=jdbc.sql("SELECT o.status_code FROM merchant_order_batch_item bi JOIN grading_order o ON o.id=bi.order_id WHERE bi.batch_id=:id AND o.status_code<>'cancelled'").param("id",batchId).query(String.class).list();String target=!states.isEmpty() && states.stream().allMatch("delivered"::equals)?"delivered":!states.isEmpty() && states.stream().allMatch(s->Set.of("return_shipped","delivered").contains(s))?"return_shipped":null;if(target!=null)jdbc.sql("UPDATE merchant_order_batch SET status_code=:status,updated_at=CURRENT_TIMESTAMP WHERE id=:id").param("id",batchId).param("status",target).update();}
    }
    private void timeline(long orderId,long userId,String event,String title,String detail){jdbc.sql("INSERT INTO order_timeline_event(order_id,event_code,title,detail,status_code,visible_to_customer,actor_type_code,actor_admin_user_id) SELECT id,:event,:title,:detail,status_code,1,'admin',:user FROM grading_order WHERE id=:id")
        .param("id",orderId).param("event",event).param("title",title).param("detail",detail).param("user",userId).update();}
    private Row mapRow(java.sql.ResultSet rs,int n)throws java.sql.SQLException{return new Row(rs.getLong("order_item_id"),rs.getLong("agent_card_id"),rs.getLong("order_id"),rs.getString("order_no"),rs.getString("order_reference"),rs.getLong("batch_id"),rs.getString("batch_no"),rs.getLong("company_id"),rs.getString("company_name"),rs.getString("card_name"),rs.getString("item_status"),rs.getString("order_status"),rs.getString("admission_status_code"),rs.getObject("grading_submission_id",Long.class),rs.getString("cert_id"),rs.getString("grading_status"),rs.getString("product_type_code"),rs.getString("vintage_classification_code"),rs.getBigDecimal("final_grade_value"),rs.getString("final_grade_label"),List.of(clean(rs.getString("contact_name")),clean(rs.getString("contact_phone")),clean(rs.getString("return_address_line1")),clean(rs.getString("return_address_line2")),clean(rs.getString("return_city")),clean(rs.getString("return_region")),clean(rs.getString("return_postal_code")),clean(rs.getString("return_country"))),rs.getString("currency_code"),rs.getString("return_shipping_option_code"));}
    private Parcel mapParcel(java.sql.ResultSet rs,int n)throws java.sql.SQLException{return new Parcel(rs.getLong("id"),rs.getString("parcel_no"),rs.getLong("merchant_customer_id"),rs.getString("company_name"),rs.getString("carrier_name"),rs.getString("tracking_number"),rs.getString("status_code"),rs.getInt("card_count"),rs.getObject("shipped_at",LocalDateTime.class),rs.getObject("delivered_at",LocalDateTime.class),rs.getString("return_address"));}
    private static String address(Row row){return String.join(", ",row.address().stream().filter(s->!s.isBlank()).toList());}
    private static String addressKey(Row row){return hash(String.join("\u001f",row.address()).toLowerCase(Locale.ROOT));}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static List<Long> ids(List<Long> raw){if(raw==null || raw.isEmpty() || raw.size()>500 || raw.stream().anyMatch(id->id==null || id<=0) || new HashSet<>(raw).size()!=raw.size())throw bad("Select between 1 and 500 different cards");return raw.stream().sorted().toList();}
    private static String clean(String s){return s==null?"":s.trim();}private static String text(String s,int max){s=clean(s);if(s.length()>max)throw bad("Text is too long");return s;}
    private static String required(String s,String name,int max){s=text(s,max);if(s.isBlank())throw bad(name+" is required");return s;}
    private static String searchPattern(String value){return "%"+clean(value).toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";}
    private static int size(int s){return Math.max(1,Math.min(100,s));}
    private static ResponseStatusException bad(String s){return new ResponseStatusException(HttpStatus.BAD_REQUEST,s);}private static ResponseStatusException conflict(String s){return new ResponseStatusException(HttpStatus.CONFLICT,s);}private static ResponseStatusException notFound(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"Return parcel or card not found");}
    private record Existing(long id,String hash){} private record Check(long id,String fingerprint){} private record Media(long id,String side,String key,String checksum,String url){}
    private record Row(long itemId,long agentCardId,long orderId,String orderNo,String orderReference,long batchId,String batchNo,long companyId,String companyName,String cardName,String itemStatus,String orderStatus,String admission,Long submissionId,String certId,String gradingStatus,String productType,String vintage,BigDecimal grade,String gradeLabel,List<String> address,String currency,String shippingOption){}
    public record Company(long id,String displayName){}
    public record CheckRequest(String certId,Boolean qualityPassed,Boolean packingPassed,String note){}
    public record CreateRequest(List<Long> orderItemIds,String carrierName,String trackingNumber,String requestKey,String note){}
    public record Page<T>(List<T> items,long total,int page,int pageSize){}
    public record Card(long orderItemId,long agentCardId,long orderId,String orderNo,String orderReference,long batchId,String batchNo,long companyId,String companyName,String cardName,String certId,String itemStatus,boolean eligible,String blockedReason,boolean checked,String returnAddress,String frontImageUrl,String backImageUrl){}
    public record Parcel(long id,String parcelNo,long companyId,String companyName,String carrierName,String trackingNumber,String statusCode,int cardCount,LocalDateTime shippedAt,LocalDateTime deliveredAt,String returnAddress){}
    public record ParcelItem(long orderItemId,long agentCardId,long orderId,String orderNo,String orderReference,long batchId,String batchNo,String cardName,String certId,String itemStatus){}
    public record ParcelDetail(long id,String parcelNo,long companyId,String companyName,String carrierName,String trackingNumber,String statusCode,int cardCount,LocalDateTime shippedAt,LocalDateTime deliveredAt,String returnAddress,List<ParcelItem> items){}
    public record PublicCard(String cardName,String certId,String statusCode){}
    public record PublicParcel(String parcelNo,String carrierName,String trackingNumber,String statusCode,LocalDateTime shippedAt,LocalDateTime deliveredAt,List<PublicCard> cards){}
    public record OrderReturnProgress(int cardCount,int gradedCardCount,int returningCardCount,int deliveredCardCount,Integer returnableCardCount){}
}
