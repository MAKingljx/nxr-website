package com.nxr.platform.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CertificateIdPolicyTest {

    private final CertificateIdPolicy policy = new CertificateIdPolicy();

    @Test
    void acceptsOnlyTenDigitIdsWithoutALeadingZeroForNewCertificates() {
        assertThat(policy.isCanonical("5703018202")).isTrue();
        assertThat(policy.isCanonical("0123456789")).isFalse();
        assertThat(policy.isCanonical("VRA003")).isFalse();
        assertThat(policy.isCanonical("NXR2026032401")).isFalse();
        assertThat(policy.isCanonical("123456789")).isFalse();
        assertThat(policy.isCanonical("12345678901")).isFalse();
    }

    @Test
    void generatedCandidatesAlwaysUseTenDigitsWithoutALeadingZero() {
        for (int index = 0; index < 1_000; index += 1) {
            assertThat(policy.generateCandidate()).matches("[1-9]\\d{9}");
        }
    }

    @Test
    void legacyCertificateCanOnlyBePreservedUnchanged() {
        assertThat(policy.preservesExistingValue("vra003", "VRA003")).isTrue();
        assertThat(policy.preservesExistingValue("0123456789", "0123456789")).isTrue();
        assertThat(policy.preservesExistingValue("VRA004", "VRA003")).isFalse();
    }
}
