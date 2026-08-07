package com.nxr.platform.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CertificateIdPolicyTest {

    private final CertificateIdPolicy policy = new CertificateIdPolicy();

    @Test
    void acceptsOnlyThePythonTenDigitFormatForNewCertificates() {
        assertThat(policy.isCanonical("5703018202")).isTrue();
        assertThat(policy.isCanonical("0123456789")).isTrue();
        assertThat(policy.isCanonical("VRA003")).isFalse();
        assertThat(policy.isCanonical("NXR2026032401")).isFalse();
        assertThat(policy.isCanonical("123456789")).isFalse();
        assertThat(policy.isCanonical("12345678901")).isFalse();
    }

    @Test
    void generatedCandidatesAlwaysUseTenDigits() {
        for (int index = 0; index < 100; index += 1) {
            assertThat(policy.generateCandidate()).matches("\\d{10}");
        }
    }

    @Test
    void legacyCertificateCanOnlyBePreservedUnchanged() {
        assertThat(policy.preservesExistingValue("vra003", "VRA003")).isTrue();
        assertThat(policy.preservesExistingValue("VRA004", "VRA003")).isFalse();
    }
}
