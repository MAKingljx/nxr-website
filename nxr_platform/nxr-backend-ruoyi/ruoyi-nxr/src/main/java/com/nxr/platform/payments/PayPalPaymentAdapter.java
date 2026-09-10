package com.nxr.platform.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nxr.platform.payments.PaymentModels.CaptureResult;
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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
final class PayPalPaymentAdapter implements PaymentAdapter {

    private final PaymentHttpTransport transport;
    private final ObjectMapper mapper;
    private volatile TokenCache tokenCache;

    PayPalPaymentAdapter(PaymentHttpTransport transport, ObjectMapper mapper) {
        this.transport = transport;
        this.mapper = mapper;
    }

    @Override
    public String provider() {
        return PaymentProviderSpec.PAYPAL.code();
    }

    @Override
    public ProviderCheckout createCheckout(CheckoutContext context) {
        ProviderContext provider = context.provider();
        String token = accessToken(provider);
        ObjectNode root = mapper.createObjectNode();
        root.put("intent", "CAPTURE");
        ObjectNode unit = root.putArray("purchase_units").addObject();
        unit.put("reference_id", context.orderNo());
        unit.put("custom_id", context.orderNo());
        unit.put("invoice_id", context.orderNo());
        ObjectNode amount = unit.putObject("amount");
        amount.put("currency_code", context.currency());
        amount.put("value", money(context.amount()));
        ObjectNode application = root.putObject("application_context");
        application.put("return_url", orderReturnUrl(provider.returnUrl(), context.orderNo(), false));
        application.put("cancel_url", orderReturnUrl(provider.returnUrl(), context.orderNo(), true));
        application.put("user_action", "PAY_NOW");
        HttpResponseData response = send(new HttpRequestData(
            "POST", URI.create(provider.apiBaseUrl() + "/v2/checkout/orders"),
            Map.of(
                "Authorization", "Bearer " + token,
                "Content-Type", "application/json",
                "Accept", "application/json",
                "PayPal-Request-Id", context.idempotencyKey(),
                "Prefer", "return=representation"
            ), root.toString()
        ));
        JsonNode json = requireJson(response, 201, "PayPal order creation");
        String id = PaymentModels.requiredText(json, "/id", "PayPal order ID");
        String status = PaymentModels.requiredText(json, "/status", "PayPal order status");
        String approvalUrl = "";
        for (JsonNode link : json.path("links")) {
            if ("approve".equals(link.path("rel").asText()) || "payer-action".equals(link.path("rel").asText())) {
                approvalUrl = link.path("href").asText();
                break;
            }
        }
        requirePayPalUrl(approvalUrl, provider.mode());
        return new ProviderCheckout(id, approvalUrl, null, status.toLowerCase(), response.body());
    }

    @Override
    public CaptureResult capture(CheckoutContext context, String providerOrderId) {
        ProviderContext provider = context.provider();
        String remoteId = requireIdentifier(providerOrderId, "PayPal order ID");
        HttpResponseData response = send(new HttpRequestData(
            "POST", URI.create(provider.apiBaseUrl() + "/v2/checkout/orders/" + remoteId + "/capture"),
            Map.of(
                "Authorization", "Bearer " + accessToken(provider),
                "Content-Type", "application/json",
                "Accept", "application/json",
                "PayPal-Request-Id", context.idempotencyKey() + "-capture",
                "Prefer", "return=representation"
            ), "{}"
        ));
        JsonNode json = requireJson(response, 201, "PayPal order capture");
        String status = PaymentModels.requiredText(json, "/status", "PayPal capture status");
        JsonNode capture = json.at("/purchase_units/0/payments/captures/0");
        String captureId = PaymentModels.requiredText(capture, "/id", "PayPal capture ID");
        BigDecimal amount = decimal(capture.at("/amount/value"), "PayPal capture amount");
        String currency = PaymentModels.requiredText(capture, "/amount/currency_code", "PayPal capture currency");
        String orderReference = json.at("/purchase_units/0/custom_id").asText(context.orderNo());
        VerifiedPayment payment = new VerifiedPayment(
            "capture:" + captureId, remoteId, captureId, orderReference, amount, currency,
            "COMPLETED".equals(status) && "COMPLETED".equals(capture.path("status").asText()) ? "paid" : "pending",
            response.body()
        );
        return new CaptureResult(payment, response.body());
    }

    @Override
    public VerifiedPayment verifyWebhook(ProviderContext context, WebhookRequest request) {
        JsonNode event = parseJson(request.body(), "PayPal webhook");
        String eventId = PaymentModels.requiredText(event, "/id", "PayPal event ID");
        String eventType = PaymentModels.requiredText(event, "/event_type", "PayPal event type");
        if (!eventId.matches("[A-Za-z0-9_-]{6,100}") || !Set.of(
            "PAYMENT.CAPTURE.COMPLETED", "PAYMENT.CAPTURE.DENIED",
            "PAYMENT.CAPTURE.REFUNDED", "PAYMENT.CAPTURE.REVERSED"
        ).contains(eventType)) {
            throw PaymentValidationException.badRequest("Unsupported PayPal webhook event");
        }
        String certUrl = requiredHeader(request, "PAYPAL-CERT-URL");
        requirePayPalCertUrl(certUrl, context.mode());
        String transmissionTime = requiredHeader(request, "PAYPAL-TRANSMISSION-TIME");
        try {
            if (Math.abs(Instant.now().getEpochSecond() - OffsetDateTime.parse(transmissionTime).toEpochSecond()) > 600) {
                throw PaymentValidationException.unauthorized("PayPal webhook timestamp is outside the allowed window");
            }
        } catch (java.time.format.DateTimeParseException exception) {
            throw PaymentValidationException.unauthorized("PayPal webhook timestamp is invalid");
        }
        ObjectNode verification = mapper.createObjectNode();
        verification.put("transmission_id", requiredHeader(request, "PAYPAL-TRANSMISSION-ID"));
        verification.put("transmission_time", transmissionTime);
        verification.put("cert_url", certUrl);
        verification.put("auth_algo", requiredHeader(request, "PAYPAL-AUTH-ALGO"));
        verification.put("transmission_sig", requiredHeader(request, "PAYPAL-TRANSMISSION-SIG"));
        verification.put("webhook_id", context.credential("webhookId"));
        verification.set("webhook_event", event);
        HttpResponseData response = send(new HttpRequestData(
            "POST", URI.create(context.apiBaseUrl() + "/v1/notifications/verify-webhook-signature"),
            Map.of("Authorization", "Bearer " + accessToken(context), "Content-Type", "application/json", "Accept", "application/json"),
            verification.toString()
        ));
        JsonNode verified = requireJson(response, 200, "PayPal webhook verification");
        if (!"SUCCESS".equals(verified.path("verification_status").asText())) {
            throw PaymentValidationException.unauthorized("PayPal webhook signature is invalid");
        }
        String status = switch (eventType) {
            case "PAYMENT.CAPTURE.COMPLETED" -> "paid";
            case "PAYMENT.CAPTURE.DENIED" -> "failed";
            case "PAYMENT.CAPTURE.REFUNDED" -> "refunded";
            case "PAYMENT.CAPTURE.REVERSED" -> "reversed";
            default -> "pending";
        };
        JsonNode resource = event.path("resource");
        String relatedOrderId = resource.at("/supplementary_data/related_ids/order_id").asText("");
        String relatedCaptureId = resource.at("/supplementary_data/related_ids/capture_id").asText("");
        String providerReference = relatedOrderId.isBlank() ? relatedCaptureId : relatedOrderId;
        String merchantOrderNo = resource.path("custom_id").asText("");
        return new VerifiedPayment(
            eventId,
            requireIdentifier(providerReference, "PayPal related order or capture ID"),
            PaymentModels.requiredText(resource, "/id", "PayPal payment event resource ID"),
            merchantOrderNo,
            decimal(resource.at("/amount/value"), "PayPal webhook amount"),
            PaymentModels.requiredText(resource, "/amount/currency_code", "PayPal webhook currency"),
            status,
            request.body()
        );
    }

    private synchronized String accessToken(ProviderContext provider) {
        String identity = provider.apiBaseUrl() + "|" + provider.credential("clientId");
        TokenCache cached = tokenCache;
        if (cached != null && cached.identity().equals(identity) && cached.expiresAt().isAfter(Instant.now().plusSeconds(30))) {
            return cached.token();
        }
        String basic = Base64.getEncoder().encodeToString(
            (provider.credential("clientId") + ":" + provider.credential("clientSecret")).getBytes(StandardCharsets.UTF_8)
        );
        HttpResponseData response = send(new HttpRequestData(
            "POST", URI.create(provider.apiBaseUrl() + "/v1/oauth2/token"),
            Map.of("Authorization", "Basic " + basic, "Content-Type", "application/x-www-form-urlencoded", "Accept", "application/json"),
            "grant_type=" + URLEncoder.encode("client_credentials", StandardCharsets.UTF_8)
        ));
        JsonNode json = requireJson(response, 200, "PayPal authentication");
        String token = PaymentModels.requiredText(json, "/access_token", "PayPal access token");
        long expiresIn = Math.max(60, Math.min(36000, json.path("expires_in").asLong(300)));
        tokenCache = new TokenCache(token, Instant.now().plusSeconds(expiresIn), identity);
        return token;
    }

    private HttpResponseData send(HttpRequestData request) {
        try {
            return transport.send(request);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw PaymentValidationException.badGateway("Payment provider request was interrupted");
        } catch (IOException exception) {
            throw PaymentValidationException.badGateway("Payment provider could not be reached");
        }
    }

    private JsonNode requireJson(HttpResponseData response, int successStatus, String operation) {
        if (response.statusCode() != successStatus && !(successStatus == 201 && response.statusCode() == 200)) {
            throw PaymentValidationException.badGateway(operation + " failed with HTTP " + response.statusCode());
        }
        return parseJson(response.body(), operation);
    }

    private JsonNode parseJson(String body, String label) {
        try {
            return mapper.readTree(body);
        } catch (IOException exception) {
            throw PaymentValidationException.badGateway(label + " returned invalid JSON");
        }
    }

    private static String requiredHeader(WebhookRequest request, String name) {
        String value = request.header(name);
        if (value.isBlank() || value.length() > 600) {
            throw PaymentValidationException.unauthorized("Required PayPal webhook header is missing or invalid");
        }
        return value;
    }

    private record TokenCache(String token, Instant expiresAt, String identity) {
    }

    private static BigDecimal decimal(JsonNode value, String label) {
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException exception) {
            throw PaymentValidationException.badGateway(label + " is invalid");
        }
    }

    private static String money(BigDecimal value) {
        return value.setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString();
    }

    private static String requireIdentifier(String value, String label) {
        String clean = value == null ? "" : value.trim();
        if (!clean.matches("[A-Za-z0-9_-]{6,80}")) {
            throw PaymentValidationException.badRequest(label + " is invalid");
        }
        return clean;
    }

    private static void requirePayPalUrl(String url, String mode) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            boolean allowed = "https".equals(uri.getScheme()) && uri.getUserInfo() == null
                && (host.equals("www.paypal.com") || host.equals("www.sandbox.paypal.com"));
            if (!allowed || ("sandbox".equals(mode) && !host.contains("sandbox"))) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw PaymentValidationException.badGateway("PayPal approval URL is not allowlisted");
        }
    }

    private static void requirePayPalCertUrl(String url, String mode) {
        try {
            URI uri = URI.create(url);
            String expectedHost = "sandbox".equals(mode) ? "api-m.sandbox.paypal.com" : "api-m.paypal.com";
            if (!"https".equals(uri.getScheme()) || !expectedHost.equalsIgnoreCase(uri.getHost())
                || uri.getUserInfo() != null || !uri.getPath().startsWith("/v1/notifications/certs/")) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw PaymentValidationException.unauthorized("PayPal certificate URL is not allowlisted");
        }
    }

    private static String orderReturnUrl(String configuredBase, String orderNo, boolean cancelled) {
        try {
            URI base = URI.create(configuredBase);
            String path = base.getPath() == null ? "" : base.getPath().replaceAll("/+$", "");
            String encodedOrderNo = URLEncoder.encode(orderNo, StandardCharsets.UTF_8).replace("+", "%20");
            if (!path.endsWith("/" + encodedOrderNo)) {
                path += "/" + encodedOrderNo;
            }
            String query = base.getRawQuery();
            if (cancelled) {
                query = query == null || query.isBlank() ? "payment=cancelled" : query + "&payment=cancelled";
            }
            return new URI(base.getScheme(), base.getRawAuthority(), path, query, null).toASCIIString();
        } catch (java.net.URISyntaxException | IllegalArgumentException exception) {
            throw PaymentValidationException.badRequest("PayPal return URL cannot be resolved for the order");
        }
    }
}
