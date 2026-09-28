package com.nxr.platform.admin.storage;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class MediaStorageRegistryTest {
    @Test void unpublishedMediaCanStayLocalWhilePublishedMediaUsesR2() {
        MediaStorageProvider local = mock(MediaStorageProvider.class);
        MediaStorageProvider r2 = mock(MediaStorageProvider.class);
        when(local.manages("local")).thenReturn(true);
        when(r2.manages("r2")).thenReturn(true);
        var registry = new MediaStorageRegistry(List.of(local, r2), "local", "local", "r2");

        assertThat(registry.forStage("staged")).isSameAs(local);
        assertThat(registry.forStage("published")).isSameAs(r2);
        assertThat(registry.active()).isSameAs(local);
        assertThatThrownBy(() -> registry.forStage("unknown"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Unsupported media stage");
    }

    @Test void legacySingleDriverConfigurationKeepsBothStagesTogether() {
        MediaStorageProvider local = mock(MediaStorageProvider.class);
        when(local.manages("local")).thenReturn(true);
        var registry = new MediaStorageRegistry(List.of(local), "local");
        assertThat(registry.forStage("staged")).isSameAs(local);
        assertThat(registry.forStage("published")).isSameAs(local);
    }
}
