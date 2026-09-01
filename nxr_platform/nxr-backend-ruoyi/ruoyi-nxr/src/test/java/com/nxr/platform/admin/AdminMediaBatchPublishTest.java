package com.nxr.platform.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AdminMediaBatchPublishTest {

    @Test
    void batchPublishDeduplicatesAndReportsIndividualFailures() {
        AdminMediaService service = new AdminMediaService(null, null, null, 100, 1024, 102400, 100_000_000) {
            @Override
            public MediaPublishResponse publishSubmission(long submissionId) {
                if (submissionId == 2L) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Back staged media is required.");
                }
                return new MediaPublishResponse(
                    submissionId,
                    "CERT" + submissionId,
                    "published",
                    LocalDateTime.of(2026, 8, 30, 17, 0),
                    "/front.webp",
                    "/back.webp"
                );
            }
        };

        AdminMediaService.MediaBatchPublishResponse response = service.publishSubmissions(
            List.of(1L, 2L, 1L, -1L)
        );

        assertThat(response.requestedCount()).isEqualTo(2);
        assertThat(response.publishedCount()).isOne();
        assertThat(response.failedCount()).isOne();
        assertThat(response.published()).singleElement().extracting("certId").isEqualTo("CERT1");
        assertThat(response.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.submissionId()).isEqualTo(2L);
            assertThat(failure.message()).contains("Back staged media");
        });
    }
}
