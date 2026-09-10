package com.nxr.platform.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional outbox entry point for customer mail.
 * Call {@link #enqueueOrderStatus(long, String, String, String)} from the same
 * transaction that writes the order timeline so a rollback removes both rows.
 */
@Service
public class NotificationOutboxService {

    private static final Set<String> ORDER_STATUSES = Set.of(
        "created", "paid", "received", "grading", "review", "return_shipped", "delivered",
        "admission_approved", "admission_rejected", "admission_needs_information", "payment_expired"
    );
    private static final Map<String, String> STATUS_ALIASES = Map.ofEntries(
        Map.entry("submitted", "created"),
        Map.entry("payment_confirmed", "paid"),
        Map.entry("intake_received", "received"),
        Map.entry("grading_in_progress", "grading"),
        Map.entry("under_review", "review"),
        Map.entry("shipped", "return_shipped"),
        Map.entry("return_in_transit", "return_shipped")
    );

    private final JdbcClient jdbcClient;
    private final NotificationTransport transport;
    private final NotificationPayloadCipher payloadCipher;
    private final boolean deliveryEnabled;
    private final String publicSiteBaseUrl;
    private final Clock clock;

    @Autowired
    public NotificationOutboxService(
        JdbcClient jdbcClient,
        NotificationTransport transport,
        ObjectMapper objectMapper,
        @Value("${NXR_NOTIFICATION_DELIVERY_ENABLED:false}") boolean deliveryEnabled,
        @Value("${NXR_NOTIFICATION_PAYLOAD_KEY:}") String payloadKey,
        @Value("${nxr.public-site.base-url:http://127.0.0.1:3000}") String publicSiteBaseUrl
    ) {
        this(jdbcClient, transport, new NotificationPayloadCipher(objectMapper, payloadKey), deliveryEnabled,
            publicSiteBaseUrl, Clock.systemDefaultZone());
    }

    NotificationOutboxService(
        JdbcClient jdbcClient,
        NotificationTransport transport,
        NotificationPayloadCipher payloadCipher,
        boolean deliveryEnabled,
        String publicSiteBaseUrl,
        Clock clock
    ) {
        this.jdbcClient = jdbcClient;
        this.transport = transport;
        this.payloadCipher = payloadCipher;
        this.deliveryEnabled = deliveryEnabled;
        this.publicSiteBaseUrl = trimTrailingSlash(clean(publicSiteBaseUrl, 512));
        this.clock = clock;
    }

    public DeliveryCapability deliveryCapability() {
        boolean protectedPayloads = payloadCipher.isConfigured();
        boolean providerConfigured = transport.isConfigured();
        boolean available = deliveryEnabled && protectedPayloads && providerConfigured;
        String message;
        if (!deliveryEnabled) {
            message = "Email delivery is temporarily unavailable. Please contact NXR support.";
        } else if (!protectedPayloads) {
            message = "Email delivery is temporarily unavailable. Please contact NXR support.";
        } else if (!providerConfigured) {
            message = "Email delivery is temporarily unavailable. Please contact NXR support.";
        } else {
            message = "Email delivery is available.";
        }
        return new DeliveryCapability(available, deliveryEnabled, providerConfigured, protectedPayloads, message);
    }

    /**
     * Stable integration contract for order workflow code. Returns false for a
     * duplicate event or when protected payload storage is not configured.
     */
    @Transactional
    public boolean enqueueOrderStatus(long customerId, String orderNo, String statusCode, String publicMessage) {
        if (customerId <= 0 || !payloadCipher.isConfigured()) {
            return false;
        }
        String normalizedOrderNo = requireBounded(orderNo, "orderNo", 64);
        String normalizedStatus = normalizeStatus(statusCode);
        String message = requireBounded(publicMessage, "publicMessage", 1000);
        String orderUrl = publicSiteBaseUrl + "/account/orders/" + urlSegment(normalizedOrderNo);
        String subject = orderSubject(normalizedStatus, normalizedOrderNo);
        String body = message + "\n\nView your order: " + orderUrl + "\n\nNXR Grading";
        String stableKey = sha256("order|" + customerId + "|" + normalizedOrderNo + "|" + normalizedStatus + "|" + message);
        return insert(stableKey, customerId, "order_" + normalizedStatus, new OutboxPayload(subject, body));
    }

    @Transactional
    boolean enqueueAccountLink(long customerId, String purposeCode, String stableSeed, String subject, String body) {
        if (customerId <= 0 || !payloadCipher.isConfigured()) {
            return false;
        }
        return insert(sha256("account|" + customerId + "|" + purposeCode + "|" + stableSeed), customerId,
            requireBounded(purposeCode, "purposeCode", 48),
            new OutboxPayload(requireBounded(subject, "subject", 255), requireBody(body)));
    }

    boolean deliveryEnabled() {
        return deliveryEnabled;
    }

    NotificationTransport transport() {
        return transport;
    }

    NotificationPayloadCipher payloadCipher() {
        return payloadCipher;
    }

    Clock clock() {
        return clock;
    }

    private boolean insert(String stableKey, long customerId, String type, OutboxPayload payload) {
        int affected = jdbcClient.sql(
                """
                INSERT INTO customer_notification_outbox
                    (stable_key, customer_id, notification_type_code, encrypted_payload, status_code, next_attempt_at)
                VALUES (:stableKey, :customerId, :type, :payload, 'pending', :now)
                ON DUPLICATE KEY UPDATE stable_key = stable_key
                """
            )
            .param("stableKey", stableKey)
            .param("customerId", customerId)
            .param("type", type)
            .param("payload", payloadCipher.encrypt(payload))
            .param("now", LocalDateTime.now(clock))
            .update();
        return affected == 1;
    }

    private static String normalizeStatus(String value) {
        String normalized = clean(value, 64).toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        normalized = STATUS_ALIASES.getOrDefault(normalized, normalized);
        if (!ORDER_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported customer notification status");
        }
        return normalized;
    }

    private static String orderSubject(String status, String orderNo) {
        String label = switch (status) {
            case "created" -> "Order received";
            case "paid" -> "Payment confirmed";
            case "received" -> "Cards received";
            case "grading" -> "Grading in progress";
            case "review" -> "Order under review";
            case "return_shipped" -> "Return shipment sent";
            case "delivered" -> "Order delivered";
            case "admission_approved" -> "Order approved";
            case "admission_rejected" -> "Order application update";
            case "admission_needs_information" -> "Order information required";
            case "payment_expired" -> "Payment window expired";
            default -> "Order update";
        };
        return "NXR " + label + " — " + orderNo;
    }

    private static String urlSegment(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String requireBounded(String value, String field, int maxLength) {
        String cleaned = clean(value, maxLength + 1);
        if (cleaned.isBlank() || cleaned.length() > maxLength) {
            throw new IllegalArgumentException(field + " is required and must be at most " + maxLength + " characters");
        }
        return cleaned;
    }

    private static String requireBody(String value) {
        String body = value == null ? "" : value.trim();
        if (body.isBlank() || body.length() > 4000) {
            throw new IllegalArgumentException("body is required and must be at most 4000 characters");
        }
        return body;
    }

    private static String clean(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().replaceAll("[\\r\\n\\t]+", " ").replaceAll(" +", " ");
        return normalized.substring(0, Math.min(normalized.length(), maxLength));
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record DeliveryCapability(
        boolean available,
        boolean deliveryEnabled,
        boolean providerConfigured,
        boolean protectedPayloadStorage,
        String message
    ) {
    }

    public record OutboxPayload(String subject, String body) {
    }
}
