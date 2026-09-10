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
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

@Component
final class WechatNativePaymentAdapter implements PaymentAdapter {

    private static final String API_PATH = "/v3/pay/transactions/native";

    private final PaymentHttpTransport transport;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    WechatNativePaymentAdapter(PaymentHttpTransport transport, ObjectMapper mapper) {
        this(transport, mapper, Clock.systemUTC());
    }

    WechatNativePaymentAdapter(PaymentHttpTransport transport, ObjectMapper mapper, Clock clock) {
        this.transport = transport;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public String provider() {
        return PaymentProviderSpec.WECHAT_PAY_NATIVE.code();
    }

    @Override
    public ProviderCheckout createCheckout(CheckoutContext context) {
        ProviderContext provider = context.provider();
        String outTradeNo = merchantOrderNo(context.orderNo());
        ObjectNode payload = mapper.createObjectNode();
        payload.put("appid", provider.credential("appId"));
        payload.put("mchid", provider.credential("merchantId"));
        payload.put("description", "NXR grading order " + context.orderNo());
        payload.put("out_trade_no", outTradeNo);
        payload.put("notify_url", provider.notifyUrl());
        ObjectNode amount = payload.putObject("amount");
        amount.put("total", fen(context.amount()));
        amount.put("currency", context.currency());
        String body = payload.toString();
        long timestamp = clock.instant().getEpochSecond();
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String authorization = buildAuthorization(
            "POST", API_PATH, timestamp, nonce, body,
            provider.credential("merchantId"), provider.credential("merchantSerialNo"), provider.credential("merchantPrivateKey")
        );
        HttpResponseData response = send(new HttpRequestData(
            "POST", URI.create(provider.apiBaseUrl() + API_PATH),
            Map.of("Authorization", authorization, "Accept", "application/json", "Content-Type", "application/json"), body
        ));
        if (response.statusCode() != 200) {
            throw PaymentValidationException.badGateway("WeChat Pay order creation failed with HTTP " + response.statusCode());
        }
        verifyWechatSignature(
            firstHeader(response, "Wechatpay-Timestamp"), firstHeader(response, "Wechatpay-Nonce"),
            firstHeader(response, "Wechatpay-Serial"), firstHeader(response, "Wechatpay-Signature"),
            response.body(), provider
        );
        JsonNode json = parseJson(response.body(), "WeChat Pay order creation");
        String codeUrl = PaymentModels.requiredText(json, "/code_url", "WeChat Pay code URL");
        if (!codeUrl.startsWith("weixin://wxpay/bizpayurl?")) {
            throw PaymentValidationException.badGateway("WeChat Pay returned an invalid code URL");
        }
        return new ProviderCheckout(outTradeNo, null, codeUrl, "created", response.body());
    }

    @Override
    public VerifiedPayment verifyWebhook(ProviderContext context, WebhookRequest request) {
        String timestamp = request.header("Wechatpay-Timestamp");
        String nonce = request.header("Wechatpay-Nonce");
        verifyWechatSignature(
            timestamp, nonce, request.header("Wechatpay-Serial"), request.header("Wechatpay-Signature"), request.body(), context
        );
        try {
            long signedAt = Long.parseLong(timestamp);
            if (Math.abs(clock.instant().getEpochSecond() - signedAt) > 300) {
                throw PaymentValidationException.unauthorized("WeChat Pay notification timestamp is outside the allowed window");
            }
        } catch (NumberFormatException exception) {
            throw PaymentValidationException.unauthorized("WeChat Pay notification timestamp is invalid");
        }
        JsonNode notification = parseJson(request.body(), "WeChat Pay notification");
        JsonNode resource = notification.path("resource");
        if (!"AEAD_AES_256_GCM".equals(resource.path("algorithm").asText())) {
            throw PaymentValidationException.unauthorized("Unsupported WeChat Pay notification encryption");
        }
        String decrypted = PaymentCryptoService.decryptAesGcm(
            context.credential("apiV3Key"), resource.path("associated_data").asText(null),
            PaymentModels.requiredText(resource, "/nonce", "WeChat Pay resource nonce"),
            PaymentModels.requiredText(resource, "/ciphertext", "WeChat Pay resource ciphertext")
        );
        JsonNode transaction = parseJson(decrypted, "WeChat Pay transaction");
        if (!context.credential("appId").equals(transaction.path("appid").asText())
            || !context.credential("merchantId").equals(transaction.path("mchid").asText())) {
            throw PaymentValidationException.unauthorized("WeChat Pay notification merchant identity does not match");
        }
        String state = PaymentModels.requiredText(transaction, "/trade_state", "WeChat Pay trade state");
        return new VerifiedPayment(
            PaymentModels.requiredText(notification, "/id", "WeChat Pay event ID"),
            PaymentModels.requiredText(transaction, "/out_trade_no", "WeChat Pay merchant order number"),
            PaymentModels.requiredText(transaction, "/transaction_id", "WeChat Pay transaction ID"),
            transaction.path("out_trade_no").asText(),
            new BigDecimal(transaction.at("/amount/total").asText()).movePointLeft(2),
            PaymentModels.requiredText(transaction, "/amount/currency", "WeChat Pay currency"),
            "SUCCESS".equals(state) ? "paid" : (SetLike.FAILURE_STATES.contains(state) ? "failed" : "pending"),
            request.body()
        );
    }

    static String buildAuthorization(
        String method, String canonicalPath, long timestamp, String nonce, String body,
        String merchantId, String merchantSerial, String privateKey
    ) {
        String message = method + "\n" + canonicalPath + "\n" + timestamp + "\n" + nonce + "\n" + body + "\n";
        String signature = PaymentCryptoService.rsaSha256Sign(message, privateKey);
        return "WECHATPAY2-SHA256-RSA2048 mchid=\"" + merchantId + "\",nonce_str=\"" + nonce
            + "\",timestamp=\"" + timestamp + "\",serial_no=\"" + merchantSerial + "\",signature=\"" + signature + "\"";
    }

    private static void verifyWechatSignature(
        String timestamp, String nonce, String serial, String encodedSignature, String body, ProviderContext context
    ) {
        if (timestamp == null || timestamp.isBlank() || nonce == null || nonce.isBlank()
            || encodedSignature == null || encodedSignature.isBlank()
            || !context.credential("platformSerialNo").equalsIgnoreCase(serial)) {
            throw PaymentValidationException.unauthorized("WeChat Pay signature headers are invalid");
        }
        String message = timestamp + "\n" + nonce + "\n" + body + "\n";
        if (!PaymentCryptoService.rsaSha256Verify(message, encodedSignature, context.credential("platformPublicKey"))) {
            throw PaymentValidationException.unauthorized("WeChat Pay signature is invalid");
        }
    }

    private HttpResponseData send(HttpRequestData request) {
        try {
            return transport.send(request);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw PaymentValidationException.badGateway("WeChat Pay request was interrupted");
        } catch (IOException exception) {
            throw PaymentValidationException.badGateway("WeChat Pay could not be reached");
        }
    }

    private JsonNode parseJson(String value, String label) {
        try {
            return mapper.readTree(value);
        } catch (IOException exception) {
            throw PaymentValidationException.badGateway(label + " returned invalid JSON");
        }
    }

    private static String firstHeader(HttpResponseData response, String name) {
        return response.headers().entrySet().stream()
            .filter(entry -> entry.getKey().equalsIgnoreCase(name))
            .flatMap(entry -> entry.getValue().stream()).findFirst().orElse("");
    }

    private static long fen(BigDecimal amount) {
        try {
            return amount.movePointRight(2).longValueExact();
        } catch (ArithmeticException exception) {
            throw PaymentValidationException.badRequest("WeChat Pay amount must have at most two decimal places");
        }
    }

    private static String merchantOrderNo(String orderNo) {
        String clean = orderNo == null ? "" : orderNo.trim();
        if (clean.matches("[A-Za-z0-9_*-]{6,32}")) {
            return clean;
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(clean.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "NXR" + HexFormat.of().formatHex(digest, 0, 14).toUpperCase(Locale.ROOT);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class SetLike {
        private static final java.util.Set<String> FAILURE_STATES = java.util.Set.of("CLOSED", "PAYERROR", "REFUND");
    }
}
