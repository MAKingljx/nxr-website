package com.nxr.platform.customer;

import com.nxr.platform.commerce.OrderAccessScopeService;
import com.nxr.platform.commerce.CommercePolicyService;
import com.nxr.platform.notifications.NotificationOutboxService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Merchant master parcels and privacy-preserving end-customer order tracking. */
@Service
public class MerchantBatchService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> BATCH_STATUSES = Set.of(
        "open", "inbound_shipped", "received", "return_shipped", "delivered", "cancelled"
    );

    private final JdbcClient jdbcClient;
    private final CustomerPortalService customerPortalService;
    private final OrderFulfillmentService orderFulfillmentService;
    private final CommercePolicyService commercePolicyService;
    private final NotificationOutboxService notificationOutboxService;
    private final TransactionTemplate batchTransaction;

    public MerchantBatchService(
        JdbcClient jdbcClient,
        CustomerPortalService customerPortalService,
        OrderFulfillmentService orderFulfillmentService,
        CommercePolicyService commercePolicyService,
        PlatformTransactionManager transactionManager
    ) {
        this(jdbcClient, customerPortalService, orderFulfillmentService, commercePolicyService, transactionManager, null);
    }

    @Autowired
    public MerchantBatchService(
        JdbcClient jdbcClient,
        CustomerPortalService customerPortalService,
        OrderFulfillmentService orderFulfillmentService,
        CommercePolicyService commercePolicyService,
        PlatformTransactionManager transactionManager,
        NotificationOutboxService notificationOutboxService
    ) {
        this.jdbcClient = jdbcClient;
        this.customerPortalService = customerPortalService;
        this.orderFulfillmentService = orderFulfillmentService;
        this.commercePolicyService = commercePolicyService;
        this.notificationOutboxService = notificationOutboxService;
        this.batchTransaction = new TransactionTemplate(transactionManager);
        this.batchTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public BatchCreateResult createBatch(long merchantCustomerId, BatchCreateRequest request) {
        orderFulfillmentService.requireMerchant(merchantCustomerId);
        List<BatchOrderRequest> rows = request == null || request.orders() == null ? List.of() : request.orders();
        if (rows.isEmpty() || rows.size() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A merchant batch must contain between 1 and 200 customer orders");
        }
        Set<String> references = new java.util.HashSet<>();
        List<CommercePolicyService.BatchPartRequest> quoteParts = new ArrayList<>();
        BatchRoute batchRoute = null;
        for (BatchOrderRequest row : rows) {
            String reference = requireText(row == null ? null : row.clientReference(), "Client reference", 64)
                .toUpperCase(Locale.ROOT);
            if (!references.add(reference)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Client references must be unique within a merchant batch");
            }
            CustomerPortalService.CreateOrderRequest order = row.order();
            if (order == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Every batch row requires an order");
            }
            BatchRoute rowRoute = resolveBatchRoute(merchantCustomerId, order);
            if (batchRoute == null) {
                batchRoute = rowRoute;
            } else if (!batchRoute.equals(rowRoute)) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Every order in a master parcel must use the same return address, currency and return shipping option"
                );
            }
            quoteParts.add(new CommercePolicyService.BatchPartRequest(reference, requestedCardCount(order)));
        }
        CommercePolicyService.BatchQuoteResult batchQuote = commercePolicyService.quoteBatch(
            merchantCustomerId, batchRoute.country(), batchRoute.currencyCode(), quoteParts, batchRoute.shippingOptionCode()
        );
        HashMap<String, CommercePolicyService.AllocatedBatchQuote> allocations = new HashMap<>();
        batchQuote.allocations().forEach(allocation -> allocations.put(allocation.reference(), allocation));
        String batchNo = newBatchNumber();
        String sourceName = blankToNull(clean(request.sourceName(), 255));
        String batchName = blankToNull(clean(request.batchName(), 191));
        if (batchName == null) {
            batchName = sourceName == null ? batchNo : sourceName;
        }
        String resolvedBatchName = batchName;
        BatchCreateResult result = batchTransaction.execute(status -> createBatchAtomically(
            merchantCustomerId, rows, batchQuote, allocations, batchNo, sourceName, resolvedBatchName
        ));
        if (result == null) {
            throw new IllegalStateException("Merchant batch transaction returned no result");
        }
        return result;
    }

    private BatchCreateResult createBatchAtomically(
        long merchantCustomerId,
        List<BatchOrderRequest> rows,
        CommercePolicyService.BatchQuoteResult batchQuote,
        HashMap<String, CommercePolicyService.AllocatedBatchQuote> allocations,
        String batchNo,
        String sourceName,
        String batchName
    ) {
        jdbcClient.sql(
                """
                INSERT INTO merchant_order_batch
                    (batch_no, merchant_customer_id, batch_name, source_name, total_rows, status_code)
                VALUES (:batchNo, :customerId, :batchName, :sourceName, :totalRows, 'open')
                """
            )
            .param("batchNo", batchNo)
            .param("customerId", merchantCustomerId)
            .param("batchName", batchName)
            .param("sourceName", sourceName)
            .param("totalRows", rows.size())
            .update();
        long batchId = jdbcClient.sql("SELECT id FROM merchant_order_batch WHERE batch_no = :batchNo")
            .param("batchNo", batchNo)
            .query(Long.class)
            .single();

        List<BatchCreateRow> results = new ArrayList<>();
        int rowNo = 1;
        for (BatchOrderRequest row : rows) {
            String clientReference = requireText(row.clientReference(), "Client reference", 64);
            CommercePolicyService.AllocatedBatchQuote allocation = allocations.get(
                clientReference.toUpperCase(Locale.ROOT)
            );
            if (allocation == null) {
                throw new IllegalStateException("Batch quote allocation is missing");
            }
            CustomerPortalService.OrderDetailResponse order = customerPortalService.createOrderWithBatchQuote(
                merchantCustomerId, row.order(), batchQuote.aggregate(), allocation
            );
            long batchItemId = insertBatchItem(batchId, order.id(), rowNo, clientReference, row);
            TrackingToken token = issueToken(batchItemId);
            results.add(new BatchCreateRow(
                rowNo, "accepted", order.id(), order.orderNo(), clientReference,
                token.rawToken(), trackingUrl(token.rawToken()), null
            ));
            rowNo += 1;
        }
        jdbcClient.sql(
                """
                UPDATE merchant_order_batch
                SET accepted_rows = :accepted, rejected_rows = :rejected, updated_at = CURRENT_TIMESTAMP
                WHERE id = :batchId
                """
            )
            .param("accepted", rows.size())
            .param("rejected", 0)
            .param("batchId", batchId)
            .update();
        return new BatchCreateResult(batchId, batchNo, "open", rows.size(), 0, List.copyOf(results));
    }

    public BatchPage listMerchantBatches(long merchantCustomerId, int page, int pageSize) {
        orderFulfillmentService.requireMerchant(merchantCustomerId);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, pageSize));
        int total = jdbcClient.sql("SELECT COUNT(*) FROM merchant_order_batch WHERE merchant_customer_id = :customerId")
            .param("customerId", merchantCustomerId)
            .query(Integer.class)
            .single();
        List<BatchSummary> items = jdbcClient.sql(
                """
                SELECT id, batch_no, batch_name, source_name, status_code, total_rows, accepted_rows, rejected_rows,
                       created_at, updated_at
                FROM merchant_order_batch
                WHERE merchant_customer_id = :customerId
                ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset
                """
            )
            .param("customerId", merchantCustomerId)
            .param("limit", safeSize)
            .param("offset", (safePage - 1) * safeSize)
            .query((rs, rowNum) -> mapSummary(rs))
            .list();
        return new BatchPage(items, total, safePage, safeSize);
    }

    public BatchDetail requireMerchantBatch(long merchantCustomerId, String batchNo) {
        orderFulfillmentService.requireMerchant(merchantCustomerId);
        BatchRow batch = findBatchByNumber(batchNo, merchantCustomerId, false);
        return loadDetail(batch);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TrackingTokenResponse rotateTrackingToken(long merchantCustomerId, String batchNo, String orderNo) {
        BatchItemRow item = lockOwnedBatchItem(merchantCustomerId, batchNo, orderNo);
        revokeTokens(item.id());
        TrackingToken issued = issueToken(item.id());
        return new TrackingTokenResponse(orderNo, issued.rawToken(), trackingUrl(issued.rawToken()));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void revokeTrackingToken(long merchantCustomerId, String batchNo, String orderNo) {
        BatchItemRow item = lockOwnedBatchItem(merchantCustomerId, batchNo, orderNo);
        revokeTokens(item.id());
    }

    public PublicTrackingResponse publicTracking(String rawToken) {
        String tokenHash = hashToken(requireText(rawToken, "Tracking token", 256));
        PublicOrder row = jdbcClient.sql(
                """
                SELECT bi.id AS batch_item_id, bi.client_reference, o.id AS order_id, o.order_no,
                       o.status_code, o.admission_status_code, o.total_card_count, o.created_at, o.updated_at,
                       b.id AS batch_id
                FROM merchant_batch_tracking_token t
                JOIN merchant_order_batch_item bi ON bi.id = t.batch_item_id
                JOIN merchant_order_batch b ON b.id = bi.batch_id
                JOIN grading_order o ON o.id = bi.order_id
                WHERE t.token_hash = :tokenHash AND t.status_code = 'active' AND t.revoked_at IS NULL
                """
            )
            .param("tokenHash", tokenHash)
            .query((rs, rowNum) -> new PublicOrder(
                rs.getLong("batch_item_id"), rs.getString("client_reference"), rs.getLong("order_id"),
                rs.getString("order_no"), rs.getString("status_code"), rs.getString("admission_status_code"),
                rs.getInt("total_card_count"),
                rs.getObject("created_at", LocalDateTime.class), rs.getObject("updated_at", LocalDateTime.class),
                rs.getLong("batch_id")
            ))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Private tracking link is invalid or revoked"));
        List<PublicTimelineEvent> timeline = jdbcClient.sql(
                """
                SELECT event_code, title, status_code, created_at
                FROM order_timeline_event
                WHERE order_id = :orderId AND visible_to_customer = 1
                ORDER BY created_at, id
                """
            )
            .param("orderId", row.orderId())
            .query((rs, rowNum) -> new PublicTimelineEvent(
                rs.getString("event_code"), rs.getString("title"), rs.getString("status_code"),
                rs.getObject("created_at", LocalDateTime.class)
            ))
            .list();
        List<PublicShipment> shipments = new ArrayList<>();
        shipments.addAll(jdbcClient.sql(
                """
                SELECT direction_code, carrier_name, tracking_number, status_code, shipped_at, delivered_at
                FROM order_shipment WHERE order_id = :orderId ORDER BY shipped_at, id
                """
            )
            .param("orderId", row.orderId())
            .query((rs, rowNum) -> mapPublicShipment(rs, "order"))
            .list());
        shipments.addAll(jdbcClient.sql(
                """
                SELECT direction_code, carrier_name, tracking_number, status_code, shipped_at, delivered_at
                FROM merchant_batch_shipment WHERE batch_id = :batchId ORDER BY shipped_at, id
                """
            )
            .param("batchId", row.batchId())
            .query((rs, rowNum) -> mapPublicShipment(rs, "batch"))
            .list());
        return new PublicTrackingResponse(
            row.orderNo(), row.clientReference(), row.statusCode(), row.admissionStatus(), row.totalCardCount(),
            row.createdAt(), row.updatedAt(), timeline, List.copyOf(shipments)
        );
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BatchDetail createInboundShipment(long merchantCustomerId, String batchNo, BatchShipmentRequest request) {
        orderFulfillmentService.requireMerchant(merchantCustomerId);
        BatchRow batch = findBatchByNumber(batchNo, merchantCustomerId, true);
        requireShipmentRequest(request);
        List<BatchOrderState> orders = lockBatchOrders(batch.id());
        if (orders.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This batch has no accepted orders");
        }
        if (orders.stream().anyMatch(order -> !Set.of("awaiting_inbound", "inbound_shipped").contains(order.statusCode()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Every customer order must be paid and ready before the master parcel ships");
        }
        if (hasOpenShipment(batch.id(), "inbound")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This batch already has an active inbound master parcel");
        }
        insertBatchShipment(batch.id(), "inbound", request, "customer", merchantCustomerId, null);
        updateBatchStatus(batch.id(), "inbound_shipped");
        jdbcClient.sql(
                "UPDATE grading_order SET status_code = 'inbound_shipped', updated_at = CURRENT_TIMESTAMP "
                    + "WHERE id IN (SELECT order_id FROM merchant_order_batch_item WHERE batch_id = :batchId) AND status_code = 'awaiting_inbound'"
            )
            .param("batchId", batch.id())
            .update();
        addChildTimeline(batch.id(), "batch_inbound_shipped", "Agent parcel shipped to NXR",
            "Your order is included in the agent's inbound master parcel.", "inbound_shipped");
        return loadDetail(findBatchById(batch.id(), false));
    }

    public BatchPage listAdminBatches(int page, int pageSize, String status, String query) {
        return listAdminBatches(page, pageSize, status, query,
            new OrderAccessScopeService.AccessScope(true, List.of(), List.of()));
    }

    public BatchPage listAdminBatches(
        int page, int pageSize, String status, String query, OrderAccessScopeService.AccessScope scope
    ) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, pageSize));
        String normalizedStatus = blankToNull(clean(status, 32));
        if (normalizedStatus != null) {
            normalizedStatus = normalizedStatus.toLowerCase(Locale.ROOT);
        }
        if (normalizedStatus != null && !BATCH_STATUSES.contains(normalizedStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported batch status");
        }
        String normalizedQuery = blankToNull(clean(query, 191));
        String like = normalizedQuery == null ? null : "%" + normalizedQuery + "%";
        int total = jdbcClient.sql(
                """
                SELECT COUNT(*) FROM merchant_order_batch b
                JOIN customer_account c ON c.id = b.merchant_customer_id
                WHERE (:status IS NULL OR b.status_code = :status)
                  AND (:query IS NULL OR b.batch_no LIKE :query OR b.batch_name LIKE :query OR c.email LIKE :query)
                  AND (:scopeAll = 1 OR (
                    EXISTS (
                      SELECT 1 FROM merchant_order_batch_item abi
                      JOIN grading_order ao ON ao.id = abi.order_id
                      WHERE abi.batch_id = b.id
                        AND ao.business_line_id IN (:lineIds)
                        AND ao.work_center_id IN (:centerIds)
                    )
                    AND NOT EXISTS (
                      SELECT 1 FROM merchant_order_batch_item ibi
                      JOIN grading_order io ON io.id = ibi.order_id
                      WHERE ibi.batch_id = b.id
                        AND (io.business_line_id IS NULL OR io.work_center_id IS NULL
                          OR io.business_line_id NOT IN (:lineIds)
                          OR io.work_center_id NOT IN (:centerIds))
                    )
                  ))
                """
            )
            .param("status", normalizedStatus)
            .param("query", like)
            .param("scopeAll", scope.unrestricted() ? 1 : 0)
            .param("lineIds", scope.safeBusinessLineIds())
            .param("centerIds", scope.safeWorkCenterIds())
            .query(Integer.class)
            .single();
        List<BatchSummary> items = jdbcClient.sql(
                """
                SELECT b.id, b.batch_no, b.batch_name, b.source_name, b.status_code, b.total_rows,
                       b.accepted_rows, b.rejected_rows, b.created_at, b.updated_at
                FROM merchant_order_batch b
                JOIN customer_account c ON c.id = b.merchant_customer_id
                WHERE (:status IS NULL OR b.status_code = :status)
                  AND (:query IS NULL OR b.batch_no LIKE :query OR b.batch_name LIKE :query OR c.email LIKE :query)
                  AND (:scopeAll = 1 OR (
                    EXISTS (
                      SELECT 1 FROM merchant_order_batch_item abi
                      JOIN grading_order ao ON ao.id = abi.order_id
                      WHERE abi.batch_id = b.id
                        AND ao.business_line_id IN (:lineIds)
                        AND ao.work_center_id IN (:centerIds)
                    )
                    AND NOT EXISTS (
                      SELECT 1 FROM merchant_order_batch_item ibi
                      JOIN grading_order io ON io.id = ibi.order_id
                      WHERE ibi.batch_id = b.id
                        AND (io.business_line_id IS NULL OR io.work_center_id IS NULL
                          OR io.business_line_id NOT IN (:lineIds)
                          OR io.work_center_id NOT IN (:centerIds))
                    )
                  ))
                ORDER BY b.created_at DESC, b.id DESC LIMIT :limit OFFSET :offset
                """
            )
            .param("status", normalizedStatus)
            .param("query", like)
            .param("scopeAll", scope.unrestricted() ? 1 : 0)
            .param("lineIds", scope.safeBusinessLineIds())
            .param("centerIds", scope.safeWorkCenterIds())
            .param("limit", safeSize)
            .param("offset", (safePage - 1) * safeSize)
            .query((rs, rowNum) -> mapSummary(rs))
            .list();
        return new BatchPage(items, total, safePage, safeSize);
    }

    public BatchDetail requireAdminBatch(long batchId) {
        return loadDetail(findBatchById(batchId, false));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BatchDetail createOutboundShipment(long batchId, long adminUserId, BatchShipmentRequest request) {
        BatchRow batch = findBatchById(batchId, true);
        requireShipmentRequest(request);
        List<BatchOrderState> orders = lockBatchOrders(batch.id());
        if (orders.isEmpty() || orders.stream().anyMatch(order -> !"completed".equals(order.statusCode()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Every customer order must be completed before batch return shipping");
        }
        if (hasOpenShipment(batch.id(), "outbound")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This batch already has an active outbound master parcel");
        }
        for (BatchOrderState order : orders) {
            orderFulfillmentService.assertOutboundReady(order.orderId());
        }
        insertBatchShipment(batch.id(), "outbound", request, "admin", null, adminUserId);
        updateBatchStatus(batch.id(), "return_shipped");
        jdbcClient.sql(
                "UPDATE grading_order SET status_code = 'return_shipped', updated_at = CURRENT_TIMESTAMP "
                    + "WHERE id IN (SELECT order_id FROM merchant_order_batch_item WHERE batch_id = :batchId) AND status_code = 'completed'"
            )
            .param("batchId", batch.id())
            .update();
        jdbcClient.sql(
                "UPDATE grading_order_item SET status_code = 'return_shipped', updated_at = CURRENT_TIMESTAMP "
                    + "WHERE order_id IN (SELECT order_id FROM merchant_order_batch_item WHERE batch_id = :batchId)"
            )
            .param("batchId", batch.id())
            .update();
        addChildTimeline(batch.id(), "batch_return_shipped", "Agent return parcel shipped",
            "Your graded card is included in the return master parcel to the agent.", "return_shipped");
        enqueueBatchStatus(orders, "return_shipped", "Your graded card is included in the return master parcel to the agent.");
        return loadDetail(findBatchById(batch.id(), false));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BatchDetail markShipmentDelivered(long batchId, long shipmentId, long adminUserId) {
        BatchRow batch = findBatchById(batchId, true);
        BatchShipment shipment = jdbcClient.sql(
                """
                SELECT id, batch_id, direction_code, carrier_name, tracking_number, status_code,
                       shipped_at, delivered_at
                FROM merchant_batch_shipment WHERE id = :shipmentId AND batch_id = :batchId FOR UPDATE
                """
            )
            .param("shipmentId", shipmentId)
            .param("batchId", batch.id())
            .query((rs, rowNum) -> mapShipment(rs))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Batch shipment not found"));
        if ("delivered".equals(shipment.statusCode())) {
            return loadDetail(findBatchById(batch.id(), false));
        }
        jdbcClient.sql(
                "UPDATE merchant_batch_shipment SET status_code = 'delivered', delivered_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE id = :id"
            )
            .param("id", shipment.id())
            .update();
        if ("outbound".equals(shipment.directionCode())) {
            if (!"return_shipped".equals(batch.statusCode())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This batch is not in return shipping");
            }
            List<BatchOrderState> orders = lockBatchOrders(batch.id());
            updateBatchStatus(batch.id(), "delivered");
            jdbcClient.sql(
                    "UPDATE grading_order SET status_code = 'delivered', updated_at = CURRENT_TIMESTAMP "
                        + "WHERE id IN (SELECT order_id FROM merchant_order_batch_item WHERE batch_id = :batchId) AND status_code = 'return_shipped'"
                )
                .param("batchId", batch.id())
                .update();
            jdbcClient.sql(
                    "UPDATE grading_order_item SET status_code = 'delivered', updated_at = CURRENT_TIMESTAMP "
                        + "WHERE order_id IN (SELECT order_id FROM merchant_order_batch_item WHERE batch_id = :batchId) AND status_code = 'return_shipped'"
                )
                .param("batchId", batch.id())
                .update();
            addChildTimeline(batch.id(), "batch_delivered", "Agent return parcel delivered",
                "The agent's return master parcel has been delivered.", "delivered");
            enqueueBatchStatus(orders, "delivered", "The agent's return master parcel has been delivered.");
        } else if ("inbound".equals(shipment.directionCode()) && "inbound_shipped".equals(batch.statusCode())) {
            updateBatchStatus(batch.id(), "received");
        }
        return loadDetail(findBatchById(batch.id(), false));
    }

    private long insertBatchItem(
        long batchId, long orderId, int rowNo, String clientReference, BatchOrderRequest row
    ) {
        jdbcClient.sql(
                """
                INSERT INTO merchant_order_batch_item
                    (batch_id, order_id, row_no, client_reference, client_display_name, client_contact_hint)
                VALUES (:batchId, :orderId, :rowNo, :reference, :displayName, :contactHint)
                """
            )
            .param("batchId", batchId)
            .param("orderId", orderId)
            .param("rowNo", rowNo)
            .param("reference", clientReference)
            .param("displayName", blankToNull(clean(row.clientDisplayName(), 128)))
            .param("contactHint", blankToNull(clean(row.clientContactHint(), 191)))
            .update();
        return jdbcClient.sql("SELECT id FROM merchant_order_batch_item WHERE batch_id = :batchId AND row_no = :rowNo")
            .param("batchId", batchId)
            .param("rowNo", rowNo)
            .query(Long.class)
            .single();
    }

    private TrackingToken issueToken(long batchItemId) {
        for (int attempt = 0; attempt < 5; attempt += 1) {
            String raw = newRawToken();
            try {
                jdbcClient.sql(
                        "INSERT INTO merchant_batch_tracking_token (batch_item_id, token_hash, token_hint, status_code) "
                            + "VALUES (:itemId, :hash, :hint, 'active')"
                    )
                    .param("itemId", batchItemId)
                    .param("hash", hashToken(raw))
                    .param("hint", raw.substring(0, Math.min(10, raw.length())))
                    .update();
                return new TrackingToken(raw);
            } catch (org.springframework.dao.DataIntegrityViolationException exception) {
                if (attempt == 4) {
                    throw exception;
                }
            }
        }
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to create private tracking link");
    }

    private void revokeTokens(long batchItemId) {
        jdbcClient.sql(
                """
                UPDATE merchant_batch_tracking_token
                SET status_code = 'revoked', revoked_at = CURRENT_TIMESTAMP
                WHERE batch_item_id = :itemId AND status_code = 'active'
                """
            )
            .param("itemId", batchItemId)
            .update();
    }

    private BatchItemRow lockOwnedBatchItem(long customerId, String batchNo, String orderNo) {
        return jdbcClient.sql(
                """
                SELECT bi.id, bi.batch_id, bi.order_id, bi.row_no, bi.client_reference,
                       bi.client_display_name, bi.client_contact_hint
                FROM merchant_order_batch_item bi
                JOIN merchant_order_batch b ON b.id = bi.batch_id
                JOIN grading_order o ON o.id = bi.order_id
                WHERE b.merchant_customer_id = :customerId AND UPPER(b.batch_no) = :batchNo AND UPPER(o.order_no) = :orderNo
                FOR UPDATE
                """
            )
            .param("customerId", customerId)
            .param("batchNo", requireText(batchNo, "Batch number", 48).toUpperCase(Locale.ROOT))
            .param("orderNo", requireText(orderNo, "Order number", 40).toUpperCase(Locale.ROOT))
            .query((rs, rowNum) -> mapBatchItem(rs))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Merchant batch order not found"));
    }

    private BatchRow findBatchByNumber(String batchNo, long customerId, boolean lock) {
        return jdbcClient.sql(
                """
                SELECT id, batch_no, merchant_customer_id, batch_name, source_name, status_code,
                       total_rows, accepted_rows, rejected_rows, created_at, updated_at
                FROM merchant_order_batch
                WHERE UPPER(batch_no) = :batchNo AND merchant_customer_id = :customerId
                """ + (lock ? " FOR UPDATE" : "")
            )
            .param("batchNo", requireText(batchNo, "Batch number", 48).toUpperCase(Locale.ROOT))
            .param("customerId", customerId)
            .query((rs, rowNum) -> mapBatch(rs))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Merchant batch not found"));
    }

    private BatchRow findBatchById(long batchId, boolean lock) {
        return jdbcClient.sql(
                """
                SELECT id, batch_no, merchant_customer_id, batch_name, source_name, status_code,
                       total_rows, accepted_rows, rejected_rows, created_at, updated_at
                FROM merchant_order_batch WHERE id = :batchId
                """ + (lock ? " FOR UPDATE" : "")
            )
            .param("batchId", batchId)
            .query((rs, rowNum) -> mapBatch(rs))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Merchant batch not found"));
    }

    private BatchDetail loadDetail(BatchRow batch) {
        List<BatchOrderItem> orders = jdbcClient.sql(
                """
                SELECT bi.id, bi.row_no, bi.client_reference, bi.client_display_name, bi.client_contact_hint,
                       o.id AS order_id, o.order_no, o.status_code, o.admission_status_code, o.total_card_count,
                       (SELECT token_hint FROM merchant_batch_tracking_token t
                        WHERE t.batch_item_id = bi.id AND t.status_code = 'active' AND t.revoked_at IS NULL
                        ORDER BY t.id DESC LIMIT 1) AS token_hint
                FROM merchant_order_batch_item bi
                JOIN grading_order o ON o.id = bi.order_id
                WHERE bi.batch_id = :batchId ORDER BY bi.row_no
                """
            )
            .param("batchId", batch.id())
            .query((rs, rowNum) -> new BatchOrderItem(
                rs.getLong("id"), rs.getInt("row_no"), rs.getLong("order_id"), rs.getString("order_no"),
                rs.getString("client_reference"), rs.getString("client_display_name"), rs.getString("client_contact_hint"),
                rs.getString("status_code"), rs.getString("admission_status_code"),
                rs.getInt("total_card_count"), rs.getString("token_hint")
            ))
            .list();
        List<BatchShipment> shipments = jdbcClient.sql(
                """
                SELECT id, batch_id, direction_code, carrier_name, tracking_number, status_code, shipped_at, delivered_at
                FROM merchant_batch_shipment WHERE batch_id = :batchId ORDER BY shipped_at, id
                """
            )
            .param("batchId", batch.id())
            .query((rs, rowNum) -> mapShipment(rs))
            .list();
        return new BatchDetail(
            batch.id(), batch.batchNo(), batch.batchName(), batch.sourceName(), batch.statusCode(),
            batch.totalRows(), batch.acceptedRows(), batch.rejectedRows(), batch.createdAt(), batch.updatedAt(),
            orders, shipments
        );
    }

    private List<BatchOrderState> lockBatchOrders(long batchId) {
        return jdbcClient.sql(
                """
                SELECT o.id, o.order_no, o.customer_id, o.status_code FROM grading_order o
                JOIN merchant_order_batch_item bi ON bi.order_id = o.id
                WHERE bi.batch_id = :batchId ORDER BY o.id FOR UPDATE
                """
            )
            .param("batchId", batchId)
            .query((rs, rowNum) -> new BatchOrderState(
                rs.getLong("id"), rs.getString("order_no"), rs.getLong("customer_id"), rs.getString("status_code")
            ))
            .list();
    }

    private void enqueueBatchStatus(List<BatchOrderState> orders, String statusCode, String publicMessage) {
        if (notificationOutboxService == null) return;
        for (BatchOrderState order : orders) {
            notificationOutboxService.enqueueOrderStatus(
                order.customerId(), order.orderNo(), statusCode, publicMessage
            );
        }
    }

    private boolean hasOpenShipment(long batchId, String direction) {
        return jdbcClient.sql(
                "SELECT COUNT(*) FROM merchant_batch_shipment WHERE batch_id = :batchId AND direction_code = :direction AND status_code <> 'delivered'"
            )
            .param("batchId", batchId)
            .param("direction", direction)
            .query(Integer.class)
            .single() > 0;
    }

    private void insertBatchShipment(
        long batchId, String direction, BatchShipmentRequest request, String actorType, Long customerId, Long adminUserId
    ) {
        jdbcClient.sql(
                """
                INSERT INTO merchant_batch_shipment
                    (batch_id, direction_code, carrier_name, tracking_number, note, created_by_type,
                     created_by_customer_id, created_by_admin_user_id)
                VALUES (:batchId, :direction, :carrier, :tracking, :note, :actorType, :customerId, :adminUserId)
                """
            )
            .param("batchId", batchId)
            .param("direction", direction)
            .param("carrier", requireText(request.carrierName(), "Carrier", 128))
            .param("tracking", requireText(request.trackingNumber(), "Tracking number", 255))
            .param("note", blankToNull(clean(request.note(), 2000)))
            .param("actorType", actorType)
            .param("customerId", customerId)
            .param("adminUserId", adminUserId)
            .update();
    }

    private void updateBatchStatus(long batchId, String status) {
        jdbcClient.sql("UPDATE merchant_order_batch SET status_code = :status, updated_at = CURRENT_TIMESTAMP WHERE id = :batchId")
            .param("status", status)
            .param("batchId", batchId)
            .update();
    }

    private void addChildTimeline(long batchId, String eventCode, String title, String detail, String status) {
        jdbcClient.sql(
                """
                INSERT INTO order_timeline_event
                    (order_id, event_code, title, detail, status_code, visible_to_customer, actor_type_code)
                SELECT bi.order_id, :eventCode, :title, :detail, :status, 1, 'system'
                FROM merchant_order_batch_item bi WHERE bi.batch_id = :batchId
                """
            )
            .param("eventCode", eventCode)
            .param("title", title)
            .param("detail", detail)
            .param("status", status)
            .param("batchId", batchId)
            .update();
    }

    private BatchSummary mapSummary(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new BatchSummary(
            rs.getLong("id"), rs.getString("batch_no"), rs.getString("batch_name"), rs.getString("source_name"),
            rs.getString("status_code"), rs.getInt("total_rows"), rs.getInt("accepted_rows"), rs.getInt("rejected_rows"),
            rs.getObject("created_at", LocalDateTime.class), rs.getObject("updated_at", LocalDateTime.class)
        );
    }

    private BatchRow mapBatch(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new BatchRow(
            rs.getLong("id"), rs.getString("batch_no"), rs.getLong("merchant_customer_id"),
            rs.getString("batch_name"), rs.getString("source_name"), rs.getString("status_code"),
            rs.getInt("total_rows"), rs.getInt("accepted_rows"), rs.getInt("rejected_rows"),
            rs.getObject("created_at", LocalDateTime.class), rs.getObject("updated_at", LocalDateTime.class)
        );
    }

    private BatchItemRow mapBatchItem(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new BatchItemRow(
            rs.getLong("id"), rs.getLong("batch_id"), rs.getLong("order_id"), rs.getInt("row_no"),
            rs.getString("client_reference"), rs.getString("client_display_name"), rs.getString("client_contact_hint")
        );
    }

    private BatchShipment mapShipment(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new BatchShipment(
            rs.getLong("id"), rs.getLong("batch_id"), rs.getString("direction_code"), rs.getString("carrier_name"),
            rs.getString("tracking_number"), rs.getString("status_code"), rs.getObject("shipped_at", LocalDateTime.class),
            rs.getObject("delivered_at", LocalDateTime.class)
        );
    }

    private PublicShipment mapPublicShipment(java.sql.ResultSet rs, String scope) throws java.sql.SQLException {
        return new PublicShipment(
            scope, rs.getString("direction_code"), rs.getString("carrier_name"), rs.getString("tracking_number"),
            rs.getString("status_code"), rs.getObject("shipped_at", LocalDateTime.class),
            rs.getObject("delivered_at", LocalDateTime.class)
        );
    }

    private static void requireShipmentRequest(BatchShipmentRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Batch shipment details are required");
        }
    }

    private static String newBatchNumber() {
        return "MB-" + System.currentTimeMillis() + "-" + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }

    private static String newRawToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hashToken(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private BatchRoute resolveBatchRoute(long customerId, CustomerPortalService.CreateOrderRequest order) {
        String currency = blankToNull(clean(order.currencyCode(), 8));
        currency = currency == null ? "USD" : currency.toUpperCase(Locale.ROOT);
        String shippingOption = blankToNull(clean(order.returnShippingOptionCode(), 32));
        shippingOption = shippingOption == null ? null : shippingOption.toLowerCase(Locale.ROOT);
        if (order.returnAddressId() != null) {
            OrderFulfillmentService.CustomerAddress address = orderFulfillmentService.requireAddress(
                customerId, order.returnAddressId()
            );
            return new BatchRoute(
                requireText(address.country(), "Return country", 128), currency, shippingOption,
                addressFingerprint(
                    address.contactName(), address.contactPhone(), address.addressLine1(), address.addressLine2(),
                    address.city(), address.region(), address.postalCode(), address.country()
                )
            );
        }
        String country = requireText(order.returnCountry(), "Return country", 128);
        return new BatchRoute(
            country, currency, shippingOption,
            addressFingerprint(
                order.contactName(), order.contactPhone(), order.returnAddressLine1(), order.returnAddressLine2(),
                order.returnCity(), order.returnRegion(), order.returnPostalCode(), country
            )
        );
    }

    private static int requestedCardCount(CustomerPortalService.CreateOrderRequest order) {
        if (order.items() != null && !order.items().isEmpty()) {
            return order.items().size();
        }
        if (order.languageGroups() == null || order.languageGroups().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Every batch order requires at least one card");
        }
        int count = 0;
        for (CustomerPortalService.LanguageGroupRequest group : order.languageGroups()) {
            if (group == null || group.quantity() == null || group.quantity() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Batch order card quantities must be positive");
            }
            try {
                count = Math.addExact(count, group.quantity());
            } catch (ArithmeticException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Batch order card count is too large");
            }
        }
        return count;
    }

    private static String addressFingerprint(String... values) {
        StringBuilder fingerprint = new StringBuilder();
        for (String value : values) {
            if (!fingerprint.isEmpty()) fingerprint.append('\u001f');
            fingerprint.append(value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT));
        }
        return fingerprint.toString();
    }

    private static String trackingUrl(String token) {
        return "/merchant-order-status/" + token;
    }

    private static String requireText(String value, String label, int maxLength) {
        String normalized = blankToNull(clean(value, maxLength));
        if (normalized == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " is required");
        }
        return normalized;
    }

    private static String clean(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.substring(0, Math.min(maxLength, normalized.length()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record TrackingToken(String rawToken) {
    }

    private record BatchRow(
        long id, String batchNo, long merchantCustomerId, String batchName, String sourceName, String statusCode,
        int totalRows, int acceptedRows, int rejectedRows, LocalDateTime createdAt, LocalDateTime updatedAt
    ) {
    }

    private record BatchItemRow(
        long id, long batchId, long orderId, int rowNo, String clientReference, String clientDisplayName, String clientContactHint
    ) {
    }

    private record BatchOrderState(long orderId, String orderNo, long customerId, String statusCode) {
    }

    private record PublicOrder(
        long batchItemId, String clientReference, long orderId, String orderNo, String statusCode,
        String admissionStatus, int totalCardCount, LocalDateTime createdAt, LocalDateTime updatedAt, long batchId
    ) {
    }

    private record BatchRoute(
        String country, String currencyCode, String shippingOptionCode, String addressFingerprint
    ) {
    }

    public record BatchCreateRequest(String sourceName, String batchName, List<BatchOrderRequest> orders) {
    }

    public record BatchOrderRequest(
        String clientReference,
        String clientDisplayName,
        String clientContactHint,
        CustomerPortalService.CreateOrderRequest order
    ) {
    }

    public record BatchCreateRow(
        int rowNo, String statusCode, Long orderId, String orderNo, String clientReference,
        String trackingToken, String trackingUrl, String errorMessage
    ) {
    }

    public record BatchCreateResult(
        long batchId, String batchNo, String statusCode, int acceptedRows, int rejectedRows, List<BatchCreateRow> rows
    ) {
    }

    public record BatchSummary(
        long id, String batchNo, String batchName, String sourceName, String statusCode,
        int totalRows, int acceptedRows, int rejectedRows, LocalDateTime createdAt, LocalDateTime updatedAt
    ) {
    }

    public record BatchPage(List<BatchSummary> items, int total, int page, int pageSize) {
    }

    public record BatchOrderItem(
        long batchItemId, int rowNo, long orderId, String orderNo, String clientReference,
        String clientDisplayName, String clientContactHint, String statusCode, String admissionStatus,
        int totalCardCount, String trackingTokenHint
    ) {
    }

    public record BatchShipment(
        long id, long batchId, String directionCode, String carrierName, String trackingNumber,
        String statusCode, LocalDateTime shippedAt, LocalDateTime deliveredAt
    ) {
    }

    public record BatchDetail(
        long id, String batchNo, String batchName, String sourceName, String statusCode,
        int totalRows, int acceptedRows, int rejectedRows, LocalDateTime createdAt, LocalDateTime updatedAt,
        List<BatchOrderItem> orders, List<BatchShipment> shipments
    ) {
    }

    public record TrackingTokenResponse(String orderNo, String trackingToken, String trackingUrl) {
    }

    public record BatchShipmentRequest(String carrierName, String trackingNumber, String note) {
    }

    public record PublicTimelineEvent(
        String eventCode, String title, String statusCode, LocalDateTime createdAt
    ) {
    }

    public record PublicShipment(
        String shipmentScope, String directionCode, String carrierName, String trackingNumber,
        String statusCode, LocalDateTime shippedAt, LocalDateTime deliveredAt
    ) {
    }

    public record PublicTrackingResponse(
        String orderNo, String clientReference, String statusCode, String admissionStatus, int totalCardCount,
        LocalDateTime createdAt, LocalDateTime updatedAt,
        List<PublicTimelineEvent> timeline, List<PublicShipment> shipments
    ) {
    }
}
