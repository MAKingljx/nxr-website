package com.nxr.platform.payments;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nxr.platform.payments.PaymentModels.AdminConfiguration;
import com.nxr.platform.payments.PaymentModels.ConfigurationUpdate;
import com.nxr.platform.payments.PaymentModels.ProviderContext;
import com.nxr.platform.payments.PaymentModels.PublicOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PaymentConfigurationService {

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final PaymentCryptoService crypto;

    PaymentConfigurationService(JdbcClient jdbcClient, ObjectMapper objectMapper, PaymentCryptoService crypto) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.crypto = crypto;
    }

    List<AdminConfiguration> listAdminConfigurations() {
        return jdbcClient.sql(
                """
                SELECT provider_code, display_name, mode_code, is_enabled, supported_currencies,
                       api_base_url, notify_url, return_url, credentials_ciphertext, updated_at
                FROM payment_channel_config
                ORDER BY sort_order, provider_code
                """
            )
            .query((rs, rowNum) -> toAdmin(new StoredConfiguration(
                rs.getString("provider_code"), rs.getString("display_name"), rs.getString("mode_code"),
                rs.getBoolean("is_enabled"), parseCurrencies(rs.getString("supported_currencies")),
                rs.getString("api_base_url"), rs.getString("notify_url"), rs.getString("return_url"),
                rs.getString("credentials_ciphertext"), rs.getObject("updated_at", LocalDateTime.class)
            )))
            .list();
    }

    List<Map<String, Object>> listPublicOptions(String requestedCurrency) {
        String currency = requestedCurrency == null ? "" : requestedCurrency.trim().toUpperCase(Locale.ROOT);
        List<Map<String, Object>> result = new ArrayList<>();
        for (AdminConfiguration config : listAdminConfigurations()) {
            if (!config.enabled() || !config.ready() || (!currency.isEmpty() && !config.supportedCurrencies().contains(currency))) {
                continue;
            }
            PaymentProviderSpec spec = PaymentProviderSpec.require(config.provider());
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("provider", config.provider());
            option.put("displayName", config.displayName());
            option.put("mode", config.mode());
            option.put("currencies", config.supportedCurrencies());
            option.put("flow", spec.flow());
            option.put("manualProof", false);
            result.add(option);
        }
        return result;
    }

    @Transactional
    AdminConfiguration update(String providerCode, ConfigurationUpdate request, Long updatedBy) {
        if (request == null) {
            throw PaymentValidationException.badRequest("Payment configuration is required");
        }
        PaymentProviderSpec spec = PaymentProviderSpec.require(providerCode);
        StoredConfiguration current = requireStored(spec.code(), true);
        boolean enabled = Boolean.TRUE.equals(request.enabled());
        String mode = spec.normalizeMode(request.mode());
        List<String> currencies = spec.normalizeCurrencies(request.supportedCurrencies());
        String apiBaseUrl = spec.normalizeBaseUrl(request.apiBaseUrl(), mode);
        String notifyUrl = PaymentProviderSpec.normalizeCallbackUrl(request.notifyUrl(), "Notification URL", enabled);
        String returnUrl = PaymentProviderSpec.normalizeCallbackUrl(request.returnUrl(), "Return URL", enabled && spec == PaymentProviderSpec.PAYPAL);
        String displayName = normalizeDisplayName(request.displayName(), spec.defaultDisplayName());

        Map<String, String> credentials = decryptCredentials(current);
        credentials.putAll(spec.normalizeCredentialUpdates(request.credentials()));
        credentials.keySet().removeIf(key -> !spec.credentialFields().contains(key));
        if (enabled) {
            spec.validateCredentials(credentials);
        } else if (!credentials.isEmpty()) {
            validatePresentCredentialFormats(spec, credentials);
        }
        String encryptedCredentials = credentials.isEmpty() ? null : crypto.encrypt(writeCredentials(credentials), spec.code(), "credentials");

        int updated = jdbcClient.sql(
                """
                UPDATE payment_channel_config
                SET display_name = :displayName, mode_code = :mode, is_enabled = :enabled,
                    supported_currencies = :currencies, api_base_url = :apiBaseUrl,
                    notify_url = :notifyUrl, return_url = :returnUrl,
                    credentials_ciphertext = :credentials, updated_by_user_id = :updatedBy,
                    config_version = config_version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE provider_code = :provider
                """
            )
            .param("displayName", displayName)
            .param("mode", mode)
            .param("enabled", enabled ? 1 : 0)
            .param("currencies", String.join(",", currencies))
            .param("apiBaseUrl", apiBaseUrl)
            .param("notifyUrl", notifyUrl)
            .param("returnUrl", returnUrl)
            .param("credentials", encryptedCredentials)
            .param("updatedBy", updatedBy)
            .param("provider", spec.code())
            .update();
        if (updated != 1) {
            throw PaymentValidationException.conflict("Payment configuration changed unexpectedly");
        }
        return toAdmin(requireStored(spec.code()));
    }

    ProviderContext requireEnabled(String providerCode, String currency) {
        PaymentProviderSpec spec = PaymentProviderSpec.require(providerCode);
        StoredConfiguration stored = requireStored(spec.code());
        if (!stored.enabled()) {
            throw PaymentValidationException.conflict("Payment channel is disabled");
        }
        String normalizedCurrency = currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
        if (!stored.currencies().contains(normalizedCurrency)) {
            throw PaymentValidationException.conflict("Payment channel does not support this order currency");
        }
        Map<String, String> credentials = decryptCredentials(stored);
        spec.validateCredentials(credentials);
        return new ProviderContext(
            spec.code(), stored.mode(), stored.apiBaseUrl(), stored.notifyUrl(), stored.returnUrl(), Map.copyOf(credentials)
        );
    }

    ProviderContext requireForWebhook(String providerCode) {
        PaymentProviderSpec spec = PaymentProviderSpec.require(providerCode);
        StoredConfiguration stored = requireStored(spec.code());
        Map<String, String> credentials = decryptCredentials(stored);
        spec.validateCredentials(credentials);
        return new ProviderContext(
            spec.code(), stored.mode(), stored.apiBaseUrl(), stored.notifyUrl(), stored.returnUrl(), Map.copyOf(credentials)
        );
    }

    private StoredConfiguration requireStored(String provider) {
        return requireStored(provider, false);
    }

    private StoredConfiguration requireStored(String provider, boolean forUpdate) {
        return jdbcClient.sql(
                """
                SELECT provider_code, display_name, mode_code, is_enabled, supported_currencies,
                       api_base_url, notify_url, return_url, credentials_ciphertext, updated_at
                FROM payment_channel_config WHERE provider_code = :provider
                """
                + (forUpdate ? " FOR UPDATE" : "")
            )
            .param("provider", provider)
            .query((rs, rowNum) -> new StoredConfiguration(
                rs.getString("provider_code"), rs.getString("display_name"), rs.getString("mode_code"),
                rs.getBoolean("is_enabled"), parseCurrencies(rs.getString("supported_currencies")),
                rs.getString("api_base_url"), rs.getString("notify_url"), rs.getString("return_url"),
                rs.getString("credentials_ciphertext"), rs.getObject("updated_at", LocalDateTime.class)
            ))
            .optional()
            .orElseThrow(() -> PaymentValidationException.unavailable("Payment channel configuration is not installed"));
    }

    private AdminConfiguration toAdmin(StoredConfiguration stored) {
        PaymentProviderSpec spec = PaymentProviderSpec.require(stored.provider());
        Map<String, String> credentials;
        try {
            credentials = decryptCredentials(stored);
        } catch (RuntimeException exception) {
            credentials = Map.of();
        }
        Map<String, Boolean> configured = new LinkedHashMap<>();
        Map<String, String> masked = new LinkedHashMap<>();
        for (String field : spec.credentialFields()) {
            String value = credentials.getOrDefault(field, "");
            configured.put(field, !value.isBlank());
            if (!value.isBlank()) {
                masked.put(field, mask(value));
            }
        }
        boolean ready;
        try {
            spec.normalizeMode(stored.mode());
            spec.normalizeCurrencies(stored.currencies());
            spec.normalizeBaseUrl(stored.apiBaseUrl(), stored.mode());
            PaymentProviderSpec.normalizeCallbackUrl(stored.notifyUrl(), "Notification URL", true);
            PaymentProviderSpec.normalizeCallbackUrl(stored.returnUrl(), "Return URL", spec == PaymentProviderSpec.PAYPAL);
            spec.validateCredentials(credentials);
            ready = true;
        } catch (RuntimeException exception) {
            ready = false;
        }
        return new AdminConfiguration(
            stored.provider(), stored.displayName(), stored.mode(), stored.enabled(), ready,
            stored.currencies(), stored.apiBaseUrl(), stored.notifyUrl(), stored.returnUrl(),
            Map.copyOf(masked), Map.copyOf(configured), stored.updatedAt()
        );
    }

    private Map<String, String> decryptCredentials(StoredConfiguration stored) {
        if (stored.encryptedCredentials() == null || stored.encryptedCredentials().isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            String json = crypto.decrypt(stored.encryptedCredentials(), stored.provider(), "credentials");
            return new LinkedHashMap<>(objectMapper.readValue(json, STRING_MAP));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored payment credentials are invalid", exception);
        }
    }

    private String writeCredentials(Map<String, String> credentials) {
        try {
            return objectMapper.writeValueAsString(credentials);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize payment credentials", exception);
        }
    }

    private static void validatePresentCredentialFormats(PaymentProviderSpec spec, Map<String, String> credentials) {
        boolean complete = spec.credentialFields().stream().allMatch(field -> !credentials.getOrDefault(field, "").isBlank());
        if (complete) {
            spec.validateCredentials(credentials);
        }
    }

    private static String normalizeDisplayName(String raw, String fallback) {
        String value = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (value.isEmpty()) {
            value = fallback;
        }
        if (value.length() > 80 || value.chars().anyMatch(ch -> Character.isISOControl(ch))) {
            throw PaymentValidationException.badRequest("Payment channel display name is invalid");
        }
        return value;
    }

    private static List<String> parseCurrencies(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split(","))
            .map(String::trim).filter(part -> !part.isEmpty()).map(part -> part.toUpperCase(Locale.ROOT)).distinct().toList();
    }

    private static String mask(String value) {
        String singleLine = value.replaceAll("\\s+", "");
        if (singleLine.contains("PRIVATEKEY") || singleLine.contains("PUBLICKEY") || singleLine.length() < 9) {
            return "••••••••";
        }
        return singleLine.substring(0, 3) + "••••" + singleLine.substring(singleLine.length() - 3);
    }

    private record StoredConfiguration(
        String provider,
        String displayName,
        String mode,
        boolean enabled,
        List<String> currencies,
        String apiBaseUrl,
        String notifyUrl,
        String returnUrl,
        String encryptedCredentials,
        LocalDateTime updatedAt
    ) {
    }
}
