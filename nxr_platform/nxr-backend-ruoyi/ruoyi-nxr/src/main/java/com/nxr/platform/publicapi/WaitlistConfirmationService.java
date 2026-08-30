package com.nxr.platform.publicapi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

@Service
public class WaitlistConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(WaitlistConfirmationService.class);
    private static final String FALLBACK_RESEND_FROM = "NXR <onboarding@resend.dev>";

    private final ObjectMapper objectMapper;
    private final TaskExecutor taskExecutor;
    private final HttpClient httpClient;
    private final String fromAddress;
    private final String resendKey;
    private final String smtpHost;
    private final int smtpPort;
    private final String smtpUser;
    private final String smtpPassword;
    private final boolean smtpStartTls;

    public WaitlistConfirmationService(
        ObjectMapper objectMapper,
        @Qualifier("threadPoolTaskExecutor") TaskExecutor taskExecutor,
        @Value("${nxr.email.from:}") String fromAddress,
        @Value("${nxr.email.resend-key:}") String resendKey,
        @Value("${nxr.email.smtp-host:}") String smtpHost,
        @Value("${nxr.email.smtp-port:587}") int smtpPort,
        @Value("${nxr.email.smtp-user:}") String smtpUser,
        @Value("${nxr.email.smtp-password:}") String smtpPassword,
        @Value("${nxr.email.smtp-starttls:true}") boolean smtpStartTls
    ) {
        this.objectMapper = objectMapper;
        this.taskExecutor = taskExecutor;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.fromAddress = clean(fromAddress);
        this.resendKey = clean(resendKey);
        this.smtpHost = clean(smtpHost);
        this.smtpPort = smtpPort > 0 ? smtpPort : 587;
        this.smtpUser = clean(smtpUser);
        this.smtpPassword = clean(smtpPassword);
        this.smtpStartTls = smtpStartTls;
    }

    public boolean queueConfirmation(String email) {
        if (!isConfigured()) {
            return false;
        }
        try {
            taskExecutor.execute(() -> sendConfirmation(email));
            return true;
        } catch (RuntimeException exc) {
            log.warn("Unable to queue waitlist confirmation for {}", email, exc);
            return false;
        }
    }

    boolean sendConfirmation(String email) {
        if (sendViaResend(email)) {
            return true;
        }
        if (sendViaSmtp(email)) {
            return true;
        }
        log.warn("No waitlist confirmation provider delivered the message for {}", email);
        return false;
    }

    private boolean isConfigured() {
        return !resendKey.isBlank()
            || (!smtpHost.isBlank() && !smtpUser.isBlank() && !smtpPassword.isBlank());
    }

    private boolean sendViaResend(String email) {
        if (resendKey.isBlank()) {
            return false;
        }
        String primaryFrom = fromAddress.isBlank() ? FALLBACK_RESEND_FROM : fromAddress;
        ResendResult result = postResend(email, primaryFrom);
        if (result.success()) {
            return true;
        }
        if (
            result.statusCode() == 403
                && !primaryFrom.equals(FALLBACK_RESEND_FROM)
                && result.body().toLowerCase(java.util.Locale.ROOT).contains("domain is not verified")
        ) {
            result = postResend(email, FALLBACK_RESEND_FROM);
            if (result.success()) {
                log.warn("Resend sender fallback applied for {} after an unverified-domain response", email);
                return true;
            }
        }
        log.warn("Resend waitlist confirmation failed for {} with HTTP {}", email, result.statusCode());
        return false;
    }

    private ResendResult postResend(String email, String sender) {
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                "from", sender,
                "to", List.of(email),
                "subject", "You're on the NXR waitlist",
                "html", "<p>You're officially on the NXR waitlist.</p>"
                    + "<p>We'll email you as soon as private beta access expands.</p>"
                    + "<p>NXR Grading</p>"
            ));
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.resend.com/emails"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + resendKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
            );
            return new ResendResult(
                response.statusCode() >= 200 && response.statusCode() < 300,
                response.statusCode(),
                response.body() == null ? "" : response.body()
            );
        } catch (JsonProcessingException exc) {
            log.warn("Unable to encode Resend waitlist confirmation", exc);
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
            log.warn("Resend waitlist confirmation was interrupted for {}", email);
        } catch (Exception exc) {
            log.warn("Resend waitlist confirmation failed for {}", email, exc);
        }
        return new ResendResult(false, 0, "");
    }

    private boolean sendViaSmtp(String email) {
        if (smtpHost.isBlank() || smtpUser.isBlank() || smtpPassword.isBlank()) {
            return false;
        }
        try {
            JavaMailSenderImpl sender = new JavaMailSenderImpl();
            sender.setHost(smtpHost);
            sender.setPort(smtpPort);
            sender.setUsername(smtpUser);
            sender.setPassword(smtpPassword);
            Properties properties = sender.getJavaMailProperties();
            properties.setProperty("mail.smtp.auth", "true");
            properties.setProperty("mail.smtp.starttls.enable", Boolean.toString(smtpStartTls));
            properties.setProperty("mail.smtp.connectiontimeout", "10000");
            properties.setProperty("mail.smtp.timeout", "10000");
            properties.setProperty("mail.smtp.writetimeout", "10000");

            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress.isBlank() ? smtpUser : fromAddress);
            message.setTo(email);
            message.setSubject("You're on the NXR waitlist");
            message.setText(
                "You're officially on the NXR waitlist.\n\n"
                    + "We'll email you as soon as private beta access expands.\n\n"
                    + "NXR Grading"
            );
            sender.send(message);
            return true;
        } catch (RuntimeException exc) {
            log.warn("SMTP waitlist confirmation failed for {}", email, exc);
            return false;
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private record ResendResult(boolean success, int statusCode, String body) {
    }
}
