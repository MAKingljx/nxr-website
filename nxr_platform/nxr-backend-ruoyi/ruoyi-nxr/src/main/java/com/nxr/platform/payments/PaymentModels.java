package com.nxr.platform.payments;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Shared, deliberately secret-free payment API models. */
public final class PaymentModels {

    private PaymentModels() {
    }

    public record AdminConfiguration(
        String provider,
        String displayName,
        String mode,
        boolean enabled,
        boolean ready,
        List<String> supportedCurrencies,
        String apiBaseUrl,
        String notifyUrl,
        String returnUrl,
        Map<String, String> maskedCredentials,
        Map<String, Boolean> credentialConfigured,
        LocalDateTime updatedAt
    ) {
    }

    /** Blank credential values mean "keep the stored value"; null/blank values are never returned by the API. */
    public record ConfigurationUpdate(
        String displayName,
        String mode,
        Boolean enabled,
        List<String> supportedCurrencies,
        String apiBaseUrl,
        String notifyUrl,
        String returnUrl,
        Map<String, String> credentials
    ) {
    }

    public record PublicOption(String provider, String displayName, String mode, List<String> currencies) {
    }

    public record CheckoutRequest(String provider, String idempotencyKey) {
    }

    public record CheckoutResponse(
        String provider,
        String paymentUrl,
        String qrPayload,
        String status,
        String providerOrderId
    ) {
    }

    public record ProviderCheckout(
        String providerOrderId,
        String paymentUrl,
        String qrPayload,
        String status,
        String rawResponse
    ) {
    }

    public record VerifiedPayment(
        String eventId,
        String providerOrderId,
        String transactionId,
        String merchantOrderNo,
        BigDecimal amount,
        String currency,
        String status,
        String rawPayload
    ) {
    }

    public record CaptureResult(VerifiedPayment payment, String rawResponse) {
    }

    public record HttpRequestData(String method, URI uri, Map<String, String> headers, String body) {
    }

    public record HttpResponseData(int statusCode, Map<String, List<String>> headers, String body) {
    }

    public record ProviderContext(
        String provider,
        String mode,
        String apiBaseUrl,
        String notifyUrl,
        String returnUrl,
        Map<String, String> credentials
    ) {
        public String credential(String key) {
            String value = credentials.get(key);
            return value == null ? "" : value;
        }
    }

    public record CheckoutContext(
        String orderNo,
        long paymentRecordId,
        BigDecimal amount,
        String currency,
        String idempotencyKey,
        ProviderContext provider
    ) {
    }

    public record WebhookRequest(Map<String, String> headers, String body, Map<String, String> form) {
        public String header(String name) {
            return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("");
        }
    }

    static String requiredText(JsonNode node, String pointer, String label) {
        JsonNode value = node.at(pointer);
        if (value.isMissingNode() || value.isNull() || value.asText().isBlank()) {
            throw PaymentValidationException.badGateway(label + " is missing from provider response");
        }
        return value.asText();
    }
}
