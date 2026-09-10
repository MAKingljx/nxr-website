package com.nxr.platform.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nxr.platform.customer.CustomerAuthService;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

class CustomerAccountNotificationServiceTest {

    private static final String PAYLOAD_KEY = "test-only-not-a-real-secret-32-bytes-minimum";
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-08T02:00:00Z"), ZoneOffset.UTC);
    private JdbcTemplate jdbc;
    private JdbcClient client;
    private NotificationPayloadCipher cipher;
    private CustomerAuthService authService;
    private CustomerAccountNotificationService service;
    private long customerId;

    @BeforeEach
    void setUp() {
        DataSource dataSource = NotificationTestDatabase.create("account_" + System.nanoTime());
        jdbc = new JdbcTemplate(dataSource);
        client = JdbcClient.create(dataSource);
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        jdbc.update(
            "INSERT INTO customer_account (email, password_hash, display_name) VALUES (?, ?, ?)",
            "collector@example.com", encoder.encode("old-password"), "Collector"
        );
        customerId = jdbc.queryForObject("SELECT id FROM customer_account", Long.class);
        authService = new CustomerAuthService(client, jdbc, encoder);
        cipher = new NotificationPayloadCipher(new ObjectMapper(), PAYLOAD_KEY);
        NotificationTransport transport = configuredNoopTransport();
        NotificationOutboxService outbox = new NotificationOutboxService(
            client, transport, cipher, true, "https://nxr.example", clock
        );
        service = new CustomerAccountNotificationService(
            client, authService, outbox, clock, 60, 30, 20, 100, "https://nxr.example"
        );
    }

    @Test
    void verificationTokensExpireAndAreSingleUse() {
        String expiredRaw = "nxre_expired";
        jdbc.update(
            "INSERT INTO customer_account_token (customer_id, purpose_code, token_hash, request_ip_hash, expires_at) VALUES (?, 'verify_email', ?, ?, ?)",
            customerId, NotificationOutboxService.sha256(expiredRaw), NotificationOutboxService.sha256("ip|test"),
            LocalDateTime.now(clock).minusSeconds(1)
        );
        assertThatThrownBy(() -> service.confirmEmailVerification(expiredRaw))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("invalid, expired");

        CustomerAuthService.CustomerAccount account = authService.findCustomerById(customerId).orElseThrow();
        assertThat(service.requestEmailVerification(account, "127.0.0.1").queued()).isTrue();
        String token = latestTokenFromOutbox();
        assertThat(jdbc.queryForObject(
            "SELECT token_hash FROM customer_account_token WHERE purpose_code = 'verify_email' AND consumed_at IS NULL",
            String.class
        )).isEqualTo(NotificationOutboxService.sha256(token)).doesNotContain(token);
        assertThat(jdbc.queryForObject(
            "SELECT encrypted_payload FROM customer_notification_outbox ORDER BY id DESC LIMIT 1", String.class
        )).doesNotContain(token);
        assertThat(service.confirmEmailVerification(token).success()).isTrue();
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM customer_account WHERE id = ? AND email_verified_at IS NOT NULL", Integer.class, customerId
        )).isEqualTo(1);
        assertThatThrownBy(() -> service.confirmEmailVerification(token))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("already been used");
    }

    @Test
    void resetRequestDoesNotRevealWhetherAccountExists() {
        CustomerAccountNotificationService.ResetRequestResult known =
            service.requestPasswordReset("collector@example.com", "127.0.0.1");
        CustomerAccountNotificationService.ResetRequestResult unknown =
            service.requestPasswordReset("missing@example.com", "127.0.0.2");

        assertThat(known).isEqualTo(unknown);
        assertThat(known.message()).isEqualTo(CustomerAccountNotificationService.RESET_REQUEST_MESSAGE);
    }

    @Test
    void verificationRequestRateLimitStopsAdditionalTokens() {
        NotificationOutboxService outbox = new NotificationOutboxService(
            client, configuredNoopTransport(), cipher, true, "https://nxr.example", clock
        );
        CustomerAccountNotificationService limited = new CustomerAccountNotificationService(
            client, authService, outbox, clock, 60, 30, 1, 100, "https://nxr.example"
        );
        CustomerAuthService.CustomerAccount account = authService.findCustomerById(customerId).orElseThrow();

        assertThat(limited.requestEmailVerification(account, "127.0.0.1").queued()).isTrue();
        assertThat(limited.requestEmailVerification(account, "127.0.0.1").queued()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_notification_outbox", Integer.class)).isEqualTo(1);
    }

    @Test
    void passwordResetRevokesAllSessionsAndCannotBeReplayed() {
        jdbc.update(
            "INSERT INTO customer_session (customer_id, token_hash, expires_at) VALUES (?, ?, ?)",
            customerId, NotificationOutboxService.sha256("session-one"), LocalDateTime.now(clock).plusDays(1)
        );
        service.requestPasswordReset("collector@example.com", "127.0.0.3");
        String token = latestTokenFromOutbox();

        assertThat(service.confirmPasswordReset(token, "a-new-secure-password").success()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_session WHERE customer_id = ?", Integer.class, customerId))
            .isZero();
        assertThatThrownBy(() -> service.confirmPasswordReset(token, "another-secure-password"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("already been used");
    }

    private String latestTokenFromOutbox() {
        String encrypted = jdbc.queryForObject(
            "SELECT encrypted_payload FROM customer_notification_outbox ORDER BY id DESC LIMIT 1", String.class
        );
        String body = cipher.decrypt(encrypted).body();
        int marker = body.indexOf("token=");
        int end = body.indexOf('\n', marker);
        String encoded = body.substring(marker + 6, end < 0 ? body.length() : end).trim();
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
    }

    private static NotificationTransport configuredNoopTransport() {
        return new NotificationTransport() {
            @Override public boolean isConfigured() { return true; }
            @Override public void send(NotificationMessage message) { }
        };
    }
}
