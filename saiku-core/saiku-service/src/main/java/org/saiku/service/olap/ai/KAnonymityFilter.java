/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.dto.resultset.DataCell;
import org.saiku.olap.dto.resultset.MemberCell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * K-anonymity small-cell suppression (saiku#905). Masks any aggregated cell
 * whose underlying row count is below {@code ai.kAnonymity} (default 5) before
 * the result crosses the AI boundary — the standard small-cell control from
 * healthcare/government statistical releases. A "SUM of salary by department"
 * looks anonymised until a department of one makes the sum that person's salary;
 * this closes that leak, which the PII annotation (#902) and policy switch
 * (#903) cannot reach because they operate on schema/policy, not cardinality.
 *
 * <p>Config (env &gt; system property &gt; default), fail-soft to the SAFE
 * default on a bad value:
 * <ul>
 *   <li>{@code SAIKU_AI_KANONYMITY} / {@code ai.kAnonymity} — threshold k
 *       (default 5; {@code 0} disables; negative/invalid → default 5).</li>
 *   <li>{@code SAIKU_AI_KANONYMITY_MASK} / {@code ai.kAnonymity.maskValue} —
 *       {@code null} (default → value null, formatted {@code —}),
 *       a number like {@code -1}, or any token e.g. {@code REDACTED} /
 *       {@code <5}.</li>
 * </ul>
 *
 * <p>This class only decides + masks; deriving the per-cell row count is the
 * caller's job (saiku#905 v1: an in-result count measure; the shadow-count
 * query for arbitrary cubes is the follow-up).
 */
public class KAnonymityFilter {

    private static final Logger log = LoggerFactory.getLogger(KAnonymityFilter.class);

    public static final String ENV_K = "SAIKU_AI_KANONYMITY";
    public static final String PROP_K = "ai.kAnonymity";
    public static final int DEFAULT_K = 5;
    public static final String ENV_MASK = "SAIKU_AI_KANONYMITY_MASK";
    public static final String PROP_MASK = "ai.kAnonymity.maskValue";
    public static final String DEFAULT_MASK = "null";

    /** saiku#905 — matches a column caption that names a row-count measure
     *  (e.g. "Fact Count", "Count", "Distinct Count", "Row Count") on a whole-
     *  word boundary, so ordinary numeric measures that merely *contain* the
     *  substring — {@code Discount}, {@code Account}, {@code Counter} — are NOT
     *  mistaken for the count column. Keying suppression off a non-count measure
     *  would silently leak the very small cells k-anonymity exists to mask
     *  (SEC #1324 / QA finding). Trailing plural {@code s} is allowed
     *  ("Counts") but {@code Discounts}/{@code Counter} stay excluded. Single
     *  source of truth shared by {@code AiQueryResource} (records/matrix egress,
     *  saiku-web) and {@link #applyToCellDataSet} (the chained-ask grid, here). */
    public static final Pattern COUNT_MEASURE = Pattern.compile("\\bcounts?\\b", Pattern.CASE_INSENSITIVE);

    private final int threshold;
    private final String maskValue;

    /** Production constructor — resolves config from the real environment +
     *  system properties and logs the active posture once at boot. */
    public KAnonymityFilter() {
        this(resolveThreshold(System::getenv, System::getProperty), resolveMask(System::getenv, System::getProperty));
        if (enabled()) {
            log.info("AI k-anonymity suppression ENABLED (k={}, mask={})", threshold, maskValue);
        } else {
            log.info("AI k-anonymity suppression DISABLED ({}=0)", PROP_K);
        }
    }

    /** Explicit constructor for tests / programmatic wiring. */
    public KAnonymityFilter(int threshold, String maskValue) {
        this.threshold = Math.max(0, threshold);
        this.maskValue = (maskValue == null || maskValue.isBlank()) ? DEFAULT_MASK : maskValue.trim();
    }

    /** Testable factory (env + property lookups injected). */
    public static KAnonymityFilter from(Function<String, String> env, Function<String, String> prop) {
        return new KAnonymityFilter(resolveThreshold(env, prop), resolveMask(env, prop));
    }

    static int resolveThreshold(Function<String, String> env, Function<String, String> prop) {
        String raw = env.apply(ENV_K);
        if (raw == null || raw.isBlank()) {
            raw = prop.apply(PROP_K);
        }
        if (raw == null || raw.isBlank()) {
            return DEFAULT_K;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            if (v < 0) {
                log.warn("Invalid {} '{}' (negative) — defaulting to {}", PROP_K, raw, DEFAULT_K);
                return DEFAULT_K;
            }
            return v;
        } catch (NumberFormatException e) {
            // Fail SAFE: an unparseable threshold protects at the default rather
            // than silently disabling suppression.
            log.warn("Invalid {} '{}' (not an integer) — defaulting to {}", PROP_K, raw, DEFAULT_K);
            return DEFAULT_K;
        }
    }

    static String resolveMask(Function<String, String> env, Function<String, String> prop) {
        String raw = env.apply(ENV_MASK);
        if (raw == null || raw.isBlank()) {
            raw = prop.apply(PROP_MASK);
        }
        return (raw == null || raw.isBlank()) ? DEFAULT_MASK : raw.trim();
    }

    public int threshold() {
        return threshold;
    }

    public String maskValue() {
        return maskValue;
    }

    /** Suppression is active only when k &gt; 0. */
    public boolean enabled() {
        return threshold > 0;
    }

    /**
     * True when a cell backed by {@code rowCount} underlying records must be
     * masked: enabled, the count is known ({@code &gt; 0}) and below k. A count
     * of exactly k is NOT masked (inclusive), and an unknown count
     * ({@code &lt;= 0}) is left to the caller (we don't mask what we can't
     * measure).
     */
    public boolean shouldSuppress(int rowCount) {
        return enabled() && rowCount > 0 && rowCount < threshold;
    }

    /** Mask a single cell in place per the configured {@code maskValue} and
     *  flag it suppressed. */
    public void mask(AiCell cell) {
        if (cell == null) {
            return;
        }
        cell.setSuppressed(true);
        cell.setUnit(null);
        if ("null".equalsIgnoreCase(maskValue)) {
            cell.setValue(null);
            cell.setFormatted("—");
            return;
        }
        try {
            double num = Double.parseDouble(maskValue);
            cell.setValue(num);
            cell.setFormatted(maskValue);
        } catch (NumberFormatException notNumeric) {
            // A token like REDACTED or "<5": no numeric value, show the token.
            cell.setValue(null);
            cell.setFormatted(maskValue);
        }
    }

    /**
     * Apply suppression to a records-format result (saiku#905 v1). For each row
     * whose {@code countKey} measure cell reports a row count below k, mask
     * every measure cell in that row (including the count cell — a count of 3
     * is itself disclosive). Row-header (String) values are untouched.
     *
     * @param rows the records payload ({@code Map<caption, AiCell|String>} per row)
     * @param countKey the measure caption carrying the underlying row count
     * @param measureKeys the measure-column captions to mask when the row is small
     * @return number of rows suppressed
     */
    public int applyToRecords(List<Map<String, Object>> rows, String countKey, Collection<String> measureKeys) {
        return applyToRows(rows, countKey, measureKeys);
    }

    /**
     * saiku#1324 — matrix-format variant of {@link #applyToRecords}. The
     * {@code format=matrix} payload is index-keyed and typed {@link AiCell}, but
     * the suppression decision is identical: a row whose {@code countKey} cell is
     * below k has every {@code measureKeys} cell masked. Shares the same core as
     * the records path so the two egress shapes can never drift apart (which is
     * how the original count-detection gap slipped in).
     */
    public int applyToMatrix(List<Map<String, AiCell>> rows, String countKey, Collection<String> measureKeys) {
        return applyToRows(rows, countKey, measureKeys);
    }

    /**
     * Shared suppression core over any String-keyed, {@link AiCell}-valued row
     * map — records carry {@code Object} values (cell or row-header String),
     * matrix carries {@code AiCell} directly. Reads only, masks cells in place.
     */
    private int applyToRows(List<? extends Map<String, ?>> rows, String countKey, Collection<String> measureKeys) {
        if (!enabled() || rows == null || countKey == null || measureKeys == null) {
            return 0;
        }
        int suppressed = 0;
        for (Map<String, ?> row : rows) {
            if (row == null) {
                continue;
            }
            Object countCell = row.get(countKey);
            if (!(countCell instanceof AiCell)) {
                continue;
            }
            Double cv = ((AiCell) countCell).getValue();
            if (cv == null) {
                continue;
            }
            int rowCount = (int) Math.round(cv);
            if (!shouldSuppress(rowCount)) {
                continue;
            }
            for (String mk : measureKeys) {
                Object c = row.get(mk);
                if (c instanceof AiCell) {
                    mask((AiCell) c);
                }
            }
            suppressed++;
        }
        if (suppressed > 0) {
            log.debug("k-anon suppressed {} small cell-row(s) (k={})", suppressed, threshold);
        }
        return suppressed;
    }

    /**
     * K-anonymity for a pivoted {@link CellDataSet} grid (the shape the chained-ask loop digests).
     * Mirrors {@link #applyToMatrix}: locate the count column among the measure columns by caption
     * ({@link #COUNT_MEASURE}); for each body row whose count is below k, mask every measure cell
     * in that row. No-op when disabled, empty, or there is no count column (matching the
     * records/matrix paths — the shadow-count follow-up, saiku#905, is out of scope). Closes the
     * chained-ask egress bypass where sub-k rows reached the LLM unmasked (same class of gap as the
     * shipped matrix-format fix, saiku#1482).
     *
     * @return number of rows suppressed
     */
    public int applyToCellDataSet(CellDataSet cds) {
        if (!enabled() || cds == null) {
            return 0;
        }
        AbstractBaseCell[][] body = cds.getCellSetBody();
        if (body == null || body.length == 0) {
            return 0;
        }

        // Row-header columns = leading MemberCell columns (mirror AiQueryResource.countRowHeaderColumns).
        int rowHeaderCount = 0;
        AbstractBaseCell[] first = body[0];
        while (rowHeaderCount < first.length && first[rowHeaderCount] instanceof MemberCell) {
            rowHeaderCount++;
        }

        // Column captions: join ALL header rows with fill-down (mirrors
        // AiQueryResource.buildResponse). The AI query converter always nests measures ABOVE any
        // column dimension, so on a multi-level column axis (e.g. measures x quarters) the LAST
        // header row alone holds the dimension members, not the measure captions -- reading only
        // that row misses the count column entirely and leaves sub-k rows unmasked.
        AbstractBaseCell[][] headers = cds.getCellSetHeaders();
        int countCol = -1;
        if (headers != null && headers.length > 0) {
            int colCount = headers[headers.length - 1].length;
            String[][] filled = new String[headers.length][colCount];
            for (int hRow = 0; hRow < headers.length; hRow++) {
                String last = "";
                for (int c = 0; c < colCount; c++) {
                    if (headers[hRow] == null || c >= headers[hRow].length) {
                        filled[hRow][c] = "";
                        continue;
                    }
                    String seg = headers[hRow][c] == null ? "" : nullToEmpty(headers[hRow][c].getFormattedValue());
                    if (!seg.isEmpty()) {
                        last = seg;
                    }
                    filled[hRow][c] = last;
                }
            }
            for (int c = rowHeaderCount; c < colCount; c++) {
                StringBuilder cap = new StringBuilder();
                for (int hRow = 0; hRow < headers.length; hRow++) {
                    String seg = filled[hRow][c];
                    if (seg == null || seg.isEmpty()) {
                        continue;
                    }
                    if (cap.length() > 0) {
                        cap.append(" | ");
                    }
                    cap.append(seg);
                }
                if (COUNT_MEASURE.matcher(cap.toString()).find()) {
                    countCol = c;
                    break;
                }
            }
        }
        if (countCol < 0) {
            return 0; // no count column anywhere in the joined captions -> no-op (same as records/matrix)
        }

        int suppressed = 0;
        for (AbstractBaseCell[] row : body) {
            if (row == null || countCol >= row.length) {
                continue;
            }
            int count = parseCount(row[countCol]);
            if (shouldSuppress(count)) {
                for (int c = rowHeaderCount; c < row.length; c++) {
                    maskGridCell(row[c]);
                }
                suppressed++;
            }
        }
        if (suppressed > 0) {
            log.debug("k-anon suppressed {} small cell-row(s) in chained-ask grid (k={})", suppressed, threshold);
        }
        return suppressed;
    }

    /** Null-safe passthrough used while joining header captions (mirrors {@code AiQueryResource#safe}). */
    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * Parse the underlying row count from a grid count cell (prefer {@link DataCell#getRawNumber},
     * else the formatted value). Returns 0 ("unknown", never suppressed) when unparseable.
     */
    private static int parseCount(AbstractBaseCell cell) {
        if (cell == null) {
            return 0;
        }
        if (cell instanceof DataCell) {
            Number raw = ((DataCell) cell).getRawNumber();
            if (raw != null) {
                return (int) Math.round(raw.doubleValue());
            }
        }
        String f = cell.getFormattedValue();
        if (f == null || f.isBlank()) {
            return 0;
        }
        try {
            return (int) Math.round(Double.parseDouble(f.replace(",", "").trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Mask one grid cell in place, matching {@link #mask(AiCell)}'s display: em dash for the
     * default "null" maskValue, else the maskValue token. Clears any raw number so the digest
     * can't read it.
     */
    private void maskGridCell(AbstractBaseCell cell) {
        if (cell == null) {
            return;
        }
        String display = "null".equalsIgnoreCase(maskValue) ? "—" : maskValue;
        cell.setFormattedValue(display);
        cell.setRawValue(null);
        if (cell instanceof DataCell) {
            ((DataCell) cell).setRawNumber(null);
        }
    }

    /** Lowercase display name of the configured mask, for {@code /info}. */
    public String describe() {
        return enabled() ? ("k=" + threshold + ",mask=" + maskValue.toLowerCase(Locale.ROOT)) : "disabled";
    }
}
