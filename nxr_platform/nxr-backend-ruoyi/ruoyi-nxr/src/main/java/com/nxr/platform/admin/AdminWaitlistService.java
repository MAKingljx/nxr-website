package com.nxr.platform.admin;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class AdminWaitlistService {

    private final JdbcClient jdbcClient;

    public AdminWaitlistService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public WaitlistResponse listWaitlist(String query, int page, int pageSize) {
        int normalizedPage = Math.max(1, page);
        int normalizedPageSize = Math.min(Math.max(1, pageSize), 100);
        int offset = (normalizedPage - 1) * normalizedPageSize;
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase();
        String queryPattern = "%" + normalizedQuery + "%";

        String whereClause = """
            WHERE (:query = ''
               OR LOWER(email) LIKE :queryPattern
               OR LOWER(source_code) LIKE :queryPattern
               OR LOWER(status_code) LIKE :queryPattern)
            """;

        Integer total = jdbcClient.sql("SELECT COUNT(*) FROM waitlist_email " + whereClause)
            .param("query", normalizedQuery)
            .param("queryPattern", queryPattern)
            .query(Integer.class)
            .single();

        List<WaitlistItem> items = jdbcClient.sql(
                """
                SELECT id, email, source_code, status_code, created_at
                FROM waitlist_email
                """ + whereClause + """
                ORDER BY created_at DESC, id DESC
                LIMIT :limit OFFSET :offset
                """
            )
            .param("query", normalizedQuery)
            .param("queryPattern", queryPattern)
            .param("limit", normalizedPageSize)
            .param("offset", offset)
            .query((rs, rowNum) -> new WaitlistItem(
                rs.getLong("id"),
                rs.getString("email"),
                rs.getString("source_code"),
                rs.getString("status_code"),
                rs.getObject("created_at", LocalDateTime.class)
            ))
            .list();

        return new WaitlistResponse(items, normalizedPage, normalizedPageSize, total);
    }

    public record WaitlistResponse(
        List<WaitlistItem> items,
        int page,
        int pageSize,
        int total
    ) {
    }

    public record WaitlistItem(
        long id,
        String email,
        String sourceCode,
        String statusCode,
        LocalDateTime createdAt
    ) {
    }
}
