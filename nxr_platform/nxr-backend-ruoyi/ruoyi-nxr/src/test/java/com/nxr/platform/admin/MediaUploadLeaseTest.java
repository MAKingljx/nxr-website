package com.nxr.platform.admin;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.*;
import org.junit.jupiter.api.Test;

class MediaUploadLeaseTest {
    @Test void inclusiveFifteenMinuteBoundaryAndUnknownTimeAreSharedAcrossJvmZones() {
        Instant now=Instant.parse("2026-10-19T10:00:00Z");
        for(ZoneId zone:new ZoneId[]{ZoneOffset.UTC,ZoneId.of("Asia/Shanghai")}) {
            var lease=new MediaUploadLease(Clock.fixed(now,zone)).snapshot();
            assertThat(lease.now()).isEqualTo(LocalDateTime.ofInstant(now,zone));
            assertThat(lease.cutoff()).isEqualTo(lease.now().minusMinutes(15));
            assertThat(lease.interrupted("uploading",lease.cutoff())).isTrue();
            assertThat(lease.interrupted("uploading",lease.cutoff().minusNanos(1))).isTrue();
            assertThat(lease.blocked("uploading",lease.cutoff().plusNanos(1))).isTrue();
            assertThat(lease.interrupted("uploading",null)).isFalse();
            assertThat(lease.blocked("uploading",null)).isTrue();
            assertThat(lease.interrupted("failed",lease.cutoff())).isFalse();
            assertThat(lease.interrupted("uploaded",lease.cutoff())).isFalse();
        }
    }
}
