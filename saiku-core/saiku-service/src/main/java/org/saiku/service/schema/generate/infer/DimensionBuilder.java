/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.generate.infer;

import java.sql.JDBCType;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.saiku.service.schema.generate.draft.DraftDimension;
import org.saiku.service.schema.generate.draft.DraftHierarchy;
import org.saiku.service.schema.generate.draft.DraftJoin;
import org.saiku.service.schema.generate.draft.DraftLevel;
import org.saiku.service.schema.generate.draft.Provenance;
import org.saiku.service.schema.generate.model.DbColumn;
import org.saiku.service.schema.generate.model.DbForeignKey;
import org.saiku.service.schema.generate.model.DbModel;
import org.saiku.service.schema.generate.model.DbTable;

/**
 * Builds a {@link DraftDimension} from a dimension-kind {@link DbTable}.
 *
 * <p>Supports two rule-based shapes:
 * <ul>
 *   <li>{@code rule:dim-flat} — a single-level dimension whose level key is the PK and whose
 *       caption/name column is the sole non-PK string column (if exactly one exists).</li>
 *   <li>{@code rule:snowflake-1hop} — when the dim table has exactly one outgoing FK, inlines
 *       the referenced table as a second level via a {@link DraftJoin}. Deeper snowflakes
 *       are not recursed.</li>
 * </ul>
 *
 * <p>Pure: does not mutate the input {@link DbModel}. The LLM pass is expected to rename
 * dimensions/hierarchies/levels; this builder keeps names raw.
 */
public class DimensionBuilder {

    private static final Set<JDBCType> STRING_TYPES = EnumSet.of(
            JDBCType.CHAR,
            JDBCType.VARCHAR,
            JDBCType.LONGVARCHAR,
            JDBCType.NCHAR,
            JDBCType.NVARCHAR,
            JDBCType.LONGNVARCHAR);

    /** Build a draft dimension for {@code dimTable}. */
    public DraftDimension build(DbTable dimTable, DbModel model) {
        String pkName = primaryKeyName(dimTable);

        // Snowflake is only allowed when there is exactly one outgoing FK AND that FK resolves.
        List<DbForeignKey> fks = dimTable.foreignKeys();
        DbTable childTable = null;
        DbForeignKey snowflakeFk = null;
        if (fks.size() == 1) {
            DbForeignKey fk = fks.get(0);
            Optional<DbTable> resolved = model.tableByName(fk.toTable());
            if (resolved.isPresent()) {
                childTable = resolved.get();
                snowflakeFk = fk;
            }
        }

        boolean snowflake = snowflakeFk != null;
        String ruleId = snowflake ? "rule:snowflake-1hop" : "rule:dim-flat";
        double confidence = snowflake ? 0.9 : 1.0;
        Provenance prov = new Provenance(Provenance.Source.RULE, ruleId, confidence);

        DraftDimension dim = new DraftDimension(dimTable.name(), DraftDimension.Type.STANDARD, prov);
        dim.setSourceTable(dimTable.name());

        DraftHierarchy h = new DraftHierarchy(dimTable.name(), pkName, prov);

        // First level: caption-source col from dimTable if exactly one non-PK string col; else PK.
        DraftLevel firstLevel = levelForTable(dimTable, pkName, prov);
        h.levels().add(firstLevel);

        if (snowflake) {
            h.setJoin(new DraftJoin(
                    dimTable.name(), snowflakeFk.fromColumn(), childTable.name(), snowflakeFk.toColumn()));
            String childPk = primaryKeyName(childTable);
            DraftLevel secondLevel = levelForTable(childTable, childPk, prov);
            // Mark the snowflake-side level with its physical table so the writer can emit
            // table="<lookup>" on the Mondrian Attribute — required for the PhysicalSchema
            // Link to actually resolve the column.
            secondLevel.setTable(childTable.name());
            h.levels().add(secondLevel);
        }

        dim.hierarchies().add(h);
        return dim;
    }

    private DraftLevel levelForTable(DbTable table, String pkName, Provenance prov) {
        List<DbColumn> stringCols = new ArrayList<>();
        for (DbColumn c : table.columns()) {
            if (!c.primaryKey() && c.type() != null && STRING_TYPES.contains(c.type())) {
                stringCols.add(c);
            }
        }
        // Level KEY (column) always prefers the PK so distinct-member identity is preserved —
        // using the caption string as the key would collapse rows that share a name but differ
        // by id, and would blow up on non-unique captions. Fall back to the first column only
        // when the table has no PK at all.
        String column = pkName != null
                ? pkName
                : (table.columns().isEmpty() ? null : table.columns().get(0).name());
        String levelName = column;
        // Caption source (nameColumn) = the sole non-PK string column when exactly one exists.
        // Zero or many string cols → no caption; Mondrian will render the key directly.
        String nameCol = stringCols.size() == 1 ? stringCols.get(0).name() : null;
        DraftLevel level = new DraftLevel(levelName, column, DraftLevel.Type.REGULAR, prov);
        if (nameCol != null) {
            level.setNameColumn(nameCol);
        }
        return level;
    }

    private String primaryKeyName(DbTable table) {
        for (DbColumn c : table.columns()) {
            if (c.primaryKey()) {
                return c.name();
            }
        }
        return null;
    }
}
