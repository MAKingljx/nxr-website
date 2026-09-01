package com.nxr.platform.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class GradeLabelResolverTest {

    private final GradeLabelResolver resolver = new GradeLabelResolver();

    @Test
    void usesPythonPrecisionAndThresholds() {
        BigDecimal average = resolver.calculateFinalGrade(
            new BigDecimal("9.3"),
            new BigDecimal("9.4"),
            new BigDecimal("9.3"),
            new BigDecimal("9.4")
        );

        assertThat(average).isEqualByComparingTo("9.35");
        assertThat(resolver.resolveLabel(average)).isEqualTo("9.5");
        assertThat(resolver.resolveLabel(new BigDecimal("9.74"))).isEqualTo("9.5");
        assertThat(resolver.resolveLabel(new BigDecimal("9.75"))).isEqualTo("10");
        assertThat(resolver.resolveLabel(new BigDecimal("10.00"))).isEqualTo("Pristine 10");
    }

    @Test
    void normalizesHistoricalDescriptiveLabels() {
        assertThat(resolver.normalizeLabel("Near Mint-Mint 8")).isEqualTo("8");
        assertThat(resolver.normalizeLabel("Near Mint-Mint+ 8.5")).isEqualTo("8.5");
        assertThat(resolver.normalizeLabel("Mint 9")).isEqualTo("9");
        assertThat(resolver.normalizeLabel("Gem Mint 9.5")).isEqualTo("9.5");
        assertThat(resolver.normalizeLabel("Pristine-10")).isEqualTo("Pristine 10");
    }
}
