package com.nxr.platform.payments;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/** Small local safety valve; edge/proxy rate limits should remain the primary production control. */
@Component
final class PaymentWebhookGate {

    private static final int REQUESTS_PER_MINUTE = 120;
    private static final int MAX_TRACKED_CLIENTS = 4096;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Semaphore paypalVerifications = new Semaphore(8);

    Permit enter(String provider, String remoteAddress) {
        long minute = Instant.now().getEpochSecond() / 60;
        String key = provider + "|" + normalizeAddress(remoteAddress);
        if (windows.size() > MAX_TRACKED_CLIENTS) {
            windows.entrySet().removeIf(entry -> entry.getValue().minute() < minute - 1);
        }
        if (windows.size() >= MAX_TRACKED_CLIENTS && !windows.containsKey(key)) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "Payment webhook source limit exceeded"
            );
        }
        Window window = windows.compute(key, (ignored, existing) ->
            existing == null || existing.minute() != minute ? new Window(minute, new AtomicInteger(1))
                : increment(existing)
        );
        if (window.count().get() > REQUESTS_PER_MINUTE) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "Payment webhook rate limit exceeded"
            );
        }
        boolean paypal = PaymentProviderSpec.PAYPAL.code().equals(provider);
        if (paypal && !paypalVerifications.tryAcquire()) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "PayPal webhook verification is busy"
            );
        }
        return new Permit(paypal ? paypalVerifications : null);
    }

    private static Window increment(Window value) {
        value.count().incrementAndGet();
        return value;
    }

    private static String normalizeAddress(String value) {
        String address = value == null ? "unknown" : value.trim();
        return address.length() <= 64 ? address : address.substring(0, 64);
    }

    record Window(long minute, AtomicInteger count) {
    }

    static final class Permit implements AutoCloseable {
        private final Semaphore semaphore;

        Permit(Semaphore semaphore) {
            this.semaphore = semaphore;
        }

        @Override
        public void close() {
            if (semaphore != null) {
                semaphore.release();
            }
        }
    }
}
