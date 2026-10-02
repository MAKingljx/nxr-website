package com.nxr.platform.payments;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Financial review metadata; provider payloads and credentials never leave this service. */
@Service
public class FinanceExceptionReviewService {
    private final JdbcClient jdbcClient;

    public FinanceExceptionReviewService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<FinanceException> listForOrder(long orderId) {
        return jdbcClient.sql("""
            SELECT id, order_id, payment_record_id, payment_attempt_id, provider_code,
                   provider_event_id, provider_transaction_id, exception_type_code,
                   amount, currency_code, resolution_status_code, resolution_note,
                   resolved_at, created_at
            FROM payment_finance_exception WHERE order_id=:orderId
            ORDER BY created_at DESC, id DESC
            """)
            .param("orderId", orderId)
            .query((rs, row) -> new FinanceException(rs.getLong("id"), rs.getLong("order_id"),
                rs.getLong("payment_record_id"), rs.getLong("payment_attempt_id"), rs.getString("provider_code"),
                rs.getString("provider_event_id"), rs.getString("provider_transaction_id"),
                rs.getString("exception_type_code"), rs.getBigDecimal("amount"), rs.getString("currency_code"),
                rs.getString("resolution_status_code"), rs.getString("resolution_note"),
                rs.getObject("resolved_at", LocalDateTime.class), rs.getObject("created_at", LocalDateTime.class)))
            .list();
    }

    public record FinanceException(long id, long orderId, long paymentRecordId, long paymentAttemptId,
                                   String providerCode, String providerEventId, String providerTransactionId,
                                   String exceptionTypeCode, BigDecimal amount, String currencyCode,
                                   String resolutionStatusCode, String resolutionNote,
                                   LocalDateTime resolvedAt, LocalDateTime createdAt) { }
}
