package com.nxr.platform.admin.storage;

import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class MediaStorageRegistry {

    private final List<MediaStorageProvider> providers;
    private final String activeDriver;

    public MediaStorageRegistry(
        List<MediaStorageProvider> providers,
        @Value("${nxr.media.storage-driver:local}") String activeDriver
    ) {
        this.providers = List.copyOf(providers);
        this.activeDriver = normalize(activeDriver);
    }

    public MediaStorageProvider active() {
        return providerFor(activeDriver);
    }

    public MediaStorageProvider providerFor(String providerCode) {
        return providers.stream()
            .filter(provider -> provider.manages(providerCode))
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Unsupported media storage provider: " + normalize(providerCode)
            ));
    }

    public boolean supports(String providerCode) {
        return providers.stream().anyMatch(provider -> provider.manages(providerCode));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
