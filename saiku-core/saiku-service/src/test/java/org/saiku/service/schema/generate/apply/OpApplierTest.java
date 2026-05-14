package org.saiku.service.schema.generate.apply;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.schema.generate.draft.DraftCube;
import org.saiku.service.schema.generate.draft.DraftDimension;
import org.saiku.service.schema.generate.draft.DraftHierarchy;
import org.saiku.service.schema.generate.draft.DraftLevel;
import org.saiku.service.schema.generate.draft.DraftMeasure;
import org.saiku.service.schema.generate.draft.DraftSchema;
import org.saiku.service.schema.generate.draft.Provenance;
import org.saiku.service.schema.generate.enrich.ops.AggregatorOp;
import org.saiku.service.schema.generate.enrich.ops.DegenerateDimOp;
import org.saiku.service.schema.generate.enrich.ops.HierarchyOp;
import org.saiku.service.schema.generate.enrich.ops.IgnoreOp;
import org.saiku.service.schema.generate.enrich.ops.RenameOp;

public class OpApplierTest {

    private DraftSchema schema;
    private DraftCube sales;
    private DraftDimension customer;
    private DraftMeasure amount;

    @Before
    public void setUp() {
        Provenance rule = new Provenance(Provenance.Source.RULE, "rule:test", 0.8);
        schema = new DraftSchema("Main");

        sales = new DraftCube("Sales", "fact_sales", rule);

        customer = new DraftDimension("customer", DraftDimension.Type.STANDARD, rule);
        customer.setSourceTable("dim_customer");
        customer.setForeignKey("customer_id");
        DraftHierarchy custHier = new DraftHierarchy("customer", "id", rule);
        custHier.levels().add(new DraftLevel("name", "name", DraftLevel.Type.REGULAR, rule));
        customer.hierarchies().add(custHier);
        sales.dimensions().add(customer);

        amount = new DraftMeasure("amount", "amount_col", DraftMeasure.Aggregator.SUM, rule);
        sales.measures().add(amount);
        DraftMeasure qty = new DraftMeasure("qty", "qty_col", DraftMeasure.Aggregator.SUM, rule);
        sales.measures().add(qty);

        schema.cubes().add(sales);

        DraftDimension time = new DraftDimension("Time", DraftDimension.Type.TIME, rule);
        schema.sharedDimensions().add(time);
    }

    @Test
    public void renameMeasureChangesOnlyCaptionAndProvenance() {
        RenameOp op =
                new RenameOp("cubes/fact_sales/measures/amount_col", "amount", "Amount", null, 0.9, "caption casing");
        new OpApplier().apply(schema, op);

        DraftMeasure m = sales.measures().get(0);
        assertEquals("Amount", m.name());
        assertEquals("amount_col", m.column());
        assertEquals(DraftMeasure.Aggregator.SUM, m.aggregator());
        assertEquals(Provenance.Source.USER, m.provenance().source());
    }

    /**
     * Regression test: after a RenameOp changes the user-visible name of a measure, a subsequent
     * op targeting the SAME stable-id path (column name) must still resolve. Previously, path
     * resolution was by mutable name, so the second op would fail with "No element at path ...".
     */
    @Test
    public void aggregatorOpAfterRenameStillResolvesByStableId() {
        RenameOp rename = new RenameOp(
                "cubes/fact_sales/measures/amount_col", "amount", "Total Sales", null, 0.9, "caption casing");
        AggregatorOp agg = new AggregatorOp(
                "cubes/fact_sales/measures/amount_col",
                DraftMeasure.Aggregator.SUM,
                DraftMeasure.Aggregator.AVG,
                0.8,
                "avg is better");
        OpApplier applier = new OpApplier();
        applier.apply(schema, rename);
        applier.apply(schema, agg);

        DraftMeasure m = sales.measures().get(0);
        assertEquals("Total Sales", m.name());
        assertEquals("amount_col", m.column());
        assertEquals(DraftMeasure.Aggregator.AVG, m.aggregator());
    }

    /**
     * Regression test: after a RenameOp on a dim, a subsequent RenameOp on one of its levels
     * still resolves via stable-id segments.
     */
    @Test
    public void levelRenameAfterDimRenameStillResolves() {
        RenameOp dimRename =
                new RenameOp("cubes/fact_sales/dimensions/dim_customer", "customer", "Customer", null, 0.9, "caption");
        RenameOp levelRename = new RenameOp(
                "cubes/fact_sales/dimensions/dim_customer/hierarchies/id/levels/name",
                "name",
                "Full Name",
                null,
                0.9,
                "caption");
        OpApplier applier = new OpApplier();
        applier.apply(schema, dimRename);
        applier.apply(schema, levelRename);

        assertEquals("Customer", customer.name());
        DraftLevel lvl = customer.hierarchies().get(0).levels().get(0);
        assertEquals("Full Name", lvl.name());
        assertEquals("name", lvl.column());
    }

    @Test
    public void aggregatorOpChangesAggregatorAndProvenance() {
        AggregatorOp op = new AggregatorOp(
                "cubes/fact_sales/measures/amount_col",
                DraftMeasure.Aggregator.SUM,
                DraftMeasure.Aggregator.AVG,
                0.8,
                "avg is better");
        new OpApplier().apply(schema, op);

        DraftMeasure m = sales.measures().get(0);
        assertEquals(DraftMeasure.Aggregator.AVG, m.aggregator());
        assertEquals("amount_col", m.column());
        assertEquals(Provenance.Source.USER, m.provenance().source());
    }

    @Test
    public void ignoreMeasureRemovesIt() {
        IgnoreOp op = new IgnoreOp("cubes/fact_sales/measures/amount_col", 0.9, "not needed");
        new OpApplier().apply(schema, op);

        assertEquals(1, sales.measures().size());
        assertEquals("qty", sales.measures().get(0).name());
    }

    @Test
    public void ignoreCubeRemovesIt() {
        IgnoreOp op = new IgnoreOp("cubes/fact_sales", 0.9, "drop");
        new OpApplier().apply(schema, op);

        assertTrue(schema.cubes().isEmpty());
    }

    @Test
    public void renameDimChangesNameNotHierarchies() {
        RenameOp op = new RenameOp(
                "cubes/fact_sales/dimensions/dim_customer", "customer", "Customer", null, 0.9, "caption casing");
        new OpApplier().apply(schema, op);

        assertEquals("Customer", customer.name());
        assertEquals(1, customer.hierarchies().size());
        assertEquals("customer", customer.hierarchies().get(0).name());
        assertEquals(Provenance.Source.USER, customer.provenance().source());
    }

    @Test
    public void unknownPathThrowsHelpful() {
        IgnoreOp op = new IgnoreOp("cubes/fact_sales/measures/unknown_col", 1.0, "x");
        try {
            new OpApplier().apply(schema, op);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            String msg = expected.getMessage();
            assertNotNull(msg);
            assertTrue("message should mention path: " + msg, msg.contains("cubes/fact_sales/measures/unknown_col"));
            assertTrue("message should list known children: " + msg, msg.contains("amount_col"));
            assertTrue("message should list known children: " + msg, msg.contains("qty_col"));
        }
    }

    @Test
    public void hierarchyOpReplacesDimHierarchies() {
        HierarchyOp op = new HierarchyOp(
                "cubes/fact_sales/dimensions/dim_customer",
                "Geography",
                List.of("country", "city"),
                0.9,
                "country then city");
        new OpApplier().apply(schema, op);

        assertEquals(1, customer.hierarchies().size());
        DraftHierarchy h = customer.hierarchies().get(0);
        assertEquals("Geography", h.name());
        assertEquals(2, h.levels().size());
        assertEquals("country", h.levels().get(0).name());
        assertEquals("country", h.levels().get(0).column());
        assertEquals(DraftLevel.Type.REGULAR, h.levels().get(0).type());
        assertEquals("city", h.levels().get(1).name());
        assertEquals(Provenance.Source.USER, h.provenance().source());
    }

    @Test
    public void degenerateDimAddsDimToCube() {
        int before = sales.dimensions().size();
        DegenerateDimOp op = new DegenerateDimOp("cubes/fact_sales", "order_status", "OrderStatus", 0.85, "degenerate");
        new OpApplier().apply(schema, op);

        assertEquals(before + 1, sales.dimensions().size());
        DraftDimension d = sales.dimensions().get(sales.dimensions().size() - 1);
        assertEquals("OrderStatus", d.name());
        assertEquals("fact_sales", d.sourceTable());
        assertEquals(Provenance.Source.USER, d.provenance().source());
        assertEquals(1, d.hierarchies().size());
        DraftHierarchy h = d.hierarchies().get(0);
        assertEquals(1, h.levels().size());
        DraftLevel lvl = h.levels().get(0);
        assertEquals("order_status", lvl.column());
        assertEquals(Provenance.Source.USER, lvl.provenance().source());
    }
}
