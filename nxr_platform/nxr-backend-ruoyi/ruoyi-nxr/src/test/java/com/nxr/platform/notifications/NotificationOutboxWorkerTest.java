package com.nxr.platform.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

class NotificationOutboxWorkerTest {

    @Test
    void retriesWithMockTransportThenMarksMessageSent() {
        DataSource dataSource = NotificationTestDatabase.create("outbox_" + System.nanoTime());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        JdbcClient client = JdbcClient.create(dataSource);
        jdbc.update("INSERT INTO customer_account (email, password_hash, display_name) VALUES ('c@example.com', 'hash', 'C')");
        long customerId = jdbc.queryForObject("SELECT id FROM customer_account", Long.class);
        Clock clock = Clock.fixed(Instant.parse("2026-09-08T02:00:00Z"), ZoneOffset.UTC);
        AtomicInteger calls = new AtomicInteger();
        NotificationTransport transport = new NotificationTransport() {
            @Override public boolean isConfigured() { return true; }
            @Override public void send(NotificationMessage message) throws NotificationTransportException {
                if (calls.incrementAndGet() == 1) throw new NotificationTransportException("mock_failure");
            }
        };
        NotificationPayloadCipher cipher = new NotificationPayloadCipher(
            new ObjectMapper(), "test-only-not-a-real-secret-32-bytes-minimum"
        );
        NotificationOutboxService outbox = new NotificationOutboxService(
            client, transport, cipher, true, "https://nxr.example", clock
        );
        NotificationOutboxWorker worker = new NotificationOutboxWorker(client, outbox, 10, 3);

        assertThat(outbox.enqueueOrderStatus(customerId, "NXR-100", "created", "We received your order.")).isTrue();
        assertThat(outbox.enqueueOrderStatus(customerId, "NXR-100", "created", "We received your order.")).isFalse();
        assertThat(worker.processBatch()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status_code FROM customer_notification_outbox", String.class)).isEqualTo("retry");
        jdbc.update("UPDATE customer_notification_outbox SET next_attempt_at = ?", LocalDateTime.now(clock));

        assertThat(worker.processBatch()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status_code FROM customer_notification_outbox", String.class)).isEqualTo("sent");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM customer_notification_outbox", Integer.class)).isEqualTo(2);
        assertThat(calls).hasValue(2);
    }

    @Test
    void disabledDeliveryNeverCallsTransport() {
        DataSource dataSource = NotificationTestDatabase.create("disabled_" + System.nanoTime());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        JdbcClient client = JdbcClient.create(dataSource);
        jdbc.update("INSERT INTO customer_account (email, password_hash, display_name) VALUES ('c@example.com', 'hash', 'C')");
        long customerId = jdbc.queryForObject("SELECT id FROM customer_account", Long.class);
        AtomicInteger calls = new AtomicInteger();
        NotificationTransport transport = new NotificationTransport() {
            @Override public boolean isConfigured() { return true; }
            @Override public void send(NotificationMessage message) { calls.incrementAndGet(); }
        };
        NotificationPayloadCipher cipher = new NotificationPayloadCipher(
            new ObjectMapper(), "test-only-not-a-real-secret-32-bytes-minimum"
        );
        NotificationOutboxService outbox = new NotificationOutboxService(
            client, transport, cipher, false, "https://nxr.example", Clock.systemUTC()
        );
        NotificationOutboxWorker worker = new NotificationOutboxWorker(client, outbox, 10, 3);

        assertThat(outbox.enqueueOrderStatus(customerId, "NXR-200", "paid", "Your payment is confirmed.")).isTrue();
        assertThat(worker.processBatch()).isZero();
        assertThat(calls).hasValue(0);
        assertThat(jdbc.queryForObject("SELECT status_code FROM customer_notification_outbox", String.class)).isEqualTo("pending");
    }

    @Test
    void orderMessagesKeepCustomerContentAndOrderLinksSeparate() {
        DataSource dataSource = NotificationTestDatabase.create("content_" + System.nanoTime());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        JdbcClient client = JdbcClient.create(dataSource);
        jdbc.update("INSERT INTO customer_account (email, password_hash, display_name) VALUES ('c@example.com', 'hash', 'C')");
        long customerId = jdbc.queryForObject("SELECT id FROM customer_account", Long.class);
        AtomicReference<NotificationMessage> delivered = new AtomicReference<>();
        NotificationTransport transport = new NotificationTransport() {
            @Override public boolean isConfigured() { return true; }
            @Override public void send(NotificationMessage message) { delivered.set(message); }
        };
        NotificationPayloadCipher cipher = new NotificationPayloadCipher(
            new ObjectMapper(), "test-only-not-a-real-secret-32-bytes-minimum"
        );
        NotificationOutboxService outbox = new NotificationOutboxService(
            client, transport, cipher, true, "https://nxr.example", Clock.systemUTC()
        );
        NotificationOutboxWorker worker = new NotificationOutboxWorker(client, outbox, 10, 3);

        assertThat(outbox.enqueueOrderStatus(customerId, "NXR-ONE", "return_shipped", "Your cards are on their way.")).isTrue();
        assertThat(worker.processBatch()).isEqualTo(1);
        assertThat(delivered.get().subject()).contains("NXR-ONE").doesNotContain("NXR-TWO");
        assertThat(delivered.get().textBody())
            .contains("Your cards are on their way.")
            .contains("https://nxr.example/account/orders/NXR-ONE")
            .doesNotContain("NXR-TWO");
    }

    @Test
    void admissionLifecycleUsesTheSameEncryptedIdempotentOutbox() {
        DataSource dataSource = NotificationTestDatabase.create("admission_" + System.nanoTime());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        JdbcClient client = JdbcClient.create(dataSource);
        jdbc.update("INSERT INTO customer_account (email, password_hash, display_name) VALUES ('c@example.com', 'hash', 'C')");
        long customerId = jdbc.queryForObject("SELECT id FROM customer_account", Long.class);
        NotificationPayloadCipher cipher = new NotificationPayloadCipher(
            new ObjectMapper(), "test-only-not-a-real-secret-32-bytes-minimum"
        );
        NotificationOutboxService outbox = new NotificationOutboxService(
            client, new NotificationTransport() {
                @Override public boolean isConfigured() { return false; }
                @Override public void send(NotificationMessage message) { }
            }, cipher, false, "https://nxr.example", Clock.systemUTC()
        );

        for (String status : new String[] {
            "admission_approved", "admission_rejected", "admission_needs_information", "payment_expired"
        }) {
            assertThat(outbox.enqueueOrderStatus(customerId, "NXR-ADMISSION", status, "Public admission update.")).isTrue();
            assertThat(outbox.enqueueOrderStatus(customerId, "NXR-ADMISSION", status, "Public admission update.")).isFalse();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_notification_outbox", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForList("SELECT encrypted_payload FROM customer_notification_outbox", String.class))
            .allSatisfy(payload -> assertThat(payload).doesNotContain("Public admission update."));
    }
}
