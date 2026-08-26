package com.nxr.platform.admin;

import com.nxr.platform.shared.ProductTypePolicy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Read and account-safety operations for public customer accounts. */
@Service
public class AdminCustomerService {

    private final JdbcClient jdbcClient;

    public AdminCustomerService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public CustomerListResponse listCustomers(int requestedPage, int requestedPageSize, String requestedStatus, String requestedQuery) {
        int page = Math.max(1, requestedPage);
        int pageSize = Math.max(1, Math.min(100, requestedPageSize));
        int offset = (page - 1) * pageSize;
        String status = normalizeStatus(requestedStatus);
        String query = clean(requestedQuery, 191).toLowerCase(Locale.ROOT);
        String queryLike = "%" + query + "%";

        List<CustomerSummary> items = jdbcClient.sql(
                """
                SELECT c.id, c.email, c.display_name, c.mobile, c.account_type_code, c.is_active, c.created_at, c.last_login_at,
                       (SELECT COUNT(*) FROM certificate_ownership o
                        WHERE o.customer_id = c.id AND o.ownership_status_code = 'active') AS active_card_count,
                       (SELECT COUNT(*) FROM certificate_ownership o
                        WHERE o.customer_id = c.id) AS ownership_count,
                       (SELECT COUNT(*) FROM grading_order g
                        WHERE g.customer_id = c.id) AS order_count,
                       (SELECT COUNT(*) FROM customer_session s
                        WHERE s.customer_id = c.id AND s.expires_at > CURRENT_TIMESTAMP) AS active_session_count
                FROM customer_account c
                WHERE (:status = ''
                       OR (:status = 'active' AND c.is_active = 1)
                       OR (:status = 'inactive' AND c.is_active = 0))
                  AND (:query = ''
                       OR LOWER(c.email) LIKE :queryLike
                       OR LOWER(c.display_name) LIKE :queryLike
                       OR LOWER(COALESCE(c.mobile, '')) LIKE :queryLike)
                ORDER BY c.created_at DESC, c.id DESC
                LIMIT :pageSize OFFSET :offset
                """
            )
            .param("status", status)
            .param("query", query)
            .param("queryLike", queryLike)
            .param("pageSize", pageSize)
            .param("offset", offset)
            .query((rs, rowNum) -> mapCustomerSummary(rs))
            .list();

        int total = jdbcClient.sql(
                """
                SELECT COUNT(*)
                FROM customer_account c
                WHERE (:status = ''
                       OR (:status = 'active' AND c.is_active = 1)
                       OR (:status = 'inactive' AND c.is_active = 0))
                  AND (:query = ''
                       OR LOWER(c.email) LIKE :queryLike
                       OR LOWER(c.display_name) LIKE :queryLike
                       OR LOWER(COALESCE(c.mobile, '')) LIKE :queryLike)
                """
            )
            .param("status", status)
            .param("query", query)
            .param("queryLike", queryLike)
            .query(Integer.class)
            .single();

        return new CustomerListResponse(items, page, pageSize, total);
    }

    public CustomerDetailResponse requireCustomer(long customerId) {
        CustomerSummary customer = jdbcClient.sql(
                """
                SELECT c.id, c.email, c.display_name, c.mobile, c.account_type_code, c.is_active, c.created_at, c.last_login_at,
                       (SELECT COUNT(*) FROM certificate_ownership o
                        WHERE o.customer_id = c.id AND o.ownership_status_code = 'active') AS active_card_count,
                       (SELECT COUNT(*) FROM certificate_ownership o
                        WHERE o.customer_id = c.id) AS ownership_count,
                       (SELECT COUNT(*) FROM grading_order g
                        WHERE g.customer_id = c.id) AS order_count,
                       (SELECT COUNT(*) FROM customer_session s
                        WHERE s.customer_id = c.id AND s.expires_at > CURRENT_TIMESTAMP) AS active_session_count
                FROM customer_account c
                WHERE c.id = :customerId
                """
            )
            .param("customerId", customerId)
            .query((rs, rowNum) -> mapCustomerSummary(rs))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer account not found"));

        return new CustomerDetailResponse(
            customer,
            listCustomerCards(customerId),
            listCustomerOwnershipEvents(customerId),
            listCustomerOrders(customerId)
        );
    }

    @Transactional
    public CustomerDetailResponse updateCustomerStatus(long customerId, UpdateCustomerStatusRequest request) {
        if (request == null || request.active() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer active status is required");
        }
        requireCustomer(customerId);
        jdbcClient.sql("UPDATE customer_account SET is_active = :active WHERE id = :customerId")
            .param("active", request.active() ? 1 : 0)
            .param("customerId", customerId)
            .update();
        if (!request.active()) {
            jdbcClient.sql("DELETE FROM customer_session WHERE customer_id = :customerId")
                .param("customerId", customerId)
                .update();
        }
        return requireCustomer(customerId);
    }

    @Transactional
    public CustomerDetailResponse updateCustomerType(long customerId, UpdateCustomerTypeRequest request) {
        String accountType = request == null ? "" : clean(request.accountTypeCode(), 32).toLowerCase(Locale.ROOT);
        if (!accountType.equals("customer") && !accountType.equals("merchant")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer type must be customer or merchant");
        }
        requireCustomer(customerId);
        jdbcClient.sql("UPDATE customer_account SET account_type_code = :accountType WHERE id = :customerId")
            .param("accountType", accountType)
            .param("customerId", customerId)
            .update();
        return requireCustomer(customerId);
    }

    @Transactional
    public SessionRevocationResponse revokeCustomerSessions(long customerId) {
        requireCustomer(customerId);
        int revoked = jdbcClient.sql("DELETE FROM customer_session WHERE customer_id = :customerId")
            .param("customerId", customerId)
            .update();
        return new SessionRevocationResponse(customerId, revoked);
    }

    private List<CustomerCardRecord> listCustomerCards(long customerId) {
        return jdbcClient.sql(
                """
                SELECT o.id, o.cert_id, o.ownership_status_code, o.visibility_code, o.note,
                       o.bound_at, o.released_at,
                       COALESCE(NULLIF(s.product_type_code, ''), 'graded_card') AS product_type_code,
                       s.vintage_classification_code,
                       s.merch_description, s.card_name, s.brand_name,
                       gs.final_grade_value, gs.final_grade_label
                FROM certificate_ownership o
                LEFT JOIN published_certificate pc ON UPPER(pc.cert_id) = UPPER(o.cert_id)
                LEFT JOIN grading_submission s ON s.id = pc.submission_id
                LEFT JOIN grading_score gs ON gs.submission_id = s.id
                WHERE o.customer_id = :customerId
                ORDER BY o.bound_at DESC, o.id DESC
                """
            )
            .param("customerId", customerId)
            .query((rs, rowNum) -> new CustomerCardRecord(
                rs.getLong("id"),
                rs.getString("cert_id"),
                rs.getString("ownership_status_code"),
                rs.getString("visibility_code"),
                rs.getString("note"),
                rs.getObject("bound_at", LocalDateTime.class),
                rs.getObject("released_at", LocalDateTime.class),
                ProductTypePolicy.normalizeStored(rs.getString("product_type_code")),
                rs.getString("vintage_classification_code"),
                rs.getString("merch_description"),
                rs.getString("card_name"),
                rs.getString("brand_name"),
                rs.getBigDecimal("final_grade_value"),
                rs.getString("final_grade_label")
            ))
            .list();
    }

    private List<CustomerOwnershipEvent> listCustomerOwnershipEvents(long customerId) {
        return jdbcClient.sql(
                """
                SELECT e.id, e.cert_id, e.event_type_code, e.visibility_code, e.message, e.created_at,
                       e.from_customer_id, from_customer.display_name AS from_display_name,
                       e.to_customer_id, to_customer.display_name AS to_display_name
                FROM certificate_ownership_event e
                LEFT JOIN customer_account from_customer ON from_customer.id = e.from_customer_id
                LEFT JOIN customer_account to_customer ON to_customer.id = e.to_customer_id
                WHERE e.from_customer_id = :customerId OR e.to_customer_id = :customerId
                ORDER BY e.created_at DESC, e.id DESC
                LIMIT 100
                """
            )
            .param("customerId", customerId)
            .query((rs, rowNum) -> new CustomerOwnershipEvent(
                rs.getLong("id"),
                rs.getString("cert_id"),
                rs.getString("event_type_code"),
                rs.getString("visibility_code"),
                nullableLong(rs, "from_customer_id"),
                rs.getString("from_display_name"),
                nullableLong(rs, "to_customer_id"),
                rs.getString("to_display_name"),
                rs.getString("message"),
                rs.getObject("created_at", LocalDateTime.class)
            ))
            .list();
    }

    private List<CustomerOrderRecord> listCustomerOrders(long customerId) {
        return jdbcClient.sql(
                """
                SELECT id, order_no, status_code, service_level_code, total_card_count,
                       total_amount, currency_code, created_at, updated_at
                FROM grading_order
                WHERE customer_id = :customerId
                ORDER BY created_at DESC, id DESC
                LIMIT 100
                """
            )
            .param("customerId", customerId)
            .query((rs, rowNum) -> new CustomerOrderRecord(
                rs.getLong("id"),
                rs.getString("order_no"),
                rs.getString("status_code"),
                rs.getString("service_level_code"),
                rs.getInt("total_card_count"),
                rs.getBigDecimal("total_amount"),
                rs.getString("currency_code"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)
            ))
            .list();
    }

    private static CustomerSummary mapCustomerSummary(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CustomerSummary(
            rs.getLong("id"),
            rs.getString("email"),
            rs.getString("display_name"),
            rs.getString("mobile"),
            rs.getString("account_type_code"),
            rs.getBoolean("is_active"),
            rs.getInt("active_card_count"),
            rs.getInt("ownership_count"),
            rs.getInt("order_count"),
            rs.getInt("active_session_count"),
            rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("last_login_at", LocalDateTime.class)
        );
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static String normalizeStatus(String value) {
        String status = clean(value, 16).toLowerCase(Locale.ROOT);
        return status.equals("active") || status.equals("inactive") ? status : "";
    }

    private static String clean(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String cleaned = value.trim();
        return cleaned.substring(0, Math.min(maxLength, cleaned.length()));
    }

    public record CustomerListResponse(List<CustomerSummary> items, int page, int pageSize, int total) {
    }

    public record CustomerSummary(
        long id,
        String email,
        String displayName,
        String mobile,
        String accountTypeCode,
        boolean active,
        int activeCardCount,
        int ownershipCount,
        int orderCount,
        int activeSessionCount,
        LocalDateTime createdAt,
        LocalDateTime lastLoginAt
    ) {
    }

    public record CustomerDetailResponse(
        CustomerSummary customer,
        List<CustomerCardRecord> cards,
        List<CustomerOwnershipEvent> ownershipEvents,
        List<CustomerOrderRecord> orders
    ) {
    }

    public record CustomerCardRecord(
        long ownershipId,
        String certId,
        String statusCode,
        String visibilityCode,
        String note,
        LocalDateTime boundAt,
        LocalDateTime releasedAt,
        String productType,
        String vintageClassification,
        String merchDescription,
        String cardName,
        String brandName,
        BigDecimal finalGradeValue,
        String finalGradeLabel
    ) {
    }

    public record CustomerOwnershipEvent(
        long id,
        String certId,
        String eventTypeCode,
        String visibilityCode,
        Long fromCustomerId,
        String fromDisplayName,
        Long toCustomerId,
        String toDisplayName,
        String message,
        LocalDateTime createdAt
    ) {
    }

    public record CustomerOrderRecord(
        long id,
        String orderNo,
        String statusCode,
        String serviceLevelCode,
        int totalCardCount,
        BigDecimal totalAmount,
        String currencyCode,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {
    }

    public record UpdateCustomerStatusRequest(Boolean active) {
    }

    public record UpdateCustomerTypeRequest(String accountTypeCode) {
    }

    public record SessionRevocationResponse(long customerId, int revokedSessions) {
    }
}
