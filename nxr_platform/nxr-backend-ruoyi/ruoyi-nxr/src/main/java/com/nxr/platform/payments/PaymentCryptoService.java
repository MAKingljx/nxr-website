package com.nxr.platform.payments;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.security.cert.CertificateFactory;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

/** AES-GCM credential storage and RSA utilities. Key material is never logged. */
@Component
final class PaymentCryptoService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<PosixFilePermission> UNSAFE_KEY_PERMISSIONS = EnumSet.of(
        PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE
    );

    private final byte[] masterKey;

    @Autowired
    PaymentCryptoService() {
        this(loadMasterKey(System.getenv("NXR_PAYMENT_MASTER_KEY"), System.getenv("NXR_PAYMENT_MASTER_KEY_FILE")));
    }

    PaymentCryptoService(byte[] masterKey) {
        this.masterKey = masterKey == null ? null : masterKey.clone();
        if (this.masterKey != null && this.masterKey.length != 32) {
            throw new IllegalArgumentException("Payment master key must contain exactly 32 bytes");
        }
    }

    boolean available() {
        return masterKey != null;
    }

    String encrypt(String plaintext, String provider, String field) {
        requireMasterKey();
        try {
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(masterKey, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(provider, field));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, combined, 0, nonce.length);
            System.arraycopy(ciphertext, 0, combined, nonce.length, ciphertext.length);
            return "v1:" + Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to encrypt payment configuration", exception);
        }
    }

    String decrypt(String encrypted, String provider, String field) {
        requireMasterKey();
        if (encrypted == null || !encrypted.startsWith("v1:")) {
            throw new IllegalStateException("Unsupported encrypted payment credential format");
        }
        try {
            byte[] combined = Base64.getDecoder().decode(encrypted.substring(3));
            if (combined.length < 29) {
                throw new IllegalStateException("Encrypted payment credential is invalid");
            }
            byte[] nonce = java.util.Arrays.copyOfRange(combined, 0, 12);
            byte[] ciphertext = java.util.Arrays.copyOfRange(combined, 12, combined.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(masterKey, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(provider, field));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("Unable to decrypt payment configuration", exception);
        }
    }

    static PrivateKey readPrivateKey(String pem) {
        try {
            byte[] der = decodePem(pem, "PRIVATE KEY");
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw PaymentValidationException.badRequest("Merchant private key must be a PKCS#8 RSA private key");
        }
    }

    static PublicKey readPublicKey(String pem) {
        try {
            if (pem != null && pem.contains("BEGIN CERTIFICATE")) {
                byte[] der = decodePem(pem, "CERTIFICATE");
                return CertificateFactory.getInstance("X.509")
                    .generateCertificate(new java.io.ByteArrayInputStream(der)).getPublicKey();
            }
            byte[] der = decodePem(pem, "PUBLIC KEY");
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw PaymentValidationException.badRequest("Provider public key must be an X.509 RSA public key");
        }
    }

    static String rsaSha256Sign(String message, String privateKeyPem) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(readPrivateKey(privateKeyPem));
            signature.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to sign provider request", exception);
        }
    }

    static boolean rsaSha256Verify(String message, String encodedSignature, String publicKeyPem) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(readPublicKey(publicKeyPem));
            signature.update(message.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(encodedSignature));
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            return false;
        }
    }

    static String decryptAesGcm(String key, String associatedData, String nonce, String ciphertext) {
        if (key == null || key.getBytes(StandardCharsets.UTF_8).length != 32) {
            throw PaymentValidationException.badRequest("WeChat Pay API v3 key must be exactly 32 UTF-8 bytes");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
            if (associatedData != null) {
                cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            }
            return new String(cipher.doFinal(Base64.getDecoder().decode(ciphertext)), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw PaymentValidationException.unauthorized("WeChat Pay notification could not be decrypted");
        }
    }

    private void requireMasterKey() {
        if (masterKey == null) {
            throw PaymentValidationException.unavailable(
                "Payment credential encryption is unavailable; configure NXR_PAYMENT_MASTER_KEY or NXR_PAYMENT_MASTER_KEY_FILE"
            );
        }
    }

    private static byte[] aad(String provider, String field) {
        return ("nxr-payment-config:" + provider + ":" + field).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] decodePem(String pem, String type) {
        if (pem == null) {
            throw new IllegalArgumentException("PEM is missing");
        }
        String normalized = pem
            .replace("-----BEGIN " + type + "-----", "")
            .replace("-----END " + type + "-----", "")
            .replaceAll("\\s", "");
        return Base64.getDecoder().decode(normalized);
    }

    private static byte[] loadMasterKey(String encodedKey, String keyFile) {
        String candidate = encodedKey == null ? "" : encodedKey.trim();
        if (!candidate.isEmpty()) {
            return decodeMasterKey(candidate);
        }
        String fileName = keyFile == null ? "" : keyFile.trim();
        if (fileName.isEmpty()) {
            return null;
        }
        Path path = Path.of(fileName).toAbsolutePath().normalize();
        try {
            if (!Files.isRegularFile(path)) {
                throw new IllegalStateException("Payment master key file is not a regular file");
            }
            try {
                Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
                if (!java.util.Collections.disjoint(permissions, UNSAFE_KEY_PERMISSIONS)) {
                    throw new IllegalStateException("Payment master key file must not be accessible by group or other users");
                }
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX filesystems cannot expose Unix permissions; file access remains OS-controlled.
            }
            return decodeMasterKey(Files.readString(path, StandardCharsets.UTF_8).trim());
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read payment master key file", exception);
        }
    }

    private static byte[] decodeMasterKey(String encoded) {
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            if (decoded.length != 32) {
                throw new IllegalStateException("Payment master key must decode to exactly 32 bytes");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Payment master key must be Base64 encoded", exception);
        }
    }
}
