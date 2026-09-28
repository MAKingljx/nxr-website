package com.nxr.platform.admin.storage;

import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class MediaStorageRegistry {

    private final List<MediaStorageProvider> providers;
    private final String activeDriver;
    private final String stagingDriver;
    private final String publishedDriver;

    @Autowired
    public MediaStorageRegistry(
        List<MediaStorageProvider> providers,
        @Value("${nxr.media.storage-driver:local}") String activeDriver,
        @Value("${nxr.media.staging-driver:local}") String stagingDriver,
        @Value("${nxr.media.published-driver:local}") String publishedDriver
    ) {
        this.providers = List.copyOf(providers);
        this.activeDriver = normalize(activeDriver);
        this.stagingDriver = normalize(stagingDriver);
        this.publishedDriver = normalize(publishedDriver);
        providerFor(this.stagingDriver);
        providerFor(this.publishedDriver);
    }

    /** Preserve the previous single-driver behavior for isolated callers. */
    public MediaStorageRegistry(List<MediaStorageProvider> providers, String activeDriver) {
        this(providers, activeDriver, activeDriver, activeDriver);
    }

    public MediaStorageProvider active() {
        return providerFor(activeDriver);
    }

    public MediaStorageProvider forStage(String stage) {
        return switch (normalize(stage)) {
            case "staged" -> providerFor(stagingDriver);
            case "published" -> providerFor(publishedDriver);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Unsupported media stage: " + stage);
        };
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
