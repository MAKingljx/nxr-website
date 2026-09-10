package com.nxr.platform.notifications;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Bounded retry dispatcher. Logs contain only outbox IDs and error categories. */
@Service
public class NotificationOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(NotificationOutboxWorker.class);
    private final JdbcClient jdbcClient;
    private final NotificationOutboxService outboxService;
    private final int batchSize;
    private final int maxAttempts;

    public NotificationOutboxWorker(
        JdbcClient jdbcClient,
        NotificationOutboxService outboxService,
        @Value("${nxr.notifications.batch-size:10}") int batchSize,
        @Value("${nxr.notifications.max-attempts:3}") int maxAttempts
    ) {
        this.jdbcClient = jdbcClient;
        this.outboxService = outboxService;
        this.batchSize = Math.max(1, Math.min(batchSize, 50));
        this.maxAttempts = Math.max(1, Math.min(maxAttempts, 5));
    }

    @Scheduled(fixedDelayString = "${nxr.notifications.poll-delay-ms:30000}")
    public void scheduledDispatch() {
        if (outboxService.deliveryCapability().available()) {
            processBatch();
        }
    }

    public int processBatch() {
        if (!outboxService.deliveryEnabled() || !outboxService.transport().isConfigured()
            || !outboxService.payloadCipher().isConfigured()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now(outboxService.clock());
        recoverStaleClaims(now.minusMinutes(10));
        List<Long> candidateIds = jdbcClient.sql(
                """
                SELECT id FROM customer_notification_outbox
                WHERE status_code IN ('pending', 'retry')
                  AND next_attempt_at <= :now
                  AND attempt_count < :maxAttempts
                ORDER BY id
                LIMIT :batchSize
                """
            )
            .param("now", now)
            .param("maxAttempts", maxAttempts)
            .param("batchSize", batchSize)
            .query(Long.class)
            .list();
        int processed = 0;
        for (Long id : candidateIds) {
            if (claim(id, now)) {
                dispatch(id, now);
                processed++;
            }
        }
        return processed;
    }

    private void recoverStaleClaims(LocalDateTime staleBefore) {
        jdbcClient.sql(
                """
                UPDATE customer_notification_outbox
                SET status_code = 'retry', claimed_at = NULL, next_attempt_at = :now,
                    last_error_code = 'stale_claim'
                WHERE status_code = 'processing' AND claimed_at < :staleBefore
                """
            )
            .param("now", LocalDateTime.now(outboxService.clock()))
            .param("staleBefore", staleBefore)
            .update();
    }

    private boolean claim(long id, LocalDateTime now) {
        return jdbcClient.sql(
                """
                UPDATE customer_notification_outbox
                SET status_code = 'processing', claimed_at = :now, attempt_count = attempt_count + 1
                WHERE id = :id AND status_code IN ('pending', 'retry')
                  AND next_attempt_at <= :now AND attempt_count < :maxAttempts
                """
            )
            .param("now", now)
            .param("id", id)
            .param("maxAttempts", maxAttempts)
            .update() == 1;
    }

    private void dispatch(long id, LocalDateTime now) {
        Optional<DispatchRow> row = jdbcClient.sql(
                """
                SELECT o.encrypted_payload, o.attempt_count, c.email
                FROM customer_notification_outbox o
                JOIN customer_account c ON c.id = o.customer_id
                WHERE o.id = :id AND o.status_code = 'processing' AND c.is_active = 1
                """
            )
            .param("id", id)
            .query((rs, rowNum) -> new DispatchRow(
                rs.getString("encrypted_payload"), rs.getInt("attempt_count"), rs.getString("email")
            ))
            .optional();
        if (row.isEmpty()) {
            markPermanentFailure(id, "recipient_unavailable");
            return;
        }
        try {
            NotificationOutboxService.OutboxPayload payload = outboxService.payloadCipher().decrypt(row.get().encryptedPayload());
            outboxService.transport().send(new NotificationMessage(row.get().email(), payload.subject(), payload.body()));
            jdbcClient.sql(
                    """
                    UPDATE customer_notification_outbox
                    SET status_code = 'sent', sent_at = :now, claimed_at = NULL, last_error_code = NULL
                    WHERE id = :id AND status_code = 'processing'
                    """
                )
                .param("now", now)
                .param("id", id)
                .update();
        } catch (NotificationTransport.NotificationTransportException exception) {
            scheduleRetry(id, row.get().attemptCount(), exception.errorCode(), now);
        } catch (RuntimeException exception) {
            scheduleRetry(id, row.get().attemptCount(), "payload_unavailable", now);
        }
    }

    private void scheduleRetry(long id, int attempts, String errorCode, LocalDateTime now) {
        boolean exhausted = attempts >= maxAttempts;
        long delayMinutes = Math.min(60, 1L << Math.min(attempts - 1, 5));
        jdbcClient.sql(
                """
                UPDATE customer_notification_outbox
                SET status_code = :status, claimed_at = NULL, next_attempt_at = :nextAttempt,
                    last_error_code = :errorCode
                WHERE id = :id AND status_code = 'processing'
                """
            )
            .param("status", exhausted ? "failed" : "retry")
            .param("nextAttempt", now.plusMinutes(delayMinutes))
            .param("errorCode", boundedErrorCode(errorCode))
            .param("id", id)
            .update();
        log.warn("Customer notification outbox {} delivery failed ({})", id, boundedErrorCode(errorCode));
    }

    private void markPermanentFailure(long id, String errorCode) {
        jdbcClient.sql(
                """
                UPDATE customer_notification_outbox
                SET status_code = 'failed', claimed_at = NULL, last_error_code = :errorCode
                WHERE id = :id
                """
            )
            .param("errorCode", boundedErrorCode(errorCode))
            .param("id", id)
            .update();
    }

    private static String boundedErrorCode(String value) {
        String safe = value == null ? "delivery_failed" : value.replaceAll("[^a-zA-Z0-9_-]", "_");
        return safe.substring(0, Math.min(safe.length(), 64));
    }

    private record DispatchRow(String encryptedPayload, int attemptCount, String email) {
    }
}
