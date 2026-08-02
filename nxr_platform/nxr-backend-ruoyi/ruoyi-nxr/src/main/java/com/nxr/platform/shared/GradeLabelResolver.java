package com.nxr.platform.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
        return total.divide(BigDecimal.valueOf(4), 1, RoundingMode.HALF_UP);
    }

    public String resolveLabel(BigDecimal finalGrade) {
        if (finalGrade.compareTo(BigDecimal.valueOf(10.0)) >= 0) {
            return "Pristine 10";
        }
        if (finalGrade.compareTo(BigDecimal.valueOf(9.5)) >= 0) {
            return "Gem Mint 9.5";
        }
        if (finalGrade.compareTo(BigDecimal.valueOf(9.0)) >= 0) {
            return "Mint 9";
        }
        if (finalGrade.compareTo(BigDecimal.valueOf(8.5)) >= 0) {
            return "Near Mint-Mint+ 8.5";
        }
        if (finalGrade.compareTo(BigDecimal.valueOf(8.0)) >= 0) {
            return "Near Mint-Mint 8";
        }
        if (finalGrade.compareTo(BigDecimal.valueOf(7.5)) >= 0) {
            return "Near Mint+ 7.5";
        }
        if (finalGrade.compareTo(BigDecimal.valueOf(7.0)) >= 0) {
            return "Near Mint 7";
        }
        return "Authentic Grade";
    }
}
