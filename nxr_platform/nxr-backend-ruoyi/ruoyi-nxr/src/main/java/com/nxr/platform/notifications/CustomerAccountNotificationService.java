package com.nxr.platform.notifications;

import com.nxr.platform.customer.CustomerAuthService;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CustomerAccountNotificationService {

    public static final String RESET_REQUEST_MESSAGE =
        "If an active account matches that email and delivery is available, NXR will send a password reset link.";
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
        "^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$", Pattern.CASE_INSENSITIVE
    );
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbcClient;
    private final CustomerAuthService customerAuthService;
    private final NotificationOutboxService outboxService;
    private final Clock clock;
    private final int verificationMinutes;
    private final int resetMinutes;
    private final int emailRequestsPerHour;
    private final int ipRequestsPerHour;
    private final String publicSiteBaseUrl;

    @Autowired
    public CustomerAccountNotificationService(
        JdbcClient jdbcClient,
        CustomerAuthService customerAuthService,
        NotificationOutboxService outboxService,
        @Value("${nxr.notifications.email-verification-minutes:1440}") int verificationMinutes,
        @Value("${nxr.notifications.password-reset-minutes:30}") int resetMinutes,
        @Value("${nxr.notifications.rate-limit-email-per-hour:3}") int emailRequestsPerHour,
        @Value("${nxr.notifications.rate-limit-ip-per-hour:10}") int ipRequestsPerHour,
        @Value("${nxr.public-site.base-url:http://127.0.0.1:3000}") String publicSiteBaseUrl
    ) {
        this(jdbcClient, customerAuthService, outboxService, Clock.systemDefaultZone(), verificationMinutes,
            resetMinutes, emailRequestsPerHour, ipRequestsPerHour, publicSiteBaseUrl);
    }

    CustomerAccountNotificationService(
        JdbcClient jdbcClient,
        CustomerAuthService customerAuthService,
        NotificationOutboxService outboxService,
        Clock clock,
        int verificationMinutes,
        int resetMinutes,
        int emailRequestsPerHour,
        int ipRequestsPerHour,
        String publicSiteBaseUrl
    ) {
        this.jdbcClient = jdbcClient;
        this.customerAuthService = customerAuthService;
        this.outboxService = outboxService;
        this.clock = clock;
        this.verificationMinutes = Math.max(5, Math.min(verificationMinutes, 2880));
        this.resetMinutes = Math.max(5, Math.min(resetMinutes, 120));
        this.emailRequestsPerHour = Math.max(1, Math.min(emailRequestsPerHour, 20));
        this.ipRequestsPerHour = Math.max(1, Math.min(ipRequestsPerHour, 100));
        String base = bounded(publicSiteBaseUrl, 512);
        this.publicSiteBaseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    public EmailStatus emailStatus(CustomerAuthService.CustomerAccount account) {
        boolean verified = jdbcClient.sql(
                "SELECT COUNT(*) FROM customer_account WHERE id = :customerId AND email_verified_at IS NOT NULL"
            )
            .param("customerId", account.id())
            .query(Integer.class)
            .single() > 0;
        return new EmailStatus(account.email(), verified, outboxService.deliveryCapability());
    }

    @Transactional
    public VerificationRequestResult requestEmailVerification(CustomerAuthService.CustomerAccount account, String remoteAddress) {
        NotificationOutboxService.DeliveryCapability capability = outboxService.deliveryCapability();
        if (!capability.available()) {
            return new VerificationRequestResult(false, capability.message());
        }
        if (emailStatus(account).verified()) {
            return new VerificationRequestResult(false, "This email address is already verified.");
        }
        if (!recordRateAndAllow("verify_email", account.email(), remoteAddress)) {
            return new VerificationRequestResult(false, "Too many verification requests. Please try again later.");
        }
        String rawToken = generateToken("nxre_");
        String tokenHash = NotificationOutboxService.sha256(rawToken);
        insertToken(account.id(), "verify_email", tokenHash, remoteAddress, verificationMinutes);
        String link = publicSiteBaseUrl + "/account/email-verification?token=" + urlQuery(rawToken);
        boolean queued = outboxService.enqueueAccountLink(
            account.id(), "email_verification", tokenHash, "Verify your NXR email address",
            "Verify your email address using this single-use link:\n\n" + link
                + "\n\nThis link expires automatically. If you did not request it, you can ignore this message.\n\nNXR Grading"
        );
        if (!queued) {
            jdbcClient.sql("DELETE FROM customer_account_token WHERE token_hash = :tokenHash")
                .param("tokenHash", tokenHash).update();
        }
        return new VerificationRequestResult(queued,
            queued ? "Verification email queued." : "Email delivery is currently unavailable.");
    }

    @Transactional
    public ConfirmResult confirmEmailVerification(String rawToken) {
        TokenRow token = requireUsableToken(rawToken, "verify_email");
        consumeToken(token.id());
        jdbcClient.sql(
                "UPDATE customer_account SET email_verified_at = COALESCE(email_verified_at, :now) WHERE id = :customerId"
            )
            .param("now", LocalDateTime.now(clock))
            .param("customerId", token.customerId())
            .update();
        return new ConfirmResult(true, "Email address verified.");
    }

    /** Always returns the same public response for known, unknown and throttled email addresses. */
    @Transactional
    public ResetRequestResult requestPasswordReset(String emailValue, String remoteAddress) {
        String email = normalizeEmail(emailValue);
        boolean validEmail = email.length() <= 254 && EMAIL_PATTERN.matcher(email).matches();
        boolean allowed = recordRateAndAllow("password_reset", validEmail ? email : "invalid", remoteAddress);
        if (validEmail && allowed && outboxService.deliveryCapability().available()) {
            Optional<CustomerAuthService.CustomerAccount> account = customerAuthService.findCustomerByEmail(email)
                .filter(CustomerAuthService.CustomerAccount::active);
            if (account.isPresent()) {
                String rawToken = generateToken("nxrp_");
                String tokenHash = NotificationOutboxService.sha256(rawToken);
                insertToken(account.get().id(), "password_reset", tokenHash, remoteAddress, resetMinutes);
                String link = publicSiteBaseUrl + "/account/password-recovery?token=" + urlQuery(rawToken);
                boolean queued = outboxService.enqueueAccountLink(
                    account.get().id(), "password_reset", tokenHash, "Reset your NXR password",
                    "Reset your password using this single-use link:\n\n" + link
                        + "\n\nThis link expires soon. If you did not request it, you can ignore this message.\n\nNXR Grading"
                );
                if (!queued) {
                    jdbcClient.sql("DELETE FROM customer_account_token WHERE token_hash = :tokenHash")
                        .param("tokenHash", tokenHash).update();
                }
            }
        }
        return new ResetRequestResult(true, RESET_REQUEST_MESSAGE);
    }

    @Transactional
    public ConfirmResult confirmPasswordReset(String rawToken, String newPassword) {
        if (newPassword == null || newPassword.length() < 8 || newPassword.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must contain 8 to 200 characters");
        }
        TokenRow token = requireUsableToken(rawToken, "password_reset");
        consumeToken(token.id());
        customerAuthService.resetPasswordAndRevokeSessions(token.customerId(), newPassword);
        jdbcClient.sql(
                "UPDATE customer_account_token SET consumed_at = :now WHERE customer_id = :customerId AND consumed_at IS NULL"
            )
            .param("now", LocalDateTime.now(clock))
            .param("customerId", token.customerId())
            .update();
        return new ConfirmResult(true, "Password updated. Please sign in again on every device.");
    }

    private void insertToken(long customerId, String purpose, String tokenHash, String remoteAddress, int lifetimeMinutes) {
        LocalDateTime now = LocalDateTime.now(clock);
        jdbcClient.sql(
                """
                UPDATE customer_account_token SET consumed_at = :now
                WHERE customer_id = :customerId AND purpose_code = :purpose AND consumed_at IS NULL
                """
            )
            .param("now", now).param("customerId", customerId).param("purpose", purpose).update();
        jdbcClient.sql(
                """
                INSERT INTO customer_account_token
                    (customer_id, purpose_code, token_hash, request_ip_hash, expires_at)
                VALUES (:customerId, :purpose, :tokenHash, :ipHash, :expiresAt)
                """
            )
            .param("customerId", customerId)
            .param("purpose", purpose)
            .param("tokenHash", tokenHash)
            .param("ipHash", NotificationOutboxService.sha256("ip|" + bounded(remoteAddress, 128)))
            .param("expiresAt", now.plusMinutes(lifetimeMinutes))
            .update();
    }

    private TokenRow requireUsableToken(String rawToken, String purpose) {
        String token = rawToken == null ? "" : rawToken.trim();
        if (token.isBlank() || token.length() > 256) {
            throw invalidToken();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        return jdbcClient.sql(
                """
                SELECT id, customer_id, expires_at FROM customer_account_token
                WHERE token_hash = :tokenHash AND purpose_code = :purpose
                  AND consumed_at IS NULL AND expires_at > :now
                """
            )
            .param("tokenHash", NotificationOutboxService.sha256(token))
            .param("purpose", purpose)
            .param("now", now)
            .query((rs, rowNum) -> new TokenRow(
                rs.getLong("id"), rs.getLong("customer_id"), rs.getObject("expires_at", LocalDateTime.class)
            ))
            .optional()
            .orElseThrow(CustomerAccountNotificationService::invalidToken);
    }

    private void consumeToken(long tokenId) {
        int updated = jdbcClient.sql(
                """
                UPDATE customer_account_token SET consumed_at = :now
                WHERE id = :id AND consumed_at IS NULL AND expires_at > :now
                """
            )
            .param("now", LocalDateTime.now(clock))
            .param("id", tokenId)
            .update();
        if (updated != 1) {
            throw invalidToken();
        }
    }

    private boolean recordRateAndAllow(String action, String email, String remoteAddress) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime cutoff = now.minusHours(1);
        String emailHash = NotificationOutboxService.sha256("email|" + normalizeEmail(email));
        String ipHash = NotificationOutboxService.sha256("ip|" + bounded(remoteAddress, 128));
        jdbcClient.sql("DELETE FROM customer_auth_rate_event WHERE created_at < :cutoff")
            .param("cutoff", now.minusDays(1)).update();
        int emailCount = rateCount(action, emailHash, cutoff);
        int ipCount = rateCount(action, ipHash, cutoff);
        jdbcClient.sql(
                "INSERT INTO customer_auth_rate_event (action_code, identifier_hash, created_at) VALUES (:action, :hash, :now)"
            ).param("action", action).param("hash", emailHash).param("now", now).update();
        jdbcClient.sql(
                "INSERT INTO customer_auth_rate_event (action_code, identifier_hash, created_at) VALUES (:action, :hash, :now)"
            ).param("action", action).param("hash", ipHash).param("now", now).update();
        return emailCount < emailRequestsPerHour && ipCount < ipRequestsPerHour;
    }

    private int rateCount(String action, String hash, LocalDateTime cutoff) {
        return jdbcClient.sql(
                "SELECT COUNT(*) FROM customer_auth_rate_event WHERE action_code = :action AND identifier_hash = :hash AND created_at >= :cutoff"
            )
            .param("action", action).param("hash", hash).param("cutoff", cutoff)
            .query(Integer.class).single();
    }

    private static ResponseStatusException invalidToken() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Link is invalid, expired, or has already been used");
    }

    private static String generateToken(String prefix) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String normalizeEmail(String value) {
        return bounded(value, 255).toLowerCase(Locale.ROOT);
    }

    private static String bounded(String value, int maxLength) {
        String cleaned = value == null ? "" : value.trim().replaceAll("[\\r\\n\\t]", "");
        return cleaned.substring(0, Math.min(cleaned.length(), maxLength));
    }

    private static String urlQuery(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private record TokenRow(long id, long customerId, LocalDateTime expiresAt) {
    }

    public record EmailStatus(
        String email,
        boolean verified,
        NotificationOutboxService.DeliveryCapability delivery
    ) {
    }

    public record VerificationRequestResult(boolean queued, String message) {
    }

    public record ResetRequestResult(boolean accepted, String message) {
    }

    public record ConfirmResult(boolean success, String message) {
    }
}
