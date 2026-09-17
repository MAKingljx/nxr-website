package com.nxr.platform.customer;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Read-only platform views; company selection narrows a live manager's existing access. */
@Service
public class AgentOverviewService {
    private static final String COMPANY_NAME = "COALESCE(NULLIF(TRIM(p.company_name),''),c.display_name)";
    private final JdbcClient jdbc;
    private final AgentOperatorScopeService scope;
    private EnterpriseCreditService credits;
    @org.springframework.beans.factory.annotation.Autowired
    public void setEnterpriseCreditService(EnterpriseCreditService credits) { this.credits=credits; }

    public AgentOverviewService(JdbcClient jdbc, AgentOperatorScopeService scope) {
        this.jdbc = jdbc;
        this.scope = scope;
    }

    public record OverviewRow(long id, long companyId, String companyName, boolean companyActive,
        String reference, String title, String clientName, String statusCode, Integer cardCount,
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigDecimal amount, String currencyCode, String detail, LocalDateTime updatedAt) { }

    private record View(String from, String reference, String title, String clientName, String status,
        String cardCount, String amount, String currency, String detail, String updatedAt, List<String> search) { }

    @Transactional(readOnly = true)
    public AgentWorkbenchService.Page<OverviewRow> overview(long userId, String view, Long companyId,
        String query, int page, int pageSize) {
        // Check current database permissions before querying any customer or address information.
        scope.requireManager(userId);
        if ("wallet".equals(view)) scope.requirePlatformPermissions(userId, "nxr:customer:finance");
        View definition = definition(view);
        if (companyId != null && companyId <= 0) throw badRequest("Select a valid partner company");
        if (query != null && query.length() > 255) throw badRequest("Search is too long");

        Map<String, Object> parameters = new LinkedHashMap<>();
        String where = " WHERE c.account_type_code='merchant'";
        if (companyId != null) {
            where += " AND c.id=:company";
            parameters.put("company", companyId);
        }
        if (query != null && !query.isBlank()) {
            // Escape LIKE wildcards so a customer reference containing '_' or '%' is searchable literally.
            String normalized = query.strip().toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_");
            parameters.put("query", "%" + normalized + "%");
            List<String> searches = new java.util.ArrayList<>(definition.search());
            searches.add(COMPANY_NAME);
            where += " AND (" + String.join(" OR ", searches.stream()
                .map(expression -> "LOWER(" + expression + ") LIKE :query ESCAPE '!'").toList()) + ")";
        }

        String from = definition.from() + " LEFT JOIN merchant_company_profile p ON p.customer_id=c.id";
        long total = jdbc.sql("SELECT COUNT(*) " + from + where).params(parameters).query(Long.class).single();
        int safePage = Math.max(1, page), safeSize = Math.min(100, Math.max(1, pageSize));
        parameters.put("limit", safeSize);
        parameters.put("offset", ((long) safePage - 1) * safeSize);
        String select = "SELECT r.id,c.id AS company_id," + COMPANY_NAME + " AS company_name,c.is_active AS company_active,"
            + definition.reference() + " AS reference," + definition.title() + " AS title,"
            + definition.clientName() + " AS client_name," + definition.status() + " AS status_code,"
            + definition.cardCount() + " AS card_count," + definition.amount() + " AS amount,"
            + definition.currency() + " AS currency_code," + definition.detail() + " AS detail,"
            + definition.updatedAt() + " AS updated_at ";
        List<OverviewRow> items = jdbc.sql(select + from + where + " ORDER BY " + definition.updatedAt()
                + " DESC,r.id DESC LIMIT :limit OFFSET :offset")
            .params(parameters).query(OverviewRow.class).list();
        return new AgentWorkbenchService.Page<>(items, total, safePage, safeSize);
    }

    private View definition(String view) {
        if (view == null) throw badRequest("Select a workspace view");
        // Every SQL identifier and expression comes from this fixed list, never from the request.
        return switch (view) {
            case "clients" -> new View(
                "FROM agent_client r JOIN customer_account c ON c.id=r.merchant_customer_id",
                "r.reference", "r.display_name", "r.display_name", "CASE WHEN r.active=1 THEN 'active' ELSE 'archived' END",
                "(SELECT COUNT(*) FROM agent_card ac JOIN agent_intake ai ON ai.id=ac.intake_id AND ai.merchant_customer_id=ac.merchant_customer_id"
                    + " WHERE ai.client_id=r.id AND ai.merchant_customer_id=r.merchant_customer_id)",
                "NULL", "NULL", "r.phone", "r.updated_at", List.of("r.reference", "r.display_name", "r.phone"));
            case "intakes" -> new View(
                "FROM agent_intake r JOIN customer_account c ON c.id=r.merchant_customer_id"
                    + " JOIN agent_client cl ON cl.id=r.client_id AND cl.merchant_customer_id=r.merchant_customer_id",
                "r.intake_no", "r.intake_no", "cl.display_name", "r.status_code", "r.expected_card_count", "NULL", "NULL",
                "CONCAT_WS(' · ',NULLIF(r.carrier_name,''),NULLIF(r.tracking_number,''))", "r.updated_at",
                List.of("r.intake_no", "cl.display_name", "r.tracking_number", "r.carrier_name"));
            case "cards" -> new View(
                "FROM agent_card r JOIN agent_intake i ON i.id=r.intake_id AND i.merchant_customer_id=r.merchant_customer_id"
                    + " JOIN agent_client cl ON cl.id=i.client_id AND cl.merchant_customer_id=r.merchant_customer_id"
                    + " JOIN customer_account c ON c.id=r.merchant_customer_id",
                "r.inventory_code", "r.card_name", "cl.display_name", "r.status_code", "1", "NULL", "NULL",
                "CONCAT_WS(' · ',NULLIF(r.official_card_number,''),NULLIF(i.intake_no,''))", "r.updated_at",
                List.of("r.inventory_code", "r.official_card_number", "r.card_name", "cl.display_name", "i.intake_no"));
            case "batches" -> new View(
                "FROM merchant_order_batch r JOIN customer_account c ON c.id=r.merchant_customer_id",
                "r.batch_no", "r.batch_name", "NULL", "r.status_code",
                "(SELECT COALESCE(SUM(o.total_card_count),0) FROM merchant_order_batch_item bi"
                    + " JOIN grading_order o ON o.id=bi.order_id AND o.customer_id=r.merchant_customer_id WHERE bi.batch_id=r.id)",
                "NULL", "NULL", "r.source_name", "r.updated_at", List.of("r.batch_no", "r.batch_name", "r.source_name"));
            case "returns" -> new View(
                "FROM agent_return_shipment r JOIN customer_account c ON c.id=r.merchant_customer_id"
                    + " JOIN agent_client cl ON cl.id=r.client_id AND cl.merchant_customer_id=r.merchant_customer_id",
                "r.shipment_no", "r.shipment_no", "cl.display_name", "r.status_code",
                "(SELECT COUNT(*) FROM agent_card ac WHERE ac.return_shipment_id=r.id AND ac.merchant_customer_id=r.merchant_customer_id)",
                "NULL", "NULL", "CONCAT_WS(' · ',NULLIF(r.carrier_name,''),NULLIF(r.tracking_number,''))",
                "COALESCE(r.delivered_at,r.shipped_at)", List.of("r.shipment_no", "cl.display_name", "r.tracking_number", "r.carrier_name"));
            case "wallet" -> new View(
                credits==null ? "FROM merchant_wallet r JOIN customer_account c ON c.id=r.customer_id"
                    : "FROM (SELECT c.id,c.id AS customer_id,'PTS' AS currency_code,COALESCE(w.balance,0) AS balance,"
                        + "COALESCE(w.updated_at,c.created_at) AS updated_at FROM customer_account c LEFT JOIN merchant_wallet w"
                        + " ON w.customer_id=c.id AND w.currency_code='PTS') r JOIN customer_account c ON c.id=r.customer_id",
                "r.currency_code", "r.currency_code", "NULL", "NULL", "NULL", "r.balance", "r.currency_code", "NULL",
                "r.updated_at", List.of("r.currency_code"));
            case "addresses" -> new View(
                "FROM customer_address r JOIN customer_account c ON c.id=r.customer_id",
                "r.label", "r.contact_name", "NULL", "CASE WHEN r.is_default=1 THEN 'default' ELSE 'saved' END", "NULL", "NULL", "NULL",
                "CONCAT_WS(', ',NULLIF(r.address_line1,''),NULLIF(r.address_line2,''),NULLIF(r.city,''),NULLIF(r.region,''),NULLIF(r.postal_code,''),NULLIF(r.country,''))",
                "r.updated_at", List.of("r.label", "r.contact_name", "r.address_line1", "r.city", "r.country"));
            default -> throw badRequest("Select a valid workspace view");
        };
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
