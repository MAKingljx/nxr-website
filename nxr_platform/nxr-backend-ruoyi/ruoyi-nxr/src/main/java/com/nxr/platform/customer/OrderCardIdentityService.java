package com.nxr.platform.customer;

import com.nxr.platform.commerce.OrderAccessScopeService;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** One physical-card identity, with the original customer's custody chain derived from explicit record links. */
@Service
public class OrderCardIdentityService {
    private final JdbcClient jdbc;
    private final OrderWorkbenchService workbench;
    private final OrderAccessScopeService scope;
    public OrderCardIdentityService(JdbcClient jdbc, OrderWorkbenchService workbench, OrderAccessScopeService scope) {
        this.jdbc=jdbc; this.workbench=workbench; this.scope=scope;
    }

    public record CardIdentity(long orderId, String orderNo, long orderItemId, int itemNo, String cardName,
        String receiptCode, String physicalBarcode, String sourceType, String ownerKey, String ownerDisplayName,
        String clientReference, String partnerCompanyName, String batchNo, String returnRoute, String certId) { }
    public record OrderIdentities(long orderId, String orderNo, String sourceType, String partnerCompanyName,
        String ownerDisplayName, String batchNo, String returnRoute, List<CardIdentity> items) { }
    private record OrderOwner(long id, String orderNo, long customerId, String sourceType, String ownerKey,
        String ownerDisplayName, String clientReference, String partnerCompanyName, String batchNo, String returnRoute) { }

    private static final String OWNER_QUERY="""
        SELECT o.id,o.order_no,o.customer_id,
               CASE WHEN b.id IS NOT NULL OR t.id IS NOT NULL THEN 'partner' ELSE 'direct' END AS source_type,
               CASE WHEN cl.id IS NOT NULL THEN CONCAT('partner:',o.customer_id,':client:',cl.id)
                    WHEN b.id IS NOT NULL THEN CONCAT('partner:',o.customer_id,':reference:',bi.client_reference)
                    ELSE CONCAT('customer:',o.customer_id) END AS owner_key,
               CASE WHEN cl.id IS NOT NULL THEN cl.display_name
                    WHEN b.id IS NOT NULL THEN COALESCE(NULLIF(bi.client_display_name,''),bi.client_reference)
                    ELSE c.display_name END AS owner_display_name,
               CASE WHEN cl.id IS NOT NULL THEN cl.reference WHEN b.id IS NOT NULL THEN bi.client_reference
                    ELSE CONCAT('C-',o.customer_id) END AS client_reference,
               CASE WHEN b.id IS NOT NULL OR t.id IS NOT NULL THEN COALESCE(NULLIF(cp.company_name,''),c.display_name)
                    ELSE NULL END AS partner_company_name,
               b.batch_no,
               CASE WHEN b.id IS NOT NULL OR t.id IS NOT NULL THEN 'via_partner' ELSE 'direct_to_customer' END AS return_route
        FROM grading_order o JOIN customer_account c ON c.id=o.customer_id
        LEFT JOIN merchant_order_batch_item bi ON bi.order_id=o.id
        LEFT JOIN merchant_order_batch b ON b.id=bi.batch_id AND b.merchant_customer_id=o.customer_id
        LEFT JOIN merchant_company_profile cp ON cp.customer_id=o.customer_id
        LEFT JOIN agent_intake t ON t.order_id=o.id AND t.merchant_customer_id=o.customer_id
        LEFT JOIN agent_client cl ON cl.id=t.client_id AND cl.merchant_customer_id=o.customer_id
        """;

    public OrderIdentities customer(long customerId, String orderNo) {
        return identities(requireCustomerOrder(customerId,orderNo));
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public OrderIdentities allocateForCustomer(long customerId, String orderNo) {
        OrderOwner order=requireCustomerOrder(customerId,orderNo);
        workbench.allocatePhysicalItems(order.id());
        return identities(order);
    }

    public OrderIdentities admin(long userId, long orderId) {
        requirePlatformScope(userId,orderId);
        return identities(requireOrder(orderId));
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public OrderIdentities allocateForAdmin(long userId, long orderId) {
        requirePlatformScope(userId,orderId);
        OrderOwner order=requireOrder(orderId);
        workbench.allocatePhysicalItems(order.id());
        return identities(order);
    }

    /** Exact opaque-code lookup only. A name or card title is never an ownership credential. */
    public CardIdentity lookup(long userId, String rawCode) {
        requirePlatformUser(userId);
        String code=rawCode==null ? "" : rawCode.strip().toUpperCase(Locale.ROOT);
        if(!code.matches("[A-Z0-9_-]{1,64}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Scan a card receipt code");
        List<Long> itemIds=jdbc.sql("""
            SELECT p.order_item_id FROM order_physical_item p WHERE UPPER(p.barcode)=:code
            UNION
            SELECT a.order_item_id FROM agent_card a
            JOIN agent_intake t ON t.id=a.intake_id AND t.merchant_customer_id=a.merchant_customer_id
            JOIN grading_order_item i ON i.id=a.order_item_id AND i.order_id=t.order_id
            JOIN grading_order o ON o.id=i.order_id AND o.customer_id=a.merchant_customer_id
            WHERE UPPER(a.inventory_code)=:code
            """).param("code",code).query(Long.class).list();
        if(itemIds.isEmpty()) throw notFound();
        if(itemIds.size()!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"Card identity is ambiguous");
        long itemId=itemIds.get(0);
        long orderId=jdbc.sql("SELECT order_id FROM grading_order_item WHERE id=:id").param("id",itemId).query(Long.class).single();
        scope.requireAccessibleOrder(userId,orderId);
        return identities(requireOrder(orderId)).items().stream().filter(i->i.orderItemId()==itemId).findFirst().orElseThrow(OrderCardIdentityService::notFound);
    }

    private OrderOwner requireCustomerOrder(long customerId, String orderNo) {
        if(customerId<=0) throw notFound();
        return jdbc.sql(OWNER_QUERY+" WHERE o.customer_id=:customer AND o.order_no=:orderNo")
            .param("customer",customerId).param("orderNo",orderNo==null?"":orderNo.strip())
            .query(OrderOwner.class).optional().orElseThrow(OrderCardIdentityService::notFound);
    }
    private OrderOwner requireOrder(long orderId) {
        return jdbc.sql(OWNER_QUERY+" WHERE o.id=:id").param("id",orderId).query(OrderOwner.class).optional()
            .orElseThrow(OrderCardIdentityService::notFound);
    }
    private void requirePlatformScope(long userId,long orderId) {
        requirePlatformUser(userId);
        scope.requireAccessibleOrder(userId,orderId);
    }
    // A stale broad RuoYi token cannot promote a bound sub-partner to NXR-wide lookup.
    private void requirePlatformUser(long userId) {
        int active=jdbc.sql("SELECT COUNT(*) FROM sys_user WHERE user_id=:id AND status='0' AND del_flag='0'")
            .param("id",userId).query(Integer.class).single();
        int bound=jdbc.sql("SELECT COUNT(*) FROM agent_operator_binding WHERE sys_user_id=:id")
            .param("id",userId).query(Integer.class).single();
        if(active!=1 || bound!=0) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"NXR order access is required");
    }
    private OrderIdentities identities(OrderOwner order) {
        List<CardIdentity> cards=jdbc.sql("""
            SELECT i.id,i.item_no,i.card_name,p.barcode,a.inventory_code,s.cert_id
            FROM grading_order_item i
            LEFT JOIN order_physical_item p ON p.order_item_id=i.id AND p.order_id=i.order_id
            LEFT JOIN agent_intake t ON t.order_id=i.order_id AND t.merchant_customer_id=:customer
            LEFT JOIN agent_card a ON a.order_item_id=i.id AND a.intake_id=t.id AND a.merchant_customer_id=:customer
            LEFT JOIN grading_submission s ON s.id=i.grading_submission_id
            WHERE i.order_id=:order ORDER BY i.item_no,i.id
            """).param("customer",order.customerId()).param("order",order.id())
            .query((r,n)->new CardIdentity(order.id(),order.orderNo(),r.getLong("id"),r.getInt("item_no"),r.getString("card_name"),
                r.getString("inventory_code")==null?r.getString("barcode"):r.getString("inventory_code"),r.getString("barcode"),
                order.sourceType(),order.ownerKey(),order.ownerDisplayName(),order.clientReference(),order.partnerCompanyName(),
                order.batchNo(),order.returnRoute(),r.getString("cert_id"))).list();
        return new OrderIdentities(order.id(),order.orderNo(),order.sourceType(),order.partnerCompanyName(),
            order.ownerDisplayName(),order.batchNo(),order.returnRoute(),cards);
    }
    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"Card or order not found"); }
}
