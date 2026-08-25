/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.generate.infer;

import java.sql.JDBCType;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.saiku.service.schema.generate.draft.DraftMeasure;
import org.saiku.service.schema.generate.draft.Provenance;
import org.saiku.service.schema.generate.model.DbColumn;
import org.saiku.service.schema.generate.model.DbForeignKey;
import org.saiku.service.schema.generate.model.DbTable;

/**
 * Builds draft measures for a fact table.
 *
 * <p>Rule: every non-PK, non-FK, non-date numeric column becomes a {@code SUM} measure;
 * an implicit {@code Fact Count} {@code count(*)} measure is always appended. Naming
 * mirrors the column name — a later LLM pass is expected to supply proper captions.
 */
public class MeasureBuilder {

    private static final Set<JDBCType> NUMERIC_TYPES = EnumSet.of(
            JDBCType.TINYINT,
            JDBCType.SMALLINT,
            JDBCType.INTEGER,
            JDBCType.BIGINT,
            JDBCType.REAL,
            JDBCType.FLOAT,
            JDBCType.DOUBLE,
            JDBCType.DECIMAL,
            JDBCType.NUMERIC);

    private static final Set<JDBCType> DATE_TYPES =
            EnumSet.of(JDBCType.DATE, JDBCType.TIME, JDBCType.TIMESTAMP, JDBCType.TIMESTAMP_WITH_TIMEZONE);

    public List<DraftMeasure> build(DbTable factTable) {
        Set<String> fkColumns = new HashSet<>();
        for (DbForeignKey fk : factTable.foreignKeys()) {
            fkColumns.add(fk.fromColumn());
        }

        List<DraftMeasure> measures = new ArrayList<>();
        for (DbColumn col : factTable.columns()) {
            if (col.primaryKey()) {
                continue;
            }
            if (fkColumns.contains(col.name())) {
                continue;
            }
            if (DATE_TYPES.contains(col.type())) {
                continue;
            }
            if (!NUMERIC_TYPES.contains(col.type())) {
                continue;
            }
            measures.add(new DraftMeasure(
                    col.name(),
                    col.name(),
                    DraftMeasure.Aggregator.SUM,
                    new Provenance(Provenance.Source.RULE, "rule:measure-sum", 1.0)));
        }

        // Fact Count. Mondrian 4 evaluates <Measure aggregator="count"/> with no column as a
        // tuple-count (always 1 at the All level) rather than a SQL count(*), so we anchor the
        // measure on the fact table's PK column when one exists. That makes the generated SQL
        // "count(<pk>)" which is equivalent to count(*) for a non-null PK — this is also the
        // convention used in the reference FoodMart schema (e.g. "Sales Count" on product_id).
        String factCountColumn = null;
        for (DbColumn c : factTable.columns()) {
            if (c.primaryKey()) {
                factCountColumn = c.name();
                break;
            }
        }
        measures.add(new DraftMeasure(
                "Fact Count",
                factCountColumn,
                DraftMeasure.Aggregator.COUNT_STAR,
                new Provenance(Provenance.Source.RULE, "rule:measure-fact-count", 1.0)));

        return measures;
    }
}
