/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.generate.infer;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.saiku.service.schema.generate.model.DbForeignKey;
import org.saiku.service.schema.generate.model.DbModel;
import org.saiku.service.schema.generate.model.DbTable;

/**
 * Stateless heuristic classifier that assigns every {@link DbTable} in a {@link DbModel} a
 * {@link TableClassification}.
 *
 * <p>Rules (deliberately simple, documented for auditability):
 *
 * <ul>
 *   <li><b>FACT</b> — {@code foreignKeys.size() >= 2} AND {@code rowCountEstimate != null &&
 *       rowCountEstimate >= 1000}. A {@code null} row count is treated as "unknown" and
 *       disqualifies the table from fact status.
 *   <li><b>DIMENSION</b> — not a fact, but referenced by at least one fact table's FK.
 *   <li><b>ORPHAN</b> — everything else.
 * </ul>
 *
 * <p>Edge case: a table can simultaneously have outgoing FKs and be referenced by a fact. We
 * prefer {@code FACT} in that case — the FK-out + row-count heuristic is the stronger signal
 * (a reference-from-fact just says "some fact has a column pointing here", which is cheap;
 * having many outgoing FKs and many rows is the actual shape of a fact).
 */
public class TableClassifier {

    private static final int FACT_MIN_FK_OUT = 2;
    private static final long FACT_MIN_ROWS = 1000L;

    /**
     * Classify every table in {@code model}. The returned map preserves {@code model.tables()}'
     * iteration order and contains exactly one entry per table.
     */
    public Map<DbTable, TableClassification> classify(DbModel model) {
        // Pass 1: identify facts.
        Set<DbTable> facts = new LinkedHashSet<>();
        for (DbTable t : model.tables()) {
            if (isFact(t)) {
                facts.add(t);
            }
        }

        // Pass 2: collect the set of table names referenced by any fact's FKs, tracking which
        // fact did the referencing so the reason string can name it.
        Map<String, String> referencedByFact = new LinkedHashMap<>();
        for (DbTable fact : facts) {
            for (DbForeignKey fk : fact.foreignKeys()) {
                referencedByFact.putIfAbsent(fk.toTable(), fact.name());
            }
        }

        // Pass 3: assign a classification to every table.
        Map<DbTable, TableClassification> out = new LinkedHashMap<>();
        for (DbTable t : model.tables()) {
            if (facts.contains(t)) {
                out.put(t, new TableClassification(TableClassification.Kind.FACT, factReason(t)));
            } else if (referencedByFact.containsKey(t.name())) {
                String factName = referencedByFact.get(t.name());
                out.put(
                        t,
                        new TableClassification(
                                TableClassification.Kind.DIMENSION, "referenced by fact '" + factName + "'"));
            } else {
                out.put(t, new TableClassification(TableClassification.Kind.ORPHAN, orphanReason(t)));
            }
        }
        return out;
    }

    private static boolean isFact(DbTable t) {
        Long rows = t.rowCountEstimate();
        return t.foreignKeys().size() >= FACT_MIN_FK_OUT && rows != null && rows >= FACT_MIN_ROWS;
    }

    private static String factReason(DbTable t) {
        int fkOut = t.foreignKeys().size();
        Long rows = t.rowCountEstimate();
        return fkOut + " outgoing FKs, ~" + formatRowCount(rows) + " rows";
    }

    private static String orphanReason(DbTable t) {
        int fkOut = t.foreignKeys().size();
        Long rows = t.rowCountEstimate();
        if (fkOut == 0) {
            return "no FK relationships";
        }
        if (rows == null) {
            return fkOut + " outgoing FKs, row count unknown; not referenced by any fact";
        }
        return fkOut + " outgoing FKs, ~" + formatRowCount(rows) + " rows; not referenced by any fact";
    }

    private static String formatRowCount(Long rows) {
        if (rows == null) {
            return "?";
        }
        return String.format(java.util.Locale.ROOT, "%,d", rows);
    }
}
