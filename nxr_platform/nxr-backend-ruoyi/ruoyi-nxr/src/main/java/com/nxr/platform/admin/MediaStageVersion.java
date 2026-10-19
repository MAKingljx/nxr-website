package com.nxr.platform.admin;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Stable, opaque identity for the exact pair selected for publication. */
public final class MediaStageVersion {

    private MediaStageVersion() {
    }

    public static String of(Source front, Source back) {
        if (front == null || back == null || front.mediaId() == null || back.mediaId() == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            append(digest, "nxr-staged-pair-v1");
            append(digest, "front");
            append(digest, front);
            append(digest, "back");
            append(digest, back);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exc) {
            throw new IllegalStateException("SHA-256 is unavailable.", exc);
        }
    }

    static Source source(AdminMediaPersistenceService.ExistingMedia media) {
        return media == null ? null : new Source(media.id(), media.storageProviderCode(), media.storageBucket(),
            media.storageKey(), media.storageObjectVersion(), media.checksumSha256());
    }

    static String of(AdminMediaPersistenceService.ExistingMedia front, AdminMediaPersistenceService.ExistingMedia back) {
        return of(source(front), source(back));
    }

    private static void append(MessageDigest digest, Source source) {
        append(digest, source.mediaId().toString());
        append(digest, source.storageProviderCode());
        append(digest, source.storageBucket());
        append(digest, source.storageKey());
        append(digest, source.storageObjectVersion());
        append(digest, source.checksumSha256());
    }

    private static void append(MessageDigest digest, String value) {
        if (value == null) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    public record Source(Long mediaId, String storageProviderCode, String storageBucket, String storageKey,
        String storageObjectVersion, String checksumSha256) {
    }
}
