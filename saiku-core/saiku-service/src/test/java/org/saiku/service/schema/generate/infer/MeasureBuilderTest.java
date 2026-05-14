package org.saiku.service.schema.generate.infer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.sql.JDBCType;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.saiku.service.schema.generate.draft.DraftMeasure;
import org.saiku.service.schema.generate.draft.Provenance;
import org.saiku.service.schema.generate.model.DbColumn;
import org.saiku.service.schema.generate.model.DbForeignKey;
import org.saiku.service.schema.generate.model.DbTable;

public class MeasureBuilderTest {

    private DbColumn pk(String name) {
        return new DbColumn(name, JDBCType.INTEGER, false, true);
    }

    private DbColumn col(String name, JDBCType type) {
        return new DbColumn(name, type, true, false);
    }

    @Test
    public void specExampleAmountWithFkAndDate() {
        DbTable fact = new DbTable(
                "public",
                "orders",
                Arrays.asList(
                        col("amount", JDBCType.DOUBLE),
                        col("customer_id", JDBCType.INTEGER),
                        col("order_date", JDBCType.DATE)),
                Collections.singletonList(new DbForeignKey("customer_id", "customers", "id")),
                1000L);

        List<DraftMeasure> measures = new MeasureBuilder().build(fact);

        assertEquals(2, measures.size());

        DraftMeasure amount = measures.get(0);
        assertEquals("amount", amount.name());
        assertEquals("amount", amount.column());
        assertEquals(DraftMeasure.Aggregator.SUM, amount.aggregator());
        assertNotNull(amount.provenance());
        assertEquals(Provenance.Source.RULE, amount.provenance().source());
        assertEquals("rule:measure-sum", amount.provenance().ruleId());
        assertEquals(1.0, amount.provenance().confidence(), 0.0);

        DraftMeasure factCount = measures.get(1);
        assertEquals("Fact Count", factCount.name());
        // Fact Count anchors on the fact PK when one exists; null when the fact has no PK.
        // The "orders" fixture here has no declared PK → column is null.
        assertNull(factCount.column());
        assertEquals(DraftMeasure.Aggregator.COUNT_STAR, factCount.aggregator());
        assertNotNull(factCount.provenance());
        assertEquals(Provenance.Source.RULE, factCount.provenance().source());
        assertEquals("rule:measure-fact-count", factCount.provenance().ruleId());
        assertEquals(1.0, factCount.provenance().confidence(), 0.0);
    }

    @Test
    public void factWithNoNumericColumnsYieldsOnlyFactCount() {
        DbTable fact = new DbTable(
                "public",
                "events",
                Arrays.asList(pk("id"), col("name", JDBCType.VARCHAR), col("occurred_at", JDBCType.TIMESTAMP)),
                Collections.<DbForeignKey>emptyList(),
                10L);

        List<DraftMeasure> measures = new MeasureBuilder().build(fact);

        assertEquals(1, measures.size());
        DraftMeasure m = measures.get(0);
        assertEquals("Fact Count", m.name());
        // Fact PK "id" becomes the anchor column for count — ensures Mondrian emits count(id)
        // instead of evaluating at the tuple level.
        assertEquals("id", m.column());
        assertEquals(DraftMeasure.Aggregator.COUNT_STAR, m.aggregator());
    }

    @Test
    public void skipsPksFksStringsAndDates() {
        DbTable fact = new DbTable(
                "public",
                "sales",
                Arrays.asList(
                        pk("id"), // PK - skip even though numeric
                        col("customer_id", JDBCType.INTEGER), // FK - skip
                        col("product_name", JDBCType.VARCHAR), // non-numeric - skip
                        col("sale_date", JDBCType.DATE), // date - skip
                        col("sale_ts", JDBCType.TIMESTAMP), // date - skip
                        col("sale_ts_tz", JDBCType.TIMESTAMP_WITH_TIMEZONE), // date - skip
                        col("sale_time", JDBCType.TIME), // date-ish - skip
                        col("revenue", JDBCType.DECIMAL)),
                Collections.singletonList(new DbForeignKey("customer_id", "customers", "id")),
                500L);

        List<DraftMeasure> measures = new MeasureBuilder().build(fact);

        assertEquals(2, measures.size());
        assertEquals("revenue", measures.get(0).name());
        assertEquals("revenue", measures.get(0).column());
        assertEquals(DraftMeasure.Aggregator.SUM, measures.get(0).aggregator());
        assertEquals("rule:measure-sum", measures.get(0).provenance().ruleId());

        assertEquals("Fact Count", measures.get(1).name());
        assertEquals(DraftMeasure.Aggregator.COUNT_STAR, measures.get(1).aggregator());
    }
}
