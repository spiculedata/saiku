/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.generate.infer;

import java.sql.JDBCType;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.saiku.service.schema.generate.draft.DraftDimension;
import org.saiku.service.schema.generate.draft.DraftHierarchy;
import org.saiku.service.schema.generate.draft.DraftLevel;
import org.saiku.service.schema.generate.draft.Provenance;
import org.saiku.service.schema.generate.model.DbColumn;
import org.saiku.service.schema.generate.model.DbTable;

/**
 * Builds per-cube, degenerate Time {@link DraftDimension}s for each fact-table date column.
 *
 * <p>Design notes: earlier iterations emitted a single schema-scope shared {@code Time} dimension
 * backed by a {@code <Table name="Time"/>} reference, plus role-playing usages per date column.
 * Because no such calendar table exists in the source database, Mondrian accepted the XML but
 * couldn't query the dim. The writer bug is fixed by colocating the dim on the fact table and
 * deriving Y/Q/M/D levels via SQL expressions ({@code YEAR}/{@code QUARTER}/{@code MONTH}/
 * {@code DAY}) — see {@code docs/plans/2026-04-19-schema-autogeneration-design.md} "Time
 * dimensions (degenerate)".
 *
 * <p>One {@link DraftDimension} per {@code DATE}/{@code TIMESTAMP}/{@code TIMESTAMP_WITH_TIMEZONE}
 * column on the fact. Name is the source column name, {@code type = TIME},
 * {@code sourceTable = factTable.name()}, {@code foreignKey = null} (degenerate — no FK link
 * needed, the dim attributes evaluate over the fact row directly), one hierarchy with four levels
 * whose {@code column} tracks the source date column and {@code expression} carries the scalar
 * extraction SQL. Provenance {@code rule:time-degenerate}.
 *
 * <p>{@code JDBCType.TIME} (time-of-day without a date) is deliberately excluded — not a calendar
 * dimension.
 *
 * <p>Pure: does not mutate the input table.
 */
public class TimeDimensionBuilder {

    private static final Set<JDBCType> DATE_TYPES =
            EnumSet.of(JDBCType.DATE, JDBCType.TIMESTAMP, JDBCType.TIMESTAMP_WITH_TIMEZONE);

    private static final String RULE_ID = "rule:time-degenerate";

    /**
     * Build one degenerate Time dimension per DATE/TIMESTAMP column on {@code factTable}.
     * Returns an empty list if the fact has no such columns.
     */
    public List<DraftDimension> buildCubeTimeDimensions(DbTable factTable) {
        Provenance prov = new Provenance(Provenance.Source.RULE, RULE_ID, 1.0);
        List<DraftDimension> dims = new ArrayList<>();
        for (DbColumn c : factTable.columns()) {
            if (c.type() != null && DATE_TYPES.contains(c.type())) {
                dims.add(buildOne(factTable.name(), c.name(), prov));
            }
        }
        return dims;
    }

    private DraftDimension buildOne(String factTableName, String dateColumn, Provenance prov) {
        DraftDimension dim = new DraftDimension(dateColumn, DraftDimension.Type.TIME, prov);
        dim.setSourceTable(factTableName);
        // Degenerate dim: attributes evaluate over the fact row. No foreignKey.

        DraftHierarchy h = new DraftHierarchy(dateColumn, dateColumn, prov);

        h.levels().add(level("Year", dateColumn, DraftLevel.Type.YEARS, "YEAR(" + dateColumn + ")", prov));
        h.levels().add(level("Quarter", dateColumn, DraftLevel.Type.QUARTERS, "QUARTER(" + dateColumn + ")", prov));
        h.levels().add(level("Month", dateColumn, DraftLevel.Type.MONTHS, "MONTH(" + dateColumn + ")", prov));
        h.levels().add(level("Day", dateColumn, DraftLevel.Type.DAYS, "DAY(" + dateColumn + ")", prov));

        dim.hierarchies().add(h);
        return dim;
    }

    private static DraftLevel level(
            String name, String column, DraftLevel.Type type, String expression, Provenance prov) {
        DraftLevel l = new DraftLevel(name, column, type, prov);
        l.setExpression(expression);
        return l;
    }
}
