/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.olap.util.formatter;

import java.util.LinkedHashMap;
import java.util.Map;
import org.olap4j.Cell;
import org.olap4j.metadata.Property;

/**
 * Pulls the standard olap4j {@link Property.StandardCellProperty} values
 * off a {@link Cell} and surfaces them as a flat {@code Map<String,String>}.
 * Powers saiku#773 — Mondrian computes these but the formatters used to
 * discard them.
 *
 * <p>What we surface (lowercase keys for stable JSON/Arrow contracts):
 * <ul>
 *   <li><b>formatString</b> — Mondrian format string (e.g. {@code "$#,##0.00"}).</li>
 *   <li><b>foreColor</b> / <b>backColor</b> — schema-driven cell colours
 *       (RGB-encoded integer; clients decode with the same convention
 *       Mondrian uses).</li>
 *   <li><b>fontFlags</b> / <b>fontName</b> / <b>fontSize</b> — typography hints.</li>
 *   <li><b>actionType</b> — schema-defined action binding (drill / nav).</li>
 *   <li><b>updateable</b> — write-back permission flag.</li>
 *   <li><b>datatype</b> — Mondrian's declared cell type.</li>
 *   <li><b>solveOrder</b> — calculation precedence for the cell.</li>
 *   <li><b>language</b> — locale identifier.</li>
 *   <li><b>error</b> — populated when the cell is an error cell.</li>
 * </ul>
 *
 * <p>Properties not propagated (deliberately — they're either internal
 * or already covered by other DTO fields):
 * <ul>
 *   <li>{@code VALUE} / {@code FORMATTED_VALUE} — already on
 *       {@code DataCell.rawNumber} + the format-result string.</li>
 *   <li>{@code CELL_ORDINAL} — addressing-only, exposed via
 *       {@code DataCell.coordinates}.</li>
 *   <li>{@code CELL_EVALUATION_LIST}, {@code NON_EMPTY_BEHAVIOR} —
 *       Mondrian internals, no rendering value.</li>
 * </ul>
 *
 * <p>Null values are skipped — the returned map only contains keys that
 * Mondrian actually populated for this cell. Clients can rely on
 * "key present ⇒ schema set it explicitly".
 */
public final class CellPropertyExtractor {

    private CellPropertyExtractor() {}

    /** Read every supported {@link Property.StandardCellProperty} on the
     *  cell. Returns an insertion-ordered map so JSON serialisation is
     *  deterministic across runs. */
    public static Map<String, String> extract(Cell cell) {
        Map<String, String> out = new LinkedHashMap<>();
        if (cell == null) return out;

        putIfNonNull(out, "formatString", cell, Property.StandardCellProperty.FORMAT_STRING);
        putIfNonNull(out, "foreColor", cell, Property.StandardCellProperty.FORE_COLOR);
        putIfNonNull(out, "backColor", cell, Property.StandardCellProperty.BACK_COLOR);
        putIfNonNull(out, "fontFlags", cell, Property.StandardCellProperty.FONT_FLAGS);
        putIfNonNull(out, "fontName", cell, Property.StandardCellProperty.FONT_NAME);
        putIfNonNull(out, "fontSize", cell, Property.StandardCellProperty.FONT_SIZE);
        putIfNonNull(out, "actionType", cell, Property.StandardCellProperty.ACTION_TYPE);
        putIfNonNull(out, "updateable", cell, Property.StandardCellProperty.UPDATEABLE);
        putIfNonNull(out, "datatype", cell, Property.StandardCellProperty.DATATYPE);
        putIfNonNull(out, "solveOrder", cell, Property.StandardCellProperty.SOLVE_ORDER);
        putIfNonNull(out, "language", cell, Property.StandardCellProperty.LANGUAGE);

        // Error cells: surface the error text under a stable 'error' key
        // even though it's not a StandardCellProperty per se.
        if (cell.isError()) {
            try {
                Object v = cell.getValue();
                if (v != null) out.put("error", v.toString());
            } catch (RuntimeException ignored) {
                // Mondrian sometimes throws here; the cell stays untagged.
            }
        }

        return out;
    }

    private static void putIfNonNull(Map<String, String> out, String key, Cell cell, Property.StandardCellProperty p) {
        try {
            Object v = cell.getPropertyValue(p);
            if (v == null) return;
            String s = v.toString();
            if (s.isEmpty()) return;
            out.put(key, s);
        } catch (RuntimeException ignored) {
            // Some properties throw if Mondrian's evaluator hasn't
            // materialised them for the cell — treat as absent.
        }
    }
}
