package com.nxr.platform.admin;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminAuthService {

    private final JdbcClient jdbcClient;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AdminAuthService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public LoginResponse login(LoginRequest request) {
        Optional<AdminUserRow> user = jdbcClient.sql(
                """
                SELECT id, username, display_name, password_hash, role_code, is_active
                FROM admin_user
                WHERE LOWER(username) = LOWER(:username)
                """
            )
            .param("username", request.username())
            .query((rs, rowNum) -> new AdminUserRow(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("display_name"),
                rs.getString("password_hash"),
                rs.getString("role_code"),
                rs.getBoolean("is_active")
            ))
            .optional();

        AdminUserRow adminUser = user.orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password")
        );

        if (!adminUser.isActive() || !passwordEncoder.matches(request.password(), adminUser.passwordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }

        jdbcClient.sql("UPDATE admin_user SET last_login_at = CURRENT_TIMESTAMP WHERE id = :id")
            .param("id", adminUser.id())
            .update();

        return new LoginResponse(
            "dev-admin-session",
            adminUser.username(),
            adminUser.displayName(),
            adminUser.roleCode(),
            LocalDateTime.now()
        );
    }

    private record AdminUserRow(
        long id,
        String username,
        String displayName,
        String passwordHash,
        String roleCode,
        boolean isActive
    ) {
    }

    public record LoginRequest(String username, String password) {
    }

    public record LoginResponse(
        String accessToken,
        String username,
        String displayName,
        String roleCode,
        LocalDateTime loggedInAt
    ) {
    }
}
