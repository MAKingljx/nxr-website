package com.nxr.platform.payments;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

enum PaymentProviderSpec {
    WECHAT_PAY_NATIVE(
        "wechat_pay_native", "微信支付", Set.of("live"), Set.of("CNY"),
        Map.of("live", "https://api.mch.weixin.qq.com"),
        List.of("appId", "merchantId", "merchantSerialNo", "merchantPrivateKey", "apiV3Key", "platformSerialNo", "platformPublicKey"),
        "qr"
    ),
    ALIPAY(
        "alipay", "支付宝", Set.of("sandbox", "live"), Set.of("CNY"),
        Map.of("sandbox", "https://openapi.alipaydev.com/gateway.do", "live", "https://openapi.alipay.com/gateway.do"),
        List.of("appId", "merchantPrivateKey", "alipayPublicKey", "sellerId"),
        "qr"
    ),
    PAYPAL(
        "paypal", "PayPal", Set.of("sandbox", "live"),
        Set.of("AUD", "BRL", "CAD", "CNY", "CZK", "DKK", "EUR", "HKD", "HUF", "ILS", "JPY", "MYR", "MXN", "TWD", "NZD", "NOK", "PHP", "PLN", "GBP", "SGD", "SEK", "CHF", "THB", "USD"),
        Map.of("sandbox", "https://api-m.sandbox.paypal.com", "live", "https://api-m.paypal.com"),
        List.of("clientId", "clientSecret", "webhookId"),
        "redirect"
    );

    private final String code;
    private final String defaultDisplayName;
    private final Set<String> modes;
    private final Set<String> currencies;
    private final Map<String, String> baseUrls;
    private final List<String> credentialFields;
    private final String flow;

    PaymentProviderSpec(
        String code,
        String defaultDisplayName,
        Set<String> modes,
        Set<String> currencies,
        Map<String, String> baseUrls,
        List<String> credentialFields,
        String flow
    ) {
        this.code = code;
        this.defaultDisplayName = defaultDisplayName;
        this.modes = modes;
        this.currencies = currencies;
        this.baseUrls = baseUrls;
        this.credentialFields = credentialFields;
        this.flow = flow;
    }

    String code() {
        return code;
    }

    String defaultDisplayName() {
        return defaultDisplayName;
    }

    List<String> credentialFields() {
        return credentialFields;
    }

    String flow() {
        return flow;
    }

    String expectedBaseUrl(String mode) {
        return baseUrls.get(mode);
    }

    static PaymentProviderSpec require(String raw) {
        String code = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        for (PaymentProviderSpec spec : values()) {
            if (spec.code.equals(code)) {
                return spec;
            }
        }
        throw PaymentValidationException.badRequest("Unsupported payment provider");
    }

    String normalizeMode(String raw) {
        String mode = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!modes.contains(mode)) {
            throw PaymentValidationException.badRequest("Unsupported mode for " + code);
        }
        return mode;
    }

    List<String> normalizeCurrencies(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw PaymentValidationException.badRequest("At least one supported currency is required");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : raw) {
            String currency = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
            if (!currencies.contains(currency)) {
                throw PaymentValidationException.badRequest("Unsupported currency for " + code + ": " + currency);
            }
            normalized.add(currency);
        }
        return List.copyOf(normalized);
    }

    String normalizeBaseUrl(String raw, String mode) {
        String value = raw == null || raw.isBlank() ? expectedBaseUrl(mode) : raw.trim();
        if (!value.equals(expectedBaseUrl(mode))) {
            throw PaymentValidationException.badRequest("API base URL is not allowlisted for " + code + " " + mode);
        }
        return value;
    }

    Map<String, String> normalizeCredentialUpdates(Map<String, String> updates) {
        Map<String, String> normalized = new LinkedHashMap<>();
        if (updates == null) {
            return normalized;
        }
        for (Map.Entry<String, String> entry : updates.entrySet()) {
            if (!credentialFields.contains(entry.getKey())) {
                throw PaymentValidationException.badRequest("Unknown credential field for " + code);
            }
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            if (!value.isBlank()) {
                if (value.length() > 12000) {
                    throw PaymentValidationException.badRequest("Payment credential value is too long");
                }
                normalized.put(entry.getKey(), value);
            }
        }
        return normalized;
    }

    void validateCredentials(Map<String, String> credentials) {
        for (String field : credentialFields) {
            if (credentials.getOrDefault(field, "").isBlank()) {
                throw PaymentValidationException.conflict("Complete all required " + code + " credentials before enabling the channel");
            }
        }
        if (this == WECHAT_PAY_NATIVE) {
            if (!credentials.get("appId").matches("[A-Za-z0-9_-]{6,32}")) {
                throw PaymentValidationException.badRequest("WeChat Pay app ID format is invalid");
            }
            if (!credentials.get("merchantId").matches("[0-9]{6,32}")) {
                throw PaymentValidationException.badRequest("WeChat Pay merchant ID format is invalid");
            }
            if (!credentials.get("merchantSerialNo").matches("[A-Fa-f0-9]{16,64}")) {
                throw PaymentValidationException.badRequest("WeChat Pay merchant certificate serial format is invalid");
            }
            if (!credentials.get("platformSerialNo").matches("(?:[A-Fa-f0-9]{16,64}|PUB_KEY_ID_[0-9]+)")) {
                throw PaymentValidationException.badRequest("WeChat Pay platform serial/public-key ID format is invalid");
            }
            if (credentials.get("apiV3Key").getBytes(java.nio.charset.StandardCharsets.UTF_8).length != 32) {
                throw PaymentValidationException.badRequest("WeChat Pay API v3 key must be exactly 32 UTF-8 bytes");
            }
            PaymentCryptoService.readPrivateKey(credentials.get("merchantPrivateKey"));
            PaymentCryptoService.readPublicKey(credentials.get("platformPublicKey"));
        } else if (this == ALIPAY) {
            if (!credentials.get("appId").matches("[0-9]{8,32}")) {
                throw PaymentValidationException.badRequest("Alipay app ID format is invalid");
            }
            if (!credentials.get("sellerId").matches("[0-9]{8,32}")) {
                throw PaymentValidationException.badRequest("Alipay seller ID format is invalid");
            }
            PaymentCryptoService.readPrivateKey(credentials.get("merchantPrivateKey"));
            PaymentCryptoService.readPublicKey(credentials.get("alipayPublicKey"));
        } else {
            if (!credentials.get("clientId").matches("[A-Za-z0-9_-]{8,256}")) {
                throw PaymentValidationException.badRequest("PayPal client ID format is invalid");
            }
            if (!credentials.get("webhookId").matches("[A-Za-z0-9_-]{6,64}")) {
                throw PaymentValidationException.badRequest("PayPal webhook ID format is invalid");
            }
        }
    }

    static String normalizeCallbackUrl(String raw, String label, boolean required) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            if (required) {
                throw PaymentValidationException.badRequest(label + " is required");
            }
            return "";
        }
        try {
            URI uri = URI.create(value);
            boolean secure = "https".equalsIgnoreCase(uri.getScheme());
            boolean local = "http".equalsIgnoreCase(uri.getScheme())
                && ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
            if ((!secure && !local) || uri.getUserInfo() != null || uri.getHost() == null || uri.getFragment() != null) {
                throw PaymentValidationException.badRequest(label + " must be an HTTPS URL (HTTP is allowed only for localhost)");
            }
            return uri.toASCIIString();
        } catch (IllegalArgumentException exception) {
            throw PaymentValidationException.badRequest(label + " is not a valid URL");
        }
    }
}
