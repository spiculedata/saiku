package org.saiku.service.schema.generate.infer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.sql.JDBCType;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.saiku.service.schema.generate.draft.DraftDimension;
import org.saiku.service.schema.generate.draft.DraftHierarchy;
import org.saiku.service.schema.generate.draft.DraftLevel;
import org.saiku.service.schema.generate.draft.Provenance;
import org.saiku.service.schema.generate.model.DbColumn;
import org.saiku.service.schema.generate.model.DbForeignKey;
import org.saiku.service.schema.generate.model.DbTable;

public class TimeDimensionBuilderTest {

    private DbColumn col(String name, JDBCType type) {
        return new DbColumn(name, type, true, false);
    }

    private DbColumn pk(String name) {
        return new DbColumn(name, JDBCType.INTEGER, false, true);
    }

    @Test
    public void buildSharedTimeDimensionEmitsYQMDLevels() {
        DraftDimension time = new TimeDimensionBuilder().buildSharedTimeDimension();

        assertEquals("Time", time.name());
        assertEquals(DraftDimension.Type.TIME, time.type());
        assertEquals(Provenance.Source.RULE, time.provenance().source());
        assertEquals("rule:time-shared", time.provenance().ruleId());

        assertEquals(1, time.hierarchies().size());
        DraftHierarchy h = time.hierarchies().get(0);
        assertEquals(4, h.levels().size());
        assertEquals(DraftLevel.Type.YEARS, h.levels().get(0).type());
        assertEquals(DraftLevel.Type.QUARTERS, h.levels().get(1).type());
        assertEquals(DraftLevel.Type.MONTHS, h.levels().get(2).type());
        assertEquals(DraftLevel.Type.DAYS, h.levels().get(3).type());

        for (DraftLevel l : h.levels()) {
            assertEquals(Provenance.Source.RULE, l.provenance().source());
        }
    }

    @Test
    public void buildCubeUsagesForFactWithTwoDateColumns() {
        DbTable fact = new DbTable(
                "public",
                "orders",
                Arrays.asList(
                        pk("id"),
                        col("order_date", JDBCType.TIMESTAMP),
                        col("ship_date", JDBCType.TIMESTAMP),
                        col("amount", JDBCType.DOUBLE),
                        col("customer_id", JDBCType.INTEGER)),
                Collections.singletonList(new DbForeignKey("customer_id", "customers", "id")),
                1000L);

        DraftDimension shared = new TimeDimensionBuilder().buildSharedTimeDimension();
        List<DraftDimension> usages = new TimeDimensionBuilder().buildCubeUsages(fact, shared);

        assertEquals(2, usages.size());

        DraftDimension orderUsage = usages.get(0);
        assertEquals("order_date", orderUsage.name());
        assertEquals(DraftDimension.Type.TIME, orderUsage.type());
        assertEquals("order_date", orderUsage.foreignKey());
        assertEquals("Time", orderUsage.sourceTable());
        assertTrue(orderUsage.hierarchies().isEmpty());
        assertNotNull(orderUsage.provenance());
        assertEquals("rule:time-usage", orderUsage.provenance().ruleId());

        DraftDimension shipUsage = usages.get(1);
        assertEquals("ship_date", shipUsage.name());
        assertEquals("ship_date", shipUsage.foreignKey());
        assertEquals("Time", shipUsage.sourceTable());
    }

    @Test
    public void buildCubeUsagesFactWithNoDateColumnsYieldsEmpty() {
        DbTable fact = new DbTable(
                "public",
                "orders",
                Arrays.asList(pk("id"), col("amount", JDBCType.DOUBLE), col("customer_id", JDBCType.INTEGER)),
                Collections.<DbForeignKey>emptyList(),
                100L);
        DraftDimension shared = new TimeDimensionBuilder().buildSharedTimeDimension();
        List<DraftDimension> usages = new TimeDimensionBuilder().buildCubeUsages(fact, shared);
        assertTrue(usages.isEmpty());
    }

    @Test
    public void timeOnlyColumnIsNotTreatedAsDate() {
        DbTable fact = new DbTable(
                "public",
                "events",
                Arrays.asList(pk("id"), col("event_time", JDBCType.TIME), col("amount", JDBCType.DOUBLE)),
                Collections.<DbForeignKey>emptyList(),
                10L);
        DraftDimension shared = new TimeDimensionBuilder().buildSharedTimeDimension();
        List<DraftDimension> usages = new TimeDimensionBuilder().buildCubeUsages(fact, shared);
        assertTrue(usages.isEmpty());
    }

    @Test
    public void timestampWithTimezoneIsTreatedAsDate() {
        DbTable fact = new DbTable(
                "public",
                "audits",
                Arrays.asList(pk("id"), col("at", JDBCType.TIMESTAMP_WITH_TIMEZONE), col("amount", JDBCType.DOUBLE)),
                Collections.<DbForeignKey>emptyList(),
                10L);
        DraftDimension shared = new TimeDimensionBuilder().buildSharedTimeDimension();
        List<DraftDimension> usages = new TimeDimensionBuilder().buildCubeUsages(fact, shared);
        assertEquals(1, usages.size());
        assertEquals("at", usages.get(0).name());
    }
}
