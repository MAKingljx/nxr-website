package com.nxr.platform.commerce;

import com.nxr.platform.admin.AdminSubmissionService;
import com.nxr.platform.customer.CustomerPortalService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Creates a grading record from an authorized order item and inherits its locked routing. */
@Service
public class ScopedOrderSubmissionService {
    private final JdbcClient jdbcClient;
    private final OrderAccessScopeService accessScopeService;
    private final AdminSubmissionService submissionService;
    private final CustomerPortalService customerPortalService;

    public ScopedOrderSubmissionService(
        JdbcClient jdbcClient, OrderAccessScopeService accessScopeService, AdminSubmissionService submissionService,
        CustomerPortalService customerPortalService
    ) {
        this.jdbcClient = jdbcClient;
        this.accessScopeService = accessScopeService;
        this.submissionService = submissionService;
        this.customerPortalService = customerPortalService;
    }

    @Transactional
    public CreatedOrderSubmission create(
        long userId, long orderId, long itemId, AdminSubmissionService.MutateSubmissionRequest supplied
    ) {
        if (supplied == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Submission details are required");
        accessScopeService.requireAccessibleOrder(userId, orderId);
        LockedItem item = jdbcClient.sql("""
            SELECT o.order_origin_code,o.business_line_id,o.work_center_id,oi.grading_submission_id
            FROM grading_order_item oi JOIN grading_order o ON o.id=oi.order_id
            WHERE o.id=:orderId AND oi.id=:itemId FOR UPDATE
            """).param("orderId", orderId).param("itemId", itemId)
            .query((rs, n) -> new LockedItem(rs.getString("order_origin_code"),
                rs.getObject("business_line_id", Long.class), rs.getObject("work_center_id", Long.class),
                rs.getObject("grading_submission_id", Long.class))).optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order item not found"));
        if (item.submissionId() != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Order item already has a grading record");
        if (!"customer_submission".equals(item.origin()) || item.businessLineId() == null || item.workCenterId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order routing must be configured before grading entry");
        }
        requireOwnedIntakeSession(userId, orderId, itemId);
        AdminSubmissionService.MutateSubmissionRequest request = withActor(supplied, userId);
        AdminSubmissionService.SubmissionDetailResponse created = submissionService.createSubmission(request);
        jdbcClient.sql("""
            UPDATE grading_submission
            SET order_origin_code='customer_submission', business_line_id=:lineId, work_center_id=:centerId
            WHERE id=:submissionId
            """).param("lineId", item.businessLineId()).param("centerId", item.workCenterId())
            .param("submissionId", created.id()).update();
        customerPortalService.linkNewOrderItemSubmission(orderId, itemId, created.id(), userId);
        return new CreatedOrderSubmission(orderId, itemId, created);
    }

    private void requireOwnedIntakeSession(long userId, long orderId, long itemId) {
        WorkbenchLock lock = jdbcClient.sql("""
            SELECT id,locked_by_user_id FROM order_workbench_session
            WHERE order_id=:orderId AND active_order_id=:orderId AND status_code='active'
            FOR UPDATE
            """).param("orderId", orderId)
            .query((rs, n) -> new WorkbenchLock(rs.getLong("id"), rs.getLong("locked_by_user_id")))
            .optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                "Start and lock this order in the workbench before grading entry"));
        if (lock.userId() != userId) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This order is locked by another workbench user");
        }
        int intakeScans = jdbcClient.sql("""
            SELECT COUNT(*) FROM order_physical_item p
            JOIN order_workbench_scan s
              ON s.order_id=p.order_id AND s.physical_item_id=p.id AND s.scan_stage_code='intake'
            WHERE p.order_id=:orderId AND p.order_item_id=:itemId
            """).param("orderId", orderId).param("itemId", itemId).query(Integer.class).single();
        if (intakeScans != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Scan this physical card into the order before grading entry");
        }
    }

    private static AdminSubmissionService.MutateSubmissionRequest withActor(
        AdminSubmissionService.MutateSubmissionRequest r, long userId
    ) {
        return new AdminSubmissionService.MutateSubmissionRequest(r.certId(), r.productType(), r.vintageClassification(),
            r.merchDescription(), r.cardCategory(), r.cardName(), r.movieName(), r.releaseYear(), r.productionCompany(),
            r.filmType(), r.sportsType(), r.groupName(), r.yearLabel(), r.brandName(), r.playerName(), r.varietyName(),
            r.setName(), r.cardNumber(), r.languageCode(), r.populationValue(), r.centeringScore(), r.edgesScore(),
            r.cornersScore(), r.surfaceScore(), r.entryNotes(), userId);
    }

    private record LockedItem(String origin, Long businessLineId, Long workCenterId, Long submissionId) {}
    private record WorkbenchLock(long id, long userId) {}
    public record CreatedOrderSubmission(long orderId, long itemId,
                                         AdminSubmissionService.SubmissionDetailResponse submission) {}
}
