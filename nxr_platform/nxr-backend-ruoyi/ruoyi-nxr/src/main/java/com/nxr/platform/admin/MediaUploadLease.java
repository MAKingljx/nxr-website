package com.nxr.platform.admin;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** The existing JVM local timestamp contract, shared by claims and read-only queue projections. */
@Component
public final class MediaUploadLease {
    public static final Duration DURATION = Duration.ofMinutes(15);
    public static final String INTERRUPTED_MESSAGE = "Upload interrupted. Retry publishing when both images are ready.";
    private final Clock clock;

    public MediaUploadLease() { this(Clock.systemDefaultZone()); }
    MediaUploadLease(Clock clock) { this.clock = Objects.requireNonNull(clock); }

    public Snapshot snapshot() {
        LocalDateTime now = LocalDateTime.now(clock);
        return new Snapshot(now, now.minus(DURATION));
    }

    public record Snapshot(LocalDateTime now, LocalDateTime cutoff) {
        public boolean interrupted(String status, LocalDateTime startedAt) {
            return "uploading".equals(status) && startedAt != null && !startedAt.isAfter(cutoff);
        }
        public boolean blocked(String status, LocalDateTime startedAt) {
            // A missing timestamp is unknown, not proof that the owner expired.
            return "uploading".equals(status) && !interrupted(status, startedAt);
        }
        public String displayedStatus(String status, LocalDateTime startedAt) {
            return interrupted(status, startedAt) ? "failed" : status;
        }
    }
}
