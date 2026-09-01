package com.nxr.platform.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class GradeLabelResolver {

    public BigDecimal calculateFinalGrade(
        BigDecimal centering,
        BigDecimal edges,
        BigDecimal corners,
        BigDecimal surface
    ) {
        BigDecimal total = centering.add(edges).add(corners).add(surface);
        return total.divide(BigDecimal.valueOf(4), 2, RoundingMode.HALF_UP);
    }

    public String resolveLabel(BigDecimal finalGrade) {
        if (finalGrade.compareTo(new BigDecimal("8.50")) < 0) {
            return "8";
        }
        if (finalGrade.compareTo(new BigDecimal("9.00")) < 0) {
            return "8.5";
        }
        if (finalGrade.compareTo(new BigDecimal("9.35")) < 0) {
            return "9";
        }
        if (finalGrade.compareTo(new BigDecimal("9.75")) < 0) {
            return "9.5";
        }
        if (finalGrade.compareTo(new BigDecimal("10.00")) < 0) {
            return "10";
        }
        return "Pristine 10";
    }

    /**
     * Convert both current Python labels and historical descriptive Java labels
     * to the six canonical values used by the live Python workflow.
     */
    public String normalizeLabel(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String compact = value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.]", "");
        return switch (compact) {
            case "8", "8.0", "8.00", "nearmintmint8" -> "8";
            case "8.5", "8.50", "nearmintmint8.5" -> "8.5";
            case "9", "9.0", "9.00", "mint9" -> "9";
            case "9.5", "9.50", "gemmint9.5" -> "9.5";
            case "10", "10.0", "10.00" -> "10";
            default -> compact.contains("stine10") ? "Pristine 10" : null;
        };
    }

    public String canonicalOrOriginal(String value) {
        String canonical = normalizeLabel(value);
        return canonical == null ? value : canonical;
    }

    /**
     * SQL equivalent of {@link #normalizeLabel(String)} for filters and POP
     * counts. The expression is compatible with MySQL and H2 MySQL mode.
     */
    public static String canonicalSql(String columnName) {
        String trimmed = "TRIM(COALESCE(" + columnName + ", ''))";
        String upper = "UPPER(" + trimmed + ")";
        String compact = "REPLACE(REPLACE(" + upper + ", ' ', ''), '-', '')";
        return "CASE "
            + "WHEN " + upper + " IN ('8', '8.0', '8.00', 'NEAR MINT-MINT 8') THEN '8' "
            + "WHEN " + upper + " IN ('8.5', '8.50', 'NEAR MINT-MINT+ 8.5') THEN '8.5' "
            + "WHEN " + upper + " IN ('9', '9.0', '9.00', 'MINT 9') THEN '9' "
            + "WHEN " + upper + " IN ('9.5', '9.50', 'GEM MINT 9.5') THEN '9.5' "
            + "WHEN " + upper + " IN ('10', '10.0', '10.00') THEN '10' "
            + "WHEN " + compact + " LIKE '%STINE10%' THEN 'Pristine 10' "
            + "ELSE NULL END";
    }
}
