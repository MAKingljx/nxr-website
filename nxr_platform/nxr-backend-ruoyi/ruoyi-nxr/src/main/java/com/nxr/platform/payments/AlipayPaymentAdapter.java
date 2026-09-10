package com.nxr.platform.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nxr.platform.payments.PaymentModels.CheckoutContext;
import com.nxr.platform.payments.PaymentModels.HttpRequestData;
import com.nxr.platform.payments.PaymentModels.HttpResponseData;
import com.nxr.platform.payments.PaymentModels.ProviderCheckout;
import com.nxr.platform.payments.PaymentModels.ProviderContext;
import com.nxr.platform.payments.PaymentModels.VerifiedPayment;
import com.nxr.platform.payments.PaymentModels.WebhookRequest;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

@Component
final class AlipayPaymentAdapter implements PaymentAdapter {

    private static final DateTimeFormatter ALIPAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PaymentHttpTransport transport;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    AlipayPaymentAdapter(PaymentHttpTransport transport, ObjectMapper mapper) {
        this(transport, mapper, Clock.systemUTC());
    }

    AlipayPaymentAdapter(PaymentHttpTransport transport, ObjectMapper mapper, Clock clock) {
        this.transport = transport;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public String provider() {
        return PaymentProviderSpec.ALIPAY.code();
    }

    @Override
    public ProviderCheckout createCheckout(CheckoutContext context) {
        String outTradeNo = merchantOrderNo(context.orderNo());
        ObjectNode bizContent = mapper.createObjectNode();
        bizContent.put("out_trade_no", outTradeNo);
        bizContent.put("total_amount", money(context.amount()));
        bizContent.put("subject", "NXR grading order " + context.orderNo());
        bizContent.put("timeout_express", "30m");

        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("app_id", context.provider().credential("appId"));
        parameters.put("method", "alipay.trade.precreate");
        parameters.put("format", "JSON");
        parameters.put("charset", "utf-8");
        parameters.put("sign_type", "RSA2");
        parameters.put("timestamp", LocalDateTime.ofInstant(clock.instant(), ZoneId.of("Asia/Shanghai")).format(ALIPAY_TIME));
        parameters.put("version", "1.0");
        parameters.put("notify_url", context.provider().notifyUrl());
        parameters.put("biz_content", bizContent.toString());
        parameters.put("sign", PaymentCryptoService.rsaSha256Sign(canonicalize(parameters), context.provider().credential("merchantPrivateKey")));

        HttpResponseData response = send(new HttpRequestData(
            "POST", URI.create(context.provider().apiBaseUrl()),
            Map.of("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8", "Accept", "application/json"),
            formEncode(parameters)
        ));
        if (response.statusCode() != 200) {
            throw PaymentValidationException.badGateway("Alipay order creation failed with HTTP " + response.statusCode());
        }
        JsonNode root = parseJson(response.body(), "Alipay order creation");
        JsonNode result = root.path("alipay_trade_precreate_response");
        String responseSign = PaymentModels.requiredText(root, "/sign", "Alipay response signature");
        String signedResponseContent = extractTopLevelObject(response.body(), "alipay_trade_precreate_response");
        if (!PaymentCryptoService.rsaSha256Verify(signedResponseContent, responseSign, context.provider().credential("alipayPublicKey"))) {
            throw PaymentValidationException.badGateway("Alipay response signature is invalid");
        }
        if (!"10000".equals(result.path("code").asText())) {
            throw PaymentValidationException.badGateway("Alipay rejected order creation: " + safeCode(result.path("sub_code").asText()));
        }
        String qrCode = PaymentModels.requiredText(result, "/qr_code", "Alipay QR code");
        requireQrUrl(qrCode);
        String returnedOrderNo = result.path("out_trade_no").asText(outTradeNo);
        if (!outTradeNo.equals(returnedOrderNo)) {
            throw PaymentValidationException.badGateway("Alipay returned a different merchant order number");
        }
        return new ProviderCheckout(outTradeNo, null, qrCode, "created", response.body());
    }

    @Override
    public VerifiedPayment verifyWebhook(ProviderContext context, WebhookRequest request) {
        Map<String, String> form = request.form();
        if (form == null || form.isEmpty()) {
            throw PaymentValidationException.badRequest("Alipay notification form is empty");
        }
        String signType = form.getOrDefault("sign_type", "RSA2");
        if (!"RSA2".equals(signType)) {
            throw PaymentValidationException.unauthorized("Alipay notification must use RSA2");
        }
        String signature = form.getOrDefault("sign", "");
        if (!PaymentCryptoService.rsaSha256Verify(canonicalize(form), signature, context.credential("alipayPublicKey"))) {
            throw PaymentValidationException.unauthorized("Alipay notification signature is invalid");
        }
        if (!context.credential("appId").equals(form.get("app_id"))
            || !context.credential("sellerId").equals(form.get("seller_id"))) {
            throw PaymentValidationException.unauthorized("Alipay notification merchant identity does not match");
        }
        String tradeStatus = required(form, "trade_status", "Alipay trade status");
        String status = switch (tradeStatus) {
            case "TRADE_SUCCESS", "TRADE_FINISHED" -> "paid";
            case "TRADE_CLOSED" -> "failed";
            default -> "pending";
        };
        String tradeNo = required(form, "trade_no", "Alipay trade number");
        return new VerifiedPayment(
            form.getOrDefault("notify_id", tradeNo + ":" + tradeStatus),
            required(form, "out_trade_no", "Alipay merchant order number"),
            tradeNo,
            form.get("out_trade_no"),
            decimal(required(form, "total_amount", "Alipay total amount")),
            "CNY",
            status,
            request.body()
        );
    }

    static String canonicalize(Map<String, String> values) {
        TreeMap<String, String> sorted = new TreeMap<>();
        values.forEach((key, value) -> {
            if (!"sign".equals(key) && !"sign_type".equals(key) && value != null && !value.isEmpty()) {
                sorted.put(key, value);
            }
        });
        return sorted.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue())
            .collect(java.util.stream.Collectors.joining("&"));
    }

    /** Extracts the exact provider response object because whitespace and escaping are part of Alipay's signature input. */
    static String extractTopLevelObject(String json, String field) {
        String marker = "\"" + field + "\"";
        int fieldIndex = json.indexOf(marker);
        int colon = fieldIndex < 0 ? -1 : json.indexOf(':', fieldIndex + marker.length());
        int start = colon < 0 ? -1 : json.indexOf('{', colon + 1);
        if (start < 0) {
            throw PaymentValidationException.badGateway("Alipay signed response object is missing");
        }
        boolean quoted = false;
        boolean escaped = false;
        int depth = 0;
        for (int index = start; index < json.length(); index++) {
            char value = json.charAt(index);
            if (quoted) {
                if (escaped) {
                    escaped = false;
                } else if (value == '\\') {
                    escaped = true;
                } else if (value == '"') {
                    quoted = false;
                }
                continue;
            }
            if (value == '"') {
                quoted = true;
            } else if (value == '{') {
                depth++;
            } else if (value == '}' && --depth == 0) {
                return json.substring(start, index + 1);
            }
        }
        throw PaymentValidationException.badGateway("Alipay signed response object is incomplete");
    }

    private static String formEncode(Map<String, String> values) {
        return values.entrySet().stream()
            .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
            .collect(java.util.stream.Collectors.joining("&"));
    }

    private HttpResponseData send(HttpRequestData request) {
        try {
            return transport.send(request);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw PaymentValidationException.badGateway("Alipay request was interrupted");
        } catch (IOException exception) {
            throw PaymentValidationException.badGateway("Alipay could not be reached");
        }
    }

    private JsonNode parseJson(String value, String label) {
        try {
            return mapper.readTree(value);
        } catch (IOException exception) {
            throw PaymentValidationException.badGateway(label + " returned invalid JSON");
        }
    }

    private static String required(Map<String, String> values, String key, String label) {
        String value = values.getOrDefault(key, "").trim();
        if (value.isEmpty() || value.length() > 512) {
            throw PaymentValidationException.badRequest(label + " is missing or invalid");
        }
        return value;
    }

    private static BigDecimal decimal(String value) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            throw PaymentValidationException.badRequest("Alipay amount is invalid");
        }
    }

    private static String money(BigDecimal amount) {
        try {
            return amount.setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString();
        } catch (ArithmeticException exception) {
            throw PaymentValidationException.badRequest("Alipay amount must have at most two decimal places");
        }
    }

    private static String merchantOrderNo(String orderNo) {
        String clean = orderNo == null ? "" : orderNo.trim();
        if (!clean.matches("[A-Za-z0-9_-]{6,64}")) {
            throw PaymentValidationException.badRequest("Order number is not valid for Alipay");
        }
        return clean;
    }

    private static String safeCode(String code) {
        return code != null && code.matches("[A-Za-z0-9_.-]{1,80}") ? code : "provider_error";
    }

    private static void requireQrUrl(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null
                || !(host.equals("alipay.com") || host.endsWith(".alipay.com") || host.endsWith(".alipaydev.com"))) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw PaymentValidationException.badGateway("Alipay returned a non-allowlisted QR URL");
        }
    }
}
