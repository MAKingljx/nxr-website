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
    void matchesPythonBinary64AdditionAndRoundForHalfwayAverages() {
        assertThat(calculate("8.5", "8.5", "8.5", "9.0")).isEqualByComparingTo("8.62");
        assertThat(calculate("8.4", "8.5", "8.6", "8.8")).isEqualByComparingTo("8.57");
        assertThat(calculate("8.3", "8.4", "8.5", "8.6")).isEqualByComparingTo("8.45");
    }

    @Test
    void keepsPythonLabelsAtThresholdsAfterCompatibleRounding() {
        BigDecimal ninePointThreeFive = calculate("9.2", "9.3", "9.4", "9.5");
        BigDecimal ninePointSevenFive = calculate("9.6", "9.7", "9.8", "9.9");

        assertThat(ninePointThreeFive).isEqualByComparingTo("9.35");
        assertThat(resolver.resolveLabel(ninePointThreeFive)).isEqualTo("9.5");
        assertThat(ninePointSevenFive).isEqualByComparingTo("9.75");
        assertThat(resolver.resolveLabel(ninePointSevenFive)).isEqualTo("10");
    }

    @Test
    void normalizesHistoricalDescriptiveLabels() {
        assertThat(resolver.normalizeLabel("Near Mint-Mint 8")).isEqualTo("8");
        assertThat(resolver.normalizeLabel("Near Mint-Mint+ 8.5")).isEqualTo("8.5");
        assertThat(resolver.normalizeLabel("Mint 9")).isEqualTo("9");
        assertThat(resolver.normalizeLabel("Gem Mint 9.5")).isEqualTo("9.5");
        assertThat(resolver.normalizeLabel("Pristine-10")).isEqualTo("Pristine 10");
    }

    private BigDecimal calculate(String centering, String edges, String corners, String surface) {
        return resolver.calculateFinalGrade(
            new BigDecimal(centering),
            new BigDecimal(edges),
            new BigDecimal(corners),
            new BigDecimal(surface)
        );
    }
}
