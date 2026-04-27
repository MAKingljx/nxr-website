package com.nxr.platform.platform;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform")
public class PlatformController {

    private final JdbcClient jdbcClient;

    public PlatformController(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of(
            "service", "nxr-platform-backend",
            "status", "ok",
            "version", "phase-1"
        );
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        Integer publishedCount = jdbcClient.sql("SELECT COUNT(*) FROM published_certificate")
            .query(Integer.class)
            .single();
        Integer submissionCount = jdbcClient.sql("SELECT COUNT(*) FROM grading_submission")
            .query(Integer.class)
            .single();

        return Map.of(
            "platform", "NXR Platform",
            "phase", "phase-1-real-slice",
            "publicAdminEntry", "/x7k9m2q4r8v6c3p1",
            "modules", List.of(
                "public-web",
                "admin-dashboard",
                "submission-workflow",
                "certificate-verify",
                "waitlist",
                "mysql-schema"
            ),
            "publishedCount", publishedCount,
            "submissionCount", submissionCount
        );
    }
}
