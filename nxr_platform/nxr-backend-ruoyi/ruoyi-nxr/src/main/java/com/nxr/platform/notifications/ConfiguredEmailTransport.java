package com.nxr.platform.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

/** Resend-first transport with SMTP fallback. Credentials remain configuration-only. */
@Service
public class ConfiguredEmailTransport implements NotificationTransport {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String fromAddress;
    private final String resendKey;
    private final String smtpHost;
    private final int smtpPort;
    private final String smtpUser;
    private final String smtpPassword;
    private final boolean smtpStartTls;

    @Autowired
    public ConfiguredEmailTransport(
        ObjectMapper objectMapper,
        @Value("${nxr.email.from:}") String fromAddress,
        @Value("${nxr.email.resend-key:}") String resendKey,
        @Value("${nxr.email.smtp-host:}") String smtpHost,
        @Value("${nxr.email.smtp-port:587}") int smtpPort,
        @Value("${nxr.email.smtp-user:}") String smtpUser,
        @Value("${nxr.email.smtp-password:}") String smtpPassword,
        @Value("${nxr.email.smtp-starttls:true}") boolean smtpStartTls
    ) {
        this(objectMapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), fromAddress,
            resendKey, smtpHost, smtpPort, smtpUser, smtpPassword, smtpStartTls);
    }

    ConfiguredEmailTransport(
        ObjectMapper objectMapper,
        HttpClient httpClient,
        String fromAddress,
        String resendKey,
        String smtpHost,
        int smtpPort,
        String smtpUser,
        String smtpPassword,
        boolean smtpStartTls
    ) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.fromAddress = clean(fromAddress);
        this.resendKey = clean(resendKey);
        this.smtpHost = clean(smtpHost);
        this.smtpPort = smtpPort > 0 ? smtpPort : 587;
        this.smtpUser = clean(smtpUser);
        this.smtpPassword = clean(smtpPassword);
        this.smtpStartTls = smtpStartTls;
    }

    @Override
    public boolean isConfigured() {
        return (!resendKey.isBlank() && !fromAddress.isBlank()) || smtpConfigured();
    }

    @Override
    public void send(NotificationMessage message) throws NotificationTransportException {
        if (!resendKey.isBlank() && !fromAddress.isBlank()) {
            try {
                if (sendViaResend(message)) {
                    return;
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new NotificationTransportException("resend_interrupted", exception);
            } catch (Exception exception) {
                if (!smtpConfigured()) {
                    throw new NotificationTransportException("resend_failed", exception);
                }
            }
        }
        if (smtpConfigured()) {
            sendViaSmtp(message);
            return;
        }
        throw new NotificationTransportException("provider_unavailable");
    }

    private boolean sendViaResend(NotificationMessage message) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
            "from", fromAddress,
            "to", List.of(message.recipient()),
            "subject", message.subject(),
            "text", message.textBody()
        ));
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.resend.com/emails"))
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer " + resendKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        return response.statusCode() >= 200 && response.statusCode() < 300;
    }

    private void sendViaSmtp(NotificationMessage message) throws NotificationTransportException {
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
            SimpleMailMessage mail = new SimpleMailMessage();
            mail.setFrom(fromAddress.isBlank() ? smtpUser : fromAddress);
            mail.setTo(message.recipient());
            mail.setSubject(message.subject());
            mail.setText(message.textBody());
            sender.send(mail);
        } catch (RuntimeException exception) {
            throw new NotificationTransportException("smtp_failed", exception);
        }
    }

    private boolean smtpConfigured() {
        return !smtpHost.isBlank() && !smtpUser.isBlank() && !smtpPassword.isBlank();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
