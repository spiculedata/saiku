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
 * Builds a shared Time {@link DraftDimension} and role-playing usages for fact-table date columns.
 *
 * <p>Two-step shape:
 * <ul>
 *   <li>{@link #buildSharedTimeDimension()} — one shared Time dimension with a single hierarchy of
 *       YEARS / QUARTERS / MONTHS / DAYS levels. Emitted once per schema, attached to
 *       {@code DraftSchema.sharedDimensions()}. Provenance {@code rule:time-shared}.</li>
 *   <li>{@link #buildCubeUsages(DbTable, DraftDimension)} — one role-playing usage per
 *       {@code DATE}/{@code TIMESTAMP}/{@code TIMESTAMP_WITH_TIMEZONE} column on the fact table.
 *       Each usage has {@code name = column}, {@code foreignKey = column},
 *       {@code sourceTable = shared.name()} and empty hierarchies (points at the shared dim).
 *       Provenance {@code rule:time-usage}.</li>
 * </ul>
 *
 * <p>{@code TIME} (time-of-day without a date) is deliberately ignored — it is not a calendar
 * dimension.
 *
 * <p>Pure: does not mutate the input table or the shared dimension.
 */
public class TimeDimensionBuilder {

    private static final Set<JDBCType> DATE_TYPES =
            EnumSet.of(JDBCType.DATE, JDBCType.TIMESTAMP, JDBCType.TIMESTAMP_WITH_TIMEZONE);

    private static final String SHARED_NAME = "Time";

    /** Build the shared Time dimension (Y/Q/M/D). */
    public DraftDimension buildSharedTimeDimension() {
        Provenance prov = new Provenance(Provenance.Source.RULE, "rule:time-shared", 1.0);
        DraftDimension dim = new DraftDimension(SHARED_NAME, DraftDimension.Type.TIME, prov);

        DraftHierarchy h = new DraftHierarchy(SHARED_NAME, null, prov);
        h.levels().add(new DraftLevel("Year", null, DraftLevel.Type.YEARS, prov));
        h.levels().add(new DraftLevel("Quarter", null, DraftLevel.Type.QUARTERS, prov));
        h.levels().add(new DraftLevel("Month", null, DraftLevel.Type.MONTHS, prov));
        h.levels().add(new DraftLevel("Day", null, DraftLevel.Type.DAYS, prov));

        dim.hierarchies().add(h);
        return dim;
    }

    /**
     * Build the role-playing cube usages of {@code sharedTimeDim} for each DATE/TIMESTAMP column
     * on {@code factTable}. Returns an empty list if the fact has no such columns.
     */
    public List<DraftDimension> buildCubeUsages(DbTable factTable, DraftDimension sharedTimeDim) {
        Provenance prov = new Provenance(Provenance.Source.RULE, "rule:time-usage", 1.0);
        List<DraftDimension> usages = new ArrayList<>();
        for (DbColumn c : factTable.columns()) {
            if (c.type() != null && DATE_TYPES.contains(c.type())) {
                DraftDimension usage = new DraftDimension(c.name(), DraftDimension.Type.TIME, prov);
                usage.setForeignKey(c.name());
                usage.setSourceTable(sharedTimeDim.name());
                usages.add(usage);
            }
        }
        return usages;
    }
}
