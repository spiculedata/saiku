package org.saiku.service.schema.generate.infer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
    public void factWithTwoDateColumnsYieldsTwoDegenerateDims() {
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

        List<DraftDimension> dims = new TimeDimensionBuilder().buildCubeTimeDimensions(fact);

        assertEquals(2, dims.size());

        DraftDimension orderDim = dims.get(0);
        assertEquals("order_date", orderDim.name());
        assertEquals(DraftDimension.Type.TIME, orderDim.type());
        // Degenerate dims colocate on the fact.
        assertEquals("orders", orderDim.sourceTable());
        assertNull("degenerate TIME dims must not carry a foreign key", orderDim.foreignKey());
        assertNotNull(orderDim.provenance());
        assertEquals("rule:time-degenerate", orderDim.provenance().ruleId());

        assertEquals(1, orderDim.hierarchies().size());
        DraftHierarchy h = orderDim.hierarchies().get(0);
        assertEquals(4, h.levels().size());

        assertLevel(h.levels().get(0), "Year", DraftLevel.Type.YEARS, "order_date", "YEAR(order_date)");
        assertLevel(h.levels().get(1), "Quarter", DraftLevel.Type.QUARTERS, "order_date", "QUARTER(order_date)");
        assertLevel(h.levels().get(2), "Month", DraftLevel.Type.MONTHS, "order_date", "MONTH(order_date)");
        assertLevel(h.levels().get(3), "Day", DraftLevel.Type.DAYS, "order_date", "DAY(order_date)");

        for (DraftLevel l : h.levels()) {
            assertEquals(Provenance.Source.RULE, l.provenance().source());
            assertEquals("rule:time-degenerate", l.provenance().ruleId());
        }

        // Second column → its own dim.
        DraftDimension shipDim = dims.get(1);
        assertEquals("ship_date", shipDim.name());
        assertEquals("orders", shipDim.sourceTable());
        assertEquals(
                "YEAR(ship_date)", shipDim.hierarchies().get(0).levels().get(0).expression());
    }

    @Test
    public void factWithNoDateColumnsYieldsEmpty() {
        DbTable fact = new DbTable(
                "public",
                "orders",
                Arrays.asList(pk("id"), col("amount", JDBCType.DOUBLE), col("customer_id", JDBCType.INTEGER)),
                Collections.<DbForeignKey>emptyList(),
                100L);
        assertTrue(new TimeDimensionBuilder().buildCubeTimeDimensions(fact).isEmpty());
    }

    @Test
    public void timeOnlyColumnIsNotTreatedAsDate() {
        DbTable fact = new DbTable(
                "public",
                "events",
                Arrays.asList(pk("id"), col("event_time", JDBCType.TIME), col("amount", JDBCType.DOUBLE)),
                Collections.<DbForeignKey>emptyList(),
                10L);
        assertTrue(new TimeDimensionBuilder().buildCubeTimeDimensions(fact).isEmpty());
    }

    @Test
    public void timestampWithTimezoneIsTreatedAsDate() {
        DbTable fact = new DbTable(
                "public",
                "audits",
                Arrays.asList(pk("id"), col("at", JDBCType.TIMESTAMP_WITH_TIMEZONE), col("amount", JDBCType.DOUBLE)),
                Collections.<DbForeignKey>emptyList(),
                10L);
        List<DraftDimension> dims = new TimeDimensionBuilder().buildCubeTimeDimensions(fact);
        assertEquals(1, dims.size());
        assertEquals("at", dims.get(0).name());
        assertEquals("audits", dims.get(0).sourceTable());
    }

    private static void assertLevel(DraftLevel l, String name, DraftLevel.Type type, String col, String expression) {
        assertEquals(name, l.name());
        assertEquals(type, l.type());
        assertEquals(col, l.column());
        assertEquals(expression, l.expression());
    }
}
