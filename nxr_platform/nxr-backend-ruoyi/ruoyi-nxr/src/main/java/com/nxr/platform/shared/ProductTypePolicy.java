package com.nxr.platform.shared;

import java.util.Locale;
import java.util.Map;

/** Canonical product-type values shared by admin, customer, and public read paths. */
public final class ProductTypePolicy {

    public static final String GRADED_CARD = "graded_card";
    public static final String MERCH_PRODUCT = "merch_product";
    public static final String VINTAGE_PRODUCT = "vintage_product";

    private static final Map<String, String> LABELS = Map.of(
        GRADED_CARD, "Graded Card",
        MERCH_PRODUCT, "Merch Product",
        VINTAGE_PRODUCT, "Vintage Card"
    );

    private ProductTypePolicy() {
    }

    /**
     * Returns a canonical code for supported current and historical values, or
     * {@code null} when a submitted value is not supported.
     */
    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return GRADED_CARD;
        }

        String token = value.trim()
            .toLowerCase(Locale.ROOT)
            .replace('_', ' ')
            .replace('-', ' ')
            .replaceAll("\\s+", " ");
        return switch (token) {
            case "graded", "graded card", "card" -> GRADED_CARD;
            case "merch", "merch product", "label", "label product" -> MERCH_PRODUCT;
            case "vintage", "vintage card", "vintage product" -> VINTAGE_PRODUCT;
            default -> null;
        };
    }

    /** Historical or unknown stored values remain safe for read-only responses. */
    public static String normalizeStored(String value) {
        String normalized = normalize(value);
        return normalized == null ? GRADED_CARD : normalized;
    }

    public static String label(String value) {
        return LABELS.getOrDefault(normalizeStored(value), LABELS.get(GRADED_CARD));
    }

    /**
     * Builds the SQL equivalent of {@link #normalizeStored(String)} for filters
     * that must include historical {@code label_product} rows.
     */
    public static String canonicalSql(String columnName) {
        if (columnName == null || !columnName.matches("[A-Za-z0-9_.]+")) {
            throw new IllegalArgumentException("Unsafe product type column name");
        }
        String raw = "LOWER(TRIM(COALESCE(" + columnName + ", '')))";
        return "CASE "
            + "WHEN " + raw + " IN ('merch', 'merch product', 'merch_product', 'merch-product', "
            + "'label', 'label product', 'label_product', 'label-product') THEN 'merch_product' "
            + "WHEN " + raw + " IN ('vintage', 'vintage card', 'vintage_card', 'vintage-card', "
            + "'vintage product', 'vintage_product', 'vintage-product') THEN 'vintage_product' "
            + "WHEN " + raw + " IN ('', 'graded', 'graded card', 'graded_card', 'graded-card', 'card') "
            + "THEN 'graded_card' ELSE 'graded_card' END";
    }
}
