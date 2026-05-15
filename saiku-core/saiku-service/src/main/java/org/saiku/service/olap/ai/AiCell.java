/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

/**
 * Typed cell envelope. Replaces the v1 "pre-formatted string" cell shape
 * so an LLM can compute on {@link #value} without re-parsing locale-
 * specific formatted strings, and so currency / unit info round-trips
 * cleanly through the API.
 */
public class AiCell {

    /** Numeric value when parseable; null otherwise (e.g. for #null / error cells). */
    private Double value;
    /** Pre-formatted display string (from the Mondrian format string). */
    private String formatted;
    /** Optional unit / currency hint sniffed from the format string. */
    private String unit;

    public AiCell() {}

    public AiCell(Double value, String formatted, String unit) {
        this.value = value;
        this.formatted = formatted;
        this.unit = unit;
    }

    public Double getValue() {
        return value;
    }

    public void setValue(Double v) {
        this.value = v;
    }

    public String getFormatted() {
        return formatted;
    }

    public void setFormatted(String v) {
        this.formatted = v;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String v) {
        this.unit = v;
    }

    /** Best-effort: parse a Mondrian-formatted string back into a Double,
     *  stripping thousands separators and currency symbols. */
    public static Double parseValueFromFormatted(String formatted) {
        if (formatted == null || formatted.isEmpty()) return null;
        // Strip everything except digits, dot, minus, and one comma group.
        // This is locale-naive on purpose — Mondrian's default format uses
        // comma thousands separators and dot decimals. Servers running a
        // non-en locale should set saiku.format.numberformat explicitly.
        String cleaned = formatted.trim();
        // Drop a trailing % so percentage cells parse to the raw number.
        boolean percent = cleaned.endsWith("%");
        if (percent) cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        // Strip currency symbols and other non-numeric leading chars.
        int i = 0;
        while (i < cleaned.length() && !isNumeric(cleaned.charAt(i)) && cleaned.charAt(i) != '-') i++;
        cleaned = cleaned.substring(i);
        // Remove all commas — assume they're thousands separators.
        cleaned = cleaned.replace(",", "");
        if (cleaned.isEmpty()) return null;
        try {
            double n = Double.parseDouble(cleaned);
            return percent ? n / 100.0 : n;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Best-effort unit sniff from a formatted string. Handles both
     *  prefix-currency conventions ({@code "$48,836.21"} → {@code "USD"},
     *  {@code "£500.00"} → {@code "GBP"}) and trailing-currency conventions
     *  used by some locales / cube formatters ({@code "29,358.98 €"} →
     *  {@code "EUR"}). Percent cells return {@code "%"}. Sign prefixes
     *  ({@code -}/{@code +}) are stripped before the symbol check. */
    public static String sniffUnit(String formatted) {
        if (formatted == null || formatted.isEmpty()) return null;
        String s = formatted.trim();
        if (s.endsWith("%")) return "%";
        // Strip a sign prefix so "-$10" still sniffs.
        String body = (s.startsWith("-") || s.startsWith("+")) ? s.substring(1).trim() : s;
        if (body.startsWith("$") || body.endsWith("$")) return "USD";
        if (body.startsWith("£") || body.endsWith("£")) return "GBP";
        if (body.startsWith("€") || body.endsWith("€")) return "EUR";
        if (body.startsWith("¥") || body.endsWith("¥")) return "JPY";
        return null;
    }

    private static boolean isNumeric(char c) {
        return (c >= '0' && c <= '9') || c == '.';
    }
}
