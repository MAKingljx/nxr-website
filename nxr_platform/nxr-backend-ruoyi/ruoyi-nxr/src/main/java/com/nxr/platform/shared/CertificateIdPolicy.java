package com.nxr.platform.shared;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Defines the certificate ID format shared with the Python workflow. */
@Component
public class CertificateIdPolicy {

    public static final int CERTIFICATE_ID_LENGTH = 10;
    private static final Pattern CANONICAL_PATTERN = Pattern.compile("[1-9]\\d{9}");

    public String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    public boolean isCanonical(String value) {
        String normalized = normalize(value);
        return normalized != null && CANONICAL_PATTERN.matcher(normalized).matches();
    }

    public boolean preservesExistingValue(String candidate, String existingValue) {
        String normalizedCandidate = normalize(candidate);
        String normalizedExisting = normalize(existingValue);
        return normalizedCandidate != null && normalizedCandidate.equals(normalizedExisting);
    }

    public String generateCandidate() {
        StringBuilder builder = new StringBuilder(CERTIFICATE_ID_LENGTH);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        builder.append(random.nextInt(1, 10));
        for (int index = 1; index < CERTIFICATE_ID_LENGTH; index += 1) {
            builder.append(random.nextInt(10));
        }
        return builder.toString();
    }
}
