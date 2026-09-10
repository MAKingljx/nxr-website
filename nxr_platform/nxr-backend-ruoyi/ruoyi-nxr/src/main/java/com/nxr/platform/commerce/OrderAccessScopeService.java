package com.nxr.platform.commerce;

import com.ruoyi.common.utils.SecurityUtils;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Server-side business-line and work-center scope for every staff order read or mutation. */
@Service
public class OrderAccessScopeService {

    private static final int MAX_ASSIGNMENTS = 100;
    private final JdbcClient jdbcClient;
    private final CommercePolicyService policyService;

    public OrderAccessScopeService(JdbcClient jdbcClient, CommercePolicyService policyService) {
        this.jdbcClient = jdbcClient;
        this.policyService = policyService;
    }

    public AccessScope scopeForUser(long userId) {
        if (userId <= 0) return AccessScope.emptyScope();
        boolean unrestricted = userId == 1L || jdbcClient.sql("""
            SELECT COUNT(*) FROM sys_user_role ur
            JOIN sys_role r ON r.role_id=ur.role_id
            WHERE ur.user_id=:userId AND r.role_key='admin' AND r.status='0' AND r.del_flag='0'
            """).param("userId", userId).query(Integer.class).single() > 0;
        if (unrestricted) return new AccessScope(true, List.of(), List.of());
        List<Long> lineIds = jdbcClient.sql("""
            SELECT m.business_line_id FROM commerce_staff_business_line m
            JOIN commerce_business_line l ON l.id=m.business_line_id AND l.is_active=1
            WHERE m.user_id=:userId ORDER BY m.business_line_id
            """).param("userId", userId).query(Long.class).list();
        List<Long> centerIds = jdbcClient.sql("""
            SELECT m.work_center_id FROM commerce_staff_work_center m
            JOIN commerce_work_center c ON c.id=m.work_center_id AND c.is_active=1
            WHERE m.user_id=:userId ORDER BY m.work_center_id
            """).param("userId", userId).query(Long.class).list();
        return new AccessScope(false, List.copyOf(lineIds), List.copyOf(centerIds));
    }

    public boolean canAccessOrder(long userId, long orderId) {
        if (orderId <= 0) return false;
        AccessScope scope = scopeForUser(userId);
        if (scope.unrestricted()) {
            return jdbcClient.sql("SELECT COUNT(*) FROM grading_order WHERE id=:orderId")
                .param("orderId", orderId).query(Integer.class).single() == 1;
        }
        if (scope.denied()) return false;
        return jdbcClient.sql("""
            SELECT COUNT(*) FROM grading_order o
            JOIN commerce_staff_business_line bl
              ON bl.user_id=:userId AND bl.business_line_id=o.business_line_id
            JOIN commerce_business_line abl
              ON abl.id=bl.business_line_id AND abl.is_active=1
            JOIN commerce_staff_work_center wc
              ON wc.user_id=:userId AND wc.work_center_id=o.work_center_id
            JOIN commerce_work_center awc
              ON awc.id=wc.work_center_id AND awc.is_active=1
            WHERE o.id=:orderId
            """).param("userId", userId).param("orderId", orderId).query(Integer.class).single() == 1;
    }

    public void requireAccessibleOrder(long userId, long orderId) {
        if (!canAccessOrder(userId, orderId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }
    }

    /** Convenience hook for controllers that use the current RuoYi staff session. */
    public void requireAccessibleOrder(long orderId) {
        requireAccessibleOrder(SecurityUtils.getUserId(), orderId);
    }

    public boolean canAccessSubmission(long userId, long submissionId) {
        if (submissionId <= 0) return false;
        AccessScope scope = scopeForUser(userId);
        if (scope.unrestricted()) {
            return jdbcClient.sql("SELECT COUNT(*) FROM grading_submission WHERE id=:id")
                .param("id", submissionId).query(Integer.class).single() == 1;
        }
        if (scope.denied()) return false;
        return jdbcClient.sql("""
            SELECT COUNT(*) FROM grading_submission s
            WHERE s.id=:submissionId AND (
              EXISTS (
                SELECT 1 FROM grading_order_item oi
                JOIN grading_order o ON o.id=oi.order_id
                JOIN commerce_staff_business_line sbl ON sbl.user_id=:userId AND sbl.business_line_id=o.business_line_id
                JOIN commerce_business_line bl ON bl.id=sbl.business_line_id AND bl.is_active=1
                JOIN commerce_staff_work_center swc ON swc.user_id=:userId AND swc.work_center_id=o.work_center_id
                JOIN commerce_work_center wc ON wc.id=swc.work_center_id AND wc.is_active=1
                WHERE oi.grading_submission_id=s.id
              ) OR (
                NOT EXISTS (SELECT 1 FROM grading_order_item oi WHERE oi.grading_submission_id=s.id)
                AND s.order_origin_code='owned_inventory'
                AND EXISTS (
                  SELECT 1 FROM commerce_staff_business_line sbl
                  JOIN commerce_business_line bl ON bl.id=sbl.business_line_id AND bl.is_active=1
                  WHERE sbl.user_id=:userId AND sbl.business_line_id=s.business_line_id
                )
                AND EXISTS (
                  SELECT 1 FROM commerce_staff_work_center swc
                  JOIN commerce_work_center wc ON wc.id=swc.work_center_id AND wc.is_active=1
                  WHERE swc.user_id=:userId AND swc.work_center_id=s.work_center_id
                )
              )
            )
            """).param("submissionId", submissionId).param("userId", userId)
            .query(Integer.class).single() == 1;
    }

    public void requireAccessibleSubmission(long userId, long submissionId) {
        if (!canAccessSubmission(userId, submissionId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Submission not found");
        }
    }

    public void requireAccessibleSubmission(long submissionId) {
        requireAccessibleSubmission(SecurityUtils.getUserId(), submissionId);
    }

    public void requireAccessibleSubmissions(long userId, List<Long> submissionIds) {
        if (submissionIds == null || submissionIds.isEmpty() || submissionIds.size() > 500)
            throw badRequest("Submission ids are required");
        for (Long id : new LinkedHashSet<>(submissionIds)) {
            if (id == null) throw badRequest("Submission id is invalid");
            requireAccessibleSubmission(userId, id);
        }
    }

    public void requireUnrestricted(long userId, String operation) {
        if (!scopeForUser(userId).unrestricted()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, operation + " requires unrestricted order access");
        }
    }

    public static String submissionSqlPredicate(String alias) {
        if (alias == null || !alias.matches("[a-z][a-z0-9_]{0,15}")) throw new IllegalArgumentException("Invalid SQL alias");
        return """
            AND (
              EXISTS (SELECT 1 FROM grading_order_item scope_oi JOIN grading_order scope_o ON scope_o.id=scope_oi.order_id
                      JOIN commerce_business_line scope_bl ON scope_bl.id=scope_o.business_line_id AND scope_bl.is_active=1
                      JOIN commerce_work_center scope_wc ON scope_wc.id=scope_o.work_center_id AND scope_wc.is_active=1
                      WHERE scope_oi.grading_submission_id=%1$s.id
                        AND scope_o.business_line_id IN (:scopeLineIds)
                        AND scope_o.work_center_id IN (:scopeCenterIds))
              OR (NOT EXISTS (SELECT 1 FROM grading_order_item scope_none WHERE scope_none.grading_submission_id=%1$s.id)
                  AND %1$s.order_origin_code='owned_inventory'
                  AND %1$s.business_line_id IN (:scopeLineIds)
                  AND %1$s.work_center_id IN (:scopeCenterIds)
                  AND EXISTS (SELECT 1 FROM commerce_business_line scope_bl WHERE scope_bl.id=%1$s.business_line_id AND scope_bl.is_active=1)
                  AND EXISTS (SELECT 1 FROM commerce_work_center scope_wc WHERE scope_wc.id=%1$s.work_center_id AND scope_wc.is_active=1))
            )
            """.formatted(alias);
    }

    /** Explicitly classifies only an unbound submission as NXR-owned inventory. */
    @Transactional
    public SubmissionRouting assignOwnedInventorySubmission(SubmissionRoutingRequest request) {
        if (request == null || request.submissionId() == null || request.submissionId() <= 0)
            throw badRequest("Submission is required");
        policyService.requireActiveLine(request.businessLineId());
        policyService.requireActiveCenter(request.workCenterId());
        int ownedLine = jdbcClient.sql("SELECT COUNT(*) FROM commerce_business_line WHERE id=:id AND is_active=1 AND order_origin_code='owned_inventory'")
            .param("id", request.businessLineId()).query(Integer.class).single();
        if (ownedLine != 1) throw badRequest("An active owned-inventory business line is required");
        int changed = jdbcClient.sql("""
            UPDATE grading_submission
            SET order_origin_code='owned_inventory',business_line_id=:lineId,work_center_id=:centerId
            WHERE id=:submissionId
              AND NOT EXISTS (SELECT 1 FROM grading_order_item oi WHERE oi.grading_submission_id=:submissionId)
            """).param("lineId", request.businessLineId()).param("centerId", request.workCenterId())
            .param("submissionId", request.submissionId()).update();
        if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Submission is missing or already belongs to a customer order");
        return new SubmissionRouting(request.submissionId(), "owned_inventory", request.businessLineId(), request.workCenterId());
    }

    public ScopeCatalog scopeCatalog() {
        List<StaffOption> staff = jdbcClient.sql("""
            SELECT u.user_id,u.user_name,u.nick_name
            FROM sys_user u
            WHERE u.status='0' AND u.del_flag='0'
            ORDER BY u.user_name,u.user_id
            """).query((rs, n) -> new StaffOption(rs.getLong("user_id"), rs.getString("user_name"),
                rs.getString("nick_name"))).list();
        List<StaffScope> scopes = new ArrayList<>();
        for (StaffOption option : staff) {
            AccessScope scope = scopeForUser(option.userId());
            scopes.add(new StaffScope(option.userId(), option.userName(), option.nickName(), scope.unrestricted(),
                scope.businessLineIds(), scope.workCenterIds()));
        }
        return new ScopeCatalog(staff, scopes);
    }

    @Transactional
    public StaffScope saveStaffScope(StaffScopeRequest request) {
        if (request == null || request.userId() == null || request.userId() <= 0) throw badRequest("Staff user is required");
        long userId = request.userId();
        int userCount = jdbcClient.sql("SELECT COUNT(*) FROM sys_user WHERE user_id=:id AND status='0' AND del_flag='0'")
            .param("id", userId).query(Integer.class).single();
        if (userCount != 1) throw badRequest("Active staff user is required");
        List<Long> lineIds = boundedIds(request.businessLineIds(), "Business-line assignments");
        List<Long> centerIds = boundedIds(request.workCenterIds(), "Work-center assignments");
        lineIds.forEach(policyService::requireActiveLine);
        centerIds.forEach(policyService::requireActiveCenter);

        jdbcClient.sql("DELETE FROM commerce_staff_business_line WHERE user_id=:id").param("id", userId).update();
        jdbcClient.sql("DELETE FROM commerce_staff_work_center WHERE user_id=:id").param("id", userId).update();
        lineIds.forEach(id -> jdbcClient.sql("INSERT INTO commerce_staff_business_line(user_id,business_line_id) VALUES(:userId,:id)")
            .param("userId", userId).param("id", id).update());
        centerIds.forEach(id -> jdbcClient.sql("INSERT INTO commerce_staff_work_center(user_id,work_center_id) VALUES(:userId,:id)")
            .param("userId", userId).param("id", id).update());
        StaffOption user = jdbcClient.sql("SELECT user_id,user_name,nick_name FROM sys_user WHERE user_id=:id")
            .param("id", userId).query((rs, n) -> new StaffOption(rs.getLong("user_id"), rs.getString("user_name"),
                rs.getString("nick_name"))).single();
        AccessScope saved = scopeForUser(userId);
        return new StaffScope(userId, user.userName(), user.nickName(), saved.unrestricted(),
            saved.businessLineIds(), saved.workCenterIds());
    }

    private static List<Long> boundedIds(List<Long> values, String label) {
        if (values == null) return List.of();
        if (values.size() > MAX_ASSIGNMENTS) throw badRequest(label + " exceed the supported limit");
        LinkedHashSet<Long> unique = new LinkedHashSet<>();
        for (Long value : values) {
            if (value == null || value <= 0) throw badRequest(label + " contain an invalid id");
            unique.add(value);
        }
        return List.copyOf(unique);
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record AccessScope(boolean unrestricted, List<Long> businessLineIds, List<Long> workCenterIds) {
        public AccessScope {
            businessLineIds = businessLineIds == null ? List.of() : List.copyOf(businessLineIds);
            workCenterIds = workCenterIds == null ? List.of() : List.copyOf(workCenterIds);
        }
        public boolean denied() { return !unrestricted && (businessLineIds.isEmpty() || workCenterIds.isEmpty()); }
        public List<Long> safeBusinessLineIds() { return businessLineIds.isEmpty() ? List.of(-1L) : businessLineIds; }
        public List<Long> safeWorkCenterIds() { return workCenterIds.isEmpty() ? List.of(-1L) : workCenterIds; }
        static AccessScope emptyScope() { return new AccessScope(false, List.of(), List.of()); }
    }

    public record StaffOption(long userId, String userName, String nickName) {}
    public record StaffScope(long userId, String userName, String nickName, boolean unrestricted,
                             List<Long> businessLineIds, List<Long> workCenterIds) {}
    public record ScopeCatalog(List<StaffOption> staff, List<StaffScope> scopes) {}
    public record StaffScopeRequest(Long userId, List<Long> businessLineIds, List<Long> workCenterIds) {}
    public record SubmissionRoutingRequest(Long submissionId, Long businessLineId, Long workCenterId) {}
    public record SubmissionRouting(long submissionId, String orderOriginCode, long businessLineId, long workCenterId) {}
}
