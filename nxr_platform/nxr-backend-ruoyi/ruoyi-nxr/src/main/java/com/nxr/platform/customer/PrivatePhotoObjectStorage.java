package com.nxr.platform.customer;

import org.springframework.core.io.Resource;

/** Private order photos are never served through an R2 public URL. */
public interface PrivatePhotoObjectStorage {
    boolean configured();
    void requireConfigured();
    void store(String id, byte[] original, byte[] preview, String contentType, String checksumSha256);
    Resource read(String id, boolean original);
    void delete(String id);
}
