package com.nxr.platform.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class PaymentCryptoServiceTest {

    @Test
    void encryptsWithAuthenticatedProviderAndFieldContext() {
        byte[] key = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        PaymentCryptoService crypto = new PaymentCryptoService(key);

        String encrypted = crypto.encrypt("merchant-secret", "paypal", "credentials");

        assertThat(encrypted).startsWith("v1:").doesNotContain("merchant-secret");
        assertThat(crypto.decrypt(encrypted, "paypal", "credentials")).isEqualTo("merchant-secret");
        assertThatThrownBy(() -> crypto.decrypt(encrypted, "alipay", "credentials"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rsa2SignaturesRoundTripWithoutExposingPrivateMaterial() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        String privatePem = pem("PRIVATE KEY", pair.getPrivate().getEncoded());
        String publicPem = pem("PUBLIC KEY", pair.getPublic().getEncoded());

        String signature = PaymentCryptoService.rsaSha256Sign("amount=12.34&currency=CNY", privatePem);

        assertThat(PaymentCryptoService.rsaSha256Verify("amount=12.34&currency=CNY", signature, publicPem)).isTrue();
        assertThat(PaymentCryptoService.rsaSha256Verify("amount=99.99&currency=CNY", signature, publicPem)).isFalse();
    }

    static String pem(String type, byte[] encoded) {
        return "-----BEGIN " + type + "-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(encoded)
            + "\n-----END " + type + "-----";
    }
}
