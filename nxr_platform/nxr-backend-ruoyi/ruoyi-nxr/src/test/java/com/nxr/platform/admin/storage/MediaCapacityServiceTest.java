package com.nxr.platform.admin.storage;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

class MediaCapacityServiceTest {
    @TempDir Path directory;

    @Test void concurrentReservationsPreserveHeadroomAndReleaseOnlyOnce() {
        MediaCapacityService capacity = new MediaCapacityService(100) {
            @Override protected long usableSpace(Path root) { return 500; }
        };
        var first = capacity.reserve(directory, 250);
        assertThatThrownBy(() -> capacity.reserve(directory, 151)).isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("507");
        first.close(); first.close();
        try (var all = capacity.reserve(directory, 400)) {
            assertThatThrownBy(() -> capacity.reserve(directory, 1)).isInstanceOf(ResponseStatusException.class);
        }
        try (var next = capacity.reserve(directory, 400)) { assertThat(next).isNotNull(); }
    }

    @Test void unknownUploadLengthIsRejectedBeforeAStreamCanBeWritten() {
        var capacity = new MediaCapacityService(0);
        assertThatThrownBy(() -> capacity.reserve(directory, -1)).isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("known upload size");
    }
}
