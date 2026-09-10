package com.nxr.platform.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** AES-GCM keeps recipients, customer messages and one-time URLs out of plaintext DB rows. */
final class NotificationPayloadCipher {

    private static final int IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom;
    private final SecretKeySpec key;

    NotificationPayloadCipher(ObjectMapper objectMapper, String configuredKey) {
        this(objectMapper, configuredKey, new SecureRandom());
    }

    NotificationPayloadCipher(ObjectMapper objectMapper, String configuredKey, SecureRandom secureRandom) {
        this.objectMapper = objectMapper;
        this.secureRandom = secureRandom;
        String cleaned = configuredKey == null ? "" : configuredKey.trim();
        this.key = cleaned.length() < 32 ? null : new SecretKeySpec(sha256(cleaned.getBytes(StandardCharsets.UTF_8)), "AES");
    }

    boolean isConfigured() {
        return key != null;
    }

    String encrypt(NotificationOutboxService.OutboxPayload payload) {
        requireKey();
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plaintext = objectMapper.writeValueAsBytes(payload);
            byte[] encrypted = cipher.doFinal(plaintext);
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to protect notification payload", exception);
        }
    }

    NotificationOutboxService.OutboxPayload decrypt(String value) {
        requireKey();
        try {
            byte[] packed = Base64.getUrlDecoder().decode(value);
            if (packed.length <= IV_LENGTH) {
                throw new IllegalArgumentException("Invalid notification payload");
            }
            ByteBuffer buffer = ByteBuffer.wrap(packed);
            byte[] iv = new byte[IV_LENGTH];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return objectMapper.readValue(cipher.doFinal(encrypted), NotificationOutboxService.OutboxPayload.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to open notification payload", exception);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException("Notification payload encryption is not configured");
        }
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
