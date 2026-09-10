package com.nxr.platform.payments;

import com.nxr.platform.payments.PaymentModels.WebhookRequest;
import com.ruoyi.common.annotation.Anonymous;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Anonymous
@RestController
@RequestMapping("/api/payments/webhooks")
public class PaymentWebhookController {

    private static final int MAX_WEBHOOK_BYTES = 128 * 1024;

    private final PaymentCheckoutService checkoutService;
    private final PaymentWebhookGate webhookGate;

    public PaymentWebhookController(PaymentCheckoutService checkoutService, PaymentWebhookGate webhookGate) {
        this.checkoutService = checkoutService;
        this.webhookGate = webhookGate;
    }

    @PostMapping("/{provider}")
    public ResponseEntity<?> receive(
        @PathVariable String provider,
        @RequestHeader Map<String, String> headers,
        @RequestBody String body,
        HttpServletRequest servletRequest
    ) {
        if (body == null || body.isBlank() || body.getBytes(StandardCharsets.UTF_8).length > MAX_WEBHOOK_BYTES) {
            throw PaymentValidationException.badRequest("Payment webhook body is missing or too large");
        }
        PaymentProviderSpec spec = PaymentProviderSpec.require(provider);
        Map<String, String> form = spec == PaymentProviderSpec.ALIPAY ? parseForm(body, servletRequest.getContentType()) : Map.of();
        String result;
        try (PaymentWebhookGate.Permit ignored = webhookGate.enter(spec.code(), servletRequest.getRemoteAddr())) {
            result = checkoutService.processWebhook(spec.code(), new WebhookRequest(Map.copyOf(headers), body, form));
        }
        if (spec == PaymentProviderSpec.ALIPAY) {
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(result);
        }
        return ResponseEntity.ok(Map.of("status", result));
    }

    static Map<String, String> parseForm(String body, String contentType) {
        if (contentType == null || !contentType.toLowerCase().startsWith(MediaType.APPLICATION_FORM_URLENCODED_VALUE)) {
            throw PaymentValidationException.badRequest("Alipay webhook must be form encoded");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            int separator = pair.indexOf('=');
            if (separator <= 0) {
                throw PaymentValidationException.badRequest("Alipay webhook form is invalid");
            }
            String key = URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
            if (key.length() > 64 || value.length() > 16000 || values.putIfAbsent(key, value) != null) {
                throw PaymentValidationException.badRequest("Alipay webhook contains invalid or duplicate fields");
            }
        }
        return Map.copyOf(values);
    }
}
