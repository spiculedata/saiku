/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.generate.quickstart;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Sanitises free-text names (an uploaded file's base name, a CSV header cell) into identifiers
 * safe to reuse as a SQL table/column name, an on-disk directory component and a Saiku datasource
 * / Mondrian schema name — all three at once, since the quickstart pipeline threads the same
 * chosen name through every one of those (see {@code QuickstartIngestService}).
 *
 * <p>Every SQL identifier built from a sanitised name is still double-quoted at the call site
 * ({@link CsvTableLoader#quoteIdentifier(String)}) — sanitising here is about producing a
 * predictable, filesystem- and URL-safe name, not about SQL-injection defence, which the quoting
 * already provides on its own.
 */
final class QuickstartNames {

    private static final int MAX_LENGTH = 64;

    private QuickstartNames() {}

    /**
     * Reduce {@code raw} to {@code [A-Za-z0-9_]}, collapsing every other character to {@code _}.
     * Falls back to {@code fallback} (itself re-sanitised) when {@code raw} is blank or every
     * character is rejected. A leading digit gets a {@code t_} prefix so the result is always
     * usable as an unquoted identifier too, even though call sites quote it regardless.
     */
    static String sanitize(String raw, String fallback) {
        // saiku#1117: the fallback is typically the uploaded file's own name ("Q3 Sales.csv"),
        // so the same "strip a .csv extension, then clean" treatment applies whichever of the
        // two candidates actually gets used — not just when raw wins.
        String cleaned = cleanCandidate(raw);
        if (cleaned.isEmpty()) {
            cleaned = cleanCandidate(fallback);
        }
        if (cleaned.isEmpty()) {
            cleaned = "t";
        }
        if (Character.isDigit(cleaned.charAt(0))) {
            cleaned = "t_" + cleaned;
        }
        if (cleaned.length() > MAX_LENGTH) {
            cleaned = cleaned.substring(0, MAX_LENGTH);
        }
        return cleaned;
    }

    private static String cleanCandidate(String raw) {
        String base = raw == null ? "" : raw.trim();
        if (base.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            base = base.substring(0, base.length() - 4);
        }
        return clean(base);
    }

    private static String clean(String s) {
        // Map every non-alphanumeric run to a single underscore (not one underscore per rejected
        // character) so "q3 sales (2026)" reads as "q3_sales_2026", not "q3_sales__2026".
        StringBuilder sb = new StringBuilder(s.length());
        boolean lastWasUnderscore = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                sb.append(c);
                lastWasUnderscore = false;
            } else if (!lastWasUnderscore) {
                sb.append('_');
                lastWasUnderscore = true;
            }
        }
        // Trim leading/trailing underscores produced by punctuation at the edges (".sales.csv" ->
        // "_sales" -> "sales") so ordinary filenames don't come out looking mangled.
        int start = 0;
        int end = sb.length();
        while (start < end && sb.charAt(start) == '_') {
            start++;
        }
        while (end > start && sb.charAt(end - 1) == '_') {
            end--;
        }
        return sb.substring(start, end);
    }

    /**
     * {@code CsvTableLoader} always adds its own surrogate {@code "ID"} primary-key column
     * ahead of every loaded column — reserving the name here (case-insensitively, same as the
     * dedup rule below) means a source column that happens to be called {@code id}/{@code Id}/
     * {@code ID} renames to {@code id_2} instead of colliding with it in the {@code CREATE TABLE}.
     */
    private static final String RESERVED_PRIMARY_KEY_COLUMN = "ID";

    /**
     * Sanitise a CSV header row into unique column names: blank cells fall back to
     * {@code column_<1-based index>}, and a collision — with another header cell, or with the
     * loader's reserved {@code ID} column (case-only collisions included: H2 quoted identifiers
     * are case-sensitive, but two columns differing only in case is almost always a source-data
     * accident, not intent) — gets a {@code _2}, {@code _3}, … suffix.
     */
    static List<String> sanitizeHeader(List<String> rawHeader) {
        List<String> result = new ArrayList<>(rawHeader.size());
        Set<String> used = new HashSet<>();
        used.add(RESERVED_PRIMARY_KEY_COLUMN);
        for (int i = 0; i < rawHeader.size(); i++) {
            String base = sanitize(rawHeader.get(i), "column_" + (i + 1));
            String candidate = base;
            int suffix = 2;
            while (!used.add(candidate.toUpperCase(Locale.ROOT))) {
                candidate = base + "_" + suffix;
                suffix++;
            }
            result.add(candidate);
        }
        return result;
    }
}
