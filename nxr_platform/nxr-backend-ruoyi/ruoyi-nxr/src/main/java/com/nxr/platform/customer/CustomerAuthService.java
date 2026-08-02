package com.nxr.platform.customer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Customer identity is intentionally separate from RuoYi's internal staff users. */
@Service
public class CustomerAuthService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$", Pattern.CASE_INSENSITIVE);
    private static final SecureRandom TOKEN_RANDOM = new SecureRandom();

    private final JdbcClient jdbcClient;
    private final SimpleJdbcInsert customerInsert;
    private final SimpleJdbcInsert sessionInsert;
    private final BCryptPasswordEncoder passwordEncoder;

    @Value("${nxr.customer.session-days:30}")
    private int sessionDays;

    public CustomerAuthService(
        JdbcClient jdbcClient,
        JdbcTemplate jdbcTemplate,
        BCryptPasswordEncoder passwordEncoder
    ) {
        this.jdbcClient = jdbcClient;
        this.passwordEncoder = passwordEncoder;
        this.customerInsert = new SimpleJdbcInsert(jdbcTemplate)
            .withTableName("customer_account")
            .usingColumns("email", "password_hash", "display_name", "mobile")
            .usingGeneratedKeyColumns("id");
        this.sessionInsert = new SimpleJdbcInsert(jdbcTemplate)
            .withTableName("customer_session")
            .usingColumns("customer_id", "token_hash", "expires_at");
    }

    @Transactional
    public CustomerAuthResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        String password = request.password() == null ? "" : request.password();
        String displayName = normalizeDisplayName(request.displayName(), email);
        String mobile = clean(request.mobile(), 64);

        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid email address is required");
        }
        if (password.length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must contain at least 8 characters");
        }
        if (findCustomerByEmail(email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An account already exists for this email address");
        }

        long customerId;
        try {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("email", email);
            values.put("password_hash", passwordEncoder.encode(password));
            values.put("display_name", displayName);
            values.put("mobile", mobile.isBlank() ? null : mobile);
            customerId = customerInsert.executeAndReturnKey(values).longValue();
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An account already exists for this email address", exception);
        }

        CustomerAccount account = findCustomerById(customerId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Customer account was not saved"));
        return createAuthenticatedResponse(account);
    }

    @Transactional
    public CustomerAuthResponse login(LoginRequest request) {
        String email = normalizeEmail(request.email());
        CustomerAccount account = findCustomerByEmail(email)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Email or password is incorrect"));
        if (!account.active() || !passwordEncoder.matches(request.password() == null ? "" : request.password(), account.passwordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Email or password is incorrect");
        }

        jdbcClient.sql("UPDATE customer_account SET last_login_at = CURRENT_TIMESTAMP WHERE id = :customerId")
            .param("customerId", account.id())
            .update();
        return createAuthenticatedResponse(findCustomerById(account.id()).orElse(account));
    }

    public CustomerAccount requireCustomer(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Customer sign-in is required");
        }
        String tokenHash = hashToken(rawToken.trim());
        Optional<CustomerAccount> account = jdbcClient.sql(
                """
                SELECT c.id, c.email, c.password_hash, c.display_name, c.mobile, c.is_active, c.created_at, c.last_login_at
                FROM customer_session s
                JOIN customer_account c ON c.id = s.customer_id
                WHERE s.token_hash = :tokenHash
                  AND s.expires_at > CURRENT_TIMESTAMP
                  AND c.is_active = 1
                """
            )
            .param("tokenHash", tokenHash)
            .query((rs, rowNum) -> mapCustomer(rs))
            .optional();
        CustomerAccount customer = account.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Customer session has expired"));
        jdbcClient.sql("UPDATE customer_session SET last_seen_at = CURRENT_TIMESTAMP WHERE token_hash = :tokenHash")
            .param("tokenHash", tokenHash)
            .update();
        return customer;
    }

    public CustomerProfile profile(CustomerAccount account) {
        return new CustomerProfile(account.id(), account.email(), account.displayName(), account.mobile(), account.createdAt(), account.lastLoginAt());
    }

    public Optional<CustomerAccount> findCustomerById(long customerId) {
        return jdbcClient.sql(
                """
                SELECT id, email, password_hash, display_name, mobile, is_active, created_at, last_login_at
                FROM customer_account
                WHERE id = :customerId
                """
            )
            .param("customerId", customerId)
            .query((rs, rowNum) -> mapCustomer(rs))
            .optional();
    }

    public Optional<CustomerAccount> findCustomerByEmail(String value) {
        String email = normalizeEmail(value);
        if (email.isBlank()) {
            return Optional.empty();
        }
        return jdbcClient.sql(
                """
                SELECT id, email, password_hash, display_name, mobile, is_active, created_at, last_login_at
                FROM customer_account
                WHERE email = :email
                """
            )
            .param("email", email)
            .query((rs, rowNum) -> mapCustomer(rs))
            .optional();
    }

    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        jdbcClient.sql("DELETE FROM customer_session WHERE token_hash = :tokenHash")
            .param("tokenHash", hashToken(rawToken.trim()))
            .update();
    }

    private CustomerAuthResponse createAuthenticatedResponse(CustomerAccount account) {
        String rawToken = generateRawToken();
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(Math.max(1, sessionDays));
        sessionInsert.execute(Map.of(
            "customer_id", account.id(),
            "token_hash", hashToken(rawToken),
            "expires_at", expiresAt
        ));
        return new CustomerAuthResponse(rawToken, expiresAt, profile(account));
    }

    private static CustomerAccount mapCustomer(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CustomerAccount(
            rs.getLong("id"),
            rs.getString("email"),
            rs.getString("password_hash"),
            rs.getString("display_name"),
            rs.getString("mobile"),
            rs.getBoolean("is_active"),
            rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("last_login_at", LocalDateTime.class)
        );
    }

    private static String generateRawToken() {
        byte[] bytes = new byte[32];
        TOKEN_RANDOM.nextBytes(bytes);
        return "nxrc_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hashToken(String token) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String normalizeEmail(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeDisplayName(String value, String email) {
        String cleaned = clean(value, 128);
        if (!cleaned.isBlank()) {
            return cleaned;
        }
        int atIndex = email.indexOf('@');
        return atIndex > 0 ? email.substring(0, atIndex) : "Collector";
    }

    private static String clean(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().replaceAll("\\s+", " ");
        return normalized.substring(0, Math.min(normalized.length(), maxLength));
    }

    public record RegisterRequest(String email, String password, String displayName, String mobile) {
    }

    public record LoginRequest(String email, String password) {
    }

    public record CustomerAccount(
        long id,
        String email,
        String passwordHash,
        String displayName,
        String mobile,
        boolean active,
        LocalDateTime createdAt,
        LocalDateTime lastLoginAt
    ) {
    }

    public record CustomerProfile(
        long id,
        String email,
        String displayName,
        String mobile,
        LocalDateTime createdAt,
        LocalDateTime lastLoginAt
    ) {
    }

    public record CustomerAuthResponse(String token, LocalDateTime expiresAt, CustomerProfile customer) {
    }
}
