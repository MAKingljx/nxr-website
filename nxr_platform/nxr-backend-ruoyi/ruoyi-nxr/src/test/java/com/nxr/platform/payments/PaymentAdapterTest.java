package com.nxr.platform.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nxr.platform.payments.PaymentModels.CheckoutContext;
import com.nxr.platform.payments.PaymentModels.HttpRequestData;
import com.nxr.platform.payments.PaymentModels.HttpResponseData;
import com.nxr.platform.payments.PaymentModels.ProviderCheckout;
import com.nxr.platform.payments.PaymentModels.ProviderContext;
import com.nxr.platform.payments.PaymentModels.VerifiedPayment;
import com.nxr.platform.payments.PaymentModels.WebhookRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PaymentAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void paypalCreatesServerPricedSandboxOrderWithoutRealNetwork() {
        ArrayDeque<HttpResponseData> responses = new ArrayDeque<>();
        responses.add(new HttpResponseData(200, Map.of(), "{\"access_token\":\"sandbox-token\"}"));
        responses.add(new HttpResponseData(201, Map.of(), """
            {"id":"PAYPAL-ORDER-1","status":"CREATED","links":[
              {"rel":"approve","href":"https://www.sandbox.paypal.com/checkoutnow?token=PAYPAL-ORDER-1"}
            ]}
            """));
        List<HttpRequestData> requests = new ArrayList<>();
        PaymentHttpTransport transport = request -> {
            requests.add(request);
            return responses.removeFirst();
        };
        PayPalPaymentAdapter adapter = new PayPalPaymentAdapter(transport, mapper);
        ProviderContext provider = new ProviderContext(
            "paypal", "sandbox", "https://api-m.sandbox.paypal.com", "https://example.com/webhook",
            "https://example.com/account/orders", Map.of("clientId", "client-id", "clientSecret", "secret", "webhookId", "WH-123456")
        );

        ProviderCheckout checkout = adapter.createCheckout(new CheckoutContext(
            "NXR-ORDER-100", 1L, new BigDecimal("28.40"), "USD", "idem-key-123", provider
        ));

        assertThat(checkout.providerOrderId()).isEqualTo("PAYPAL-ORDER-1");
        assertThat(checkout.paymentUrl()).startsWith("https://www.sandbox.paypal.com/");
        assertThat(requests).hasSize(2);
        assertThat(requests.get(1).body()).contains("\"value\":\"28.40\"")
            .contains("\"custom_id\":\"NXR-ORDER-100\"")
            .contains("https://example.com/account/orders/NXR-ORDER-100");
        assertThat(requests.get(1).headers()).containsEntry("PayPal-Request-Id", "idem-key-123");
    }

    @Test
    void alipayAcceptsOnlyRsa2VerifiedMerchantNotification() throws Exception {
        KeyPair alipay = rsaKeyPair();
        ProviderContext provider = new ProviderContext(
            "alipay", "sandbox", "https://openapi.alipaydev.com/gateway.do", "https://example.com/webhooks/alipay", "",
            Map.of(
                "appId", "2026000012345678", "sellerId", "2088000012345678",
                "merchantPrivateKey", PaymentCryptoServiceTest.pem("PRIVATE KEY", rsaKeyPair().getPrivate().getEncoded()),
                "alipayPublicKey", PaymentCryptoServiceTest.pem("PUBLIC KEY", alipay.getPublic().getEncoded())
            )
        );
        Map<String, String> unsigned = Map.of(
            "app_id", "2026000012345678", "seller_id", "2088000012345678",
            "out_trade_no", "NXR-ORDER-100", "trade_no", "202609080001",
            "trade_status", "TRADE_SUCCESS", "total_amount", "88.00", "notify_id", "notify-100"
        );
        String signature = PaymentCryptoService.rsaSha256Sign(
            AlipayPaymentAdapter.canonicalize(unsigned), PaymentCryptoServiceTest.pem("PRIVATE KEY", alipay.getPrivate().getEncoded())
        );
        Map<String, String> signed = new java.util.LinkedHashMap<>(unsigned);
        signed.put("sign_type", "RSA2");
        signed.put("sign", signature);
        AlipayPaymentAdapter adapter = new AlipayPaymentAdapter(request -> {
            throw new AssertionError("Webhook verification must not call the provider");
        }, mapper);

        VerifiedPayment payment = adapter.verifyWebhook(provider, new WebhookRequest(Map.of(), "encoded-form", signed));

        assertThat(payment.status()).isEqualTo("paid");
        assertThat(payment.amount()).isEqualByComparingTo("88.00");
        assertThat(payment.providerOrderId()).isEqualTo("NXR-ORDER-100");
    }

    @Test
    void alipayPreservesExactSignedResponseObject() {
        String body = "{\"alipay_trade_precreate_response\" : { \"code\" : \"10000\", \"note\":\"brace } in text\" },\"sign\":\"x\"}";

        assertThat(AlipayPaymentAdapter.extractTopLevelObject(body, "alipay_trade_precreate_response"))
            .isEqualTo("{ \"code\" : \"10000\", \"note\":\"brace } in text\" }");
    }

    @Test
    void wechatNativeVerifiesSignedResponseAndReturnsQrPayload() throws Exception {
        KeyPair merchant = rsaKeyPair();
        KeyPair platform = rsaKeyPair();
        Instant now = Instant.parse("2026-09-08T02:00:00Z");
        String responseBody = "{\"code_url\":\"weixin://wxpay/bizpayurl?pr=test123\"}";
        String responseNonce = "response-nonce";
        String responseTimestamp = Long.toString(now.getEpochSecond());
        String responseSignature = PaymentCryptoService.rsaSha256Sign(
            responseTimestamp + "\n" + responseNonce + "\n" + responseBody + "\n",
            PaymentCryptoServiceTest.pem("PRIVATE KEY", platform.getPrivate().getEncoded())
        );
        List<HttpRequestData> requests = new ArrayList<>();
        PaymentHttpTransport transport = request -> {
            requests.add(request);
            return new HttpResponseData(200, Map.of(
                "Wechatpay-Timestamp", List.of(responseTimestamp),
                "Wechatpay-Nonce", List.of(responseNonce),
                "Wechatpay-Serial", List.of("ABCDEF0123456789"),
                "Wechatpay-Signature", List.of(responseSignature)
            ), responseBody);
        };
        ProviderContext provider = new ProviderContext(
            "wechat_pay_native", "live", "https://api.mch.weixin.qq.com", "https://example.com/webhooks/wechat_pay_native", "",
            Map.of(
                "appId", "wx1234567890", "merchantId", "1900000109", "merchantSerialNo", "1234567890ABCDEF",
                "merchantPrivateKey", PaymentCryptoServiceTest.pem("PRIVATE KEY", merchant.getPrivate().getEncoded()),
                "apiV3Key", "0123456789abcdef0123456789abcdef", "platformSerialNo", "ABCDEF0123456789",
                "platformPublicKey", PaymentCryptoServiceTest.pem("PUBLIC KEY", platform.getPublic().getEncoded())
            )
        );
        WechatNativePaymentAdapter adapter = new WechatNativePaymentAdapter(
            transport, mapper, Clock.fixed(now, ZoneOffset.UTC)
        );

        ProviderCheckout checkout = adapter.createCheckout(new CheckoutContext(
            "NXR-ORDER-100", 1L, new BigDecimal("12.34"), "CNY", "idem-wechat-100", provider
        ));

        assertThat(checkout.qrPayload()).isEqualTo("weixin://wxpay/bizpayurl?pr=test123");
        assertThat(requests.get(0).body()).contains("\"total\":1234").contains("\"currency\":\"CNY\"");
        assertThat(requests.get(0).headers().get("Authorization")).startsWith("WECHATPAY2-SHA256-RSA2048 ");
    }

    private static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
}
