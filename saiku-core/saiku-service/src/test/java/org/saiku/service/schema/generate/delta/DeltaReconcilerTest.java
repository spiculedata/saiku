package org.saiku.service.schema.generate.delta;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import org.junit.Test;
import org.saiku.service.schema.generate.draft.DraftCube;
import org.saiku.service.schema.generate.draft.DraftDimension;
import org.saiku.service.schema.generate.draft.DraftHierarchy;
import org.saiku.service.schema.generate.draft.DraftLevel;
import org.saiku.service.schema.generate.draft.DraftMeasure;
import org.saiku.service.schema.generate.draft.DraftSchema;
import org.saiku.service.schema.generate.draft.Provenance;

public class DeltaReconcilerTest {

    private static final Provenance RULE = new Provenance(Provenance.Source.RULE, "rule:test", 0.9);

    /** Build a cube "Sales" on fact_sales with one customer dim and measures {amount, qty}. */
    private static DraftSchema buildBaseline() {
        DraftSchema schema = new DraftSchema("Main");

        DraftCube cube = new DraftCube("Sales", "fact_sales", RULE);

        DraftDimension customer = new DraftDimension("Customer", DraftDimension.Type.STANDARD, RULE);
        customer.setSourceTable("dim_customer");
        customer.setForeignKey("customer_id");
        DraftHierarchy hier = new DraftHierarchy("Customer", "id", RULE);
        hier.levels().add(new DraftLevel("Name", "name", DraftLevel.Type.REGULAR, RULE));
        customer.hierarchies().add(hier);
        cube.dimensions().add(customer);

        cube.measures().add(new DraftMeasure("Amount", "amount", DraftMeasure.Aggregator.SUM, RULE));
        cube.measures().add(new DraftMeasure("Qty", "qty", DraftMeasure.Aggregator.SUM, RULE));

        schema.cubes().add(cube);
        return schema;
    }

    @Test
    public void noChange_allExisting() {
        DeltaReport report = new DeltaReconciler().reconcile(buildBaseline(), buildBaseline());

        assertTrue("no NEW elements expected", report.newPaths().isEmpty());
        assertTrue(
                "no REMOVED_UPSTREAM elements expected",
                report.removedUpstreamPaths().isEmpty());
        assertFalse("should have EXISTING tags", report.existingPaths().isEmpty());

        for (Map.Entry<String, DeltaTag> e : report.tags().entrySet()) {
            assertEquals("path " + e.getKey() + " should be EXISTING", DeltaTag.EXISTING, e.getValue());
        }
    }

    @Test
    public void addColumn_newMeasureTaggedNew() {
        DraftSchema baseline = buildBaseline();
        // drop qty from baseline so baseline has only amount
        baseline.cubes().get(0).measures().removeIf(m -> m.column().equals("qty"));

        DraftSchema current = buildBaseline(); // has amount + qty

        DeltaReport report = new DeltaReconciler().reconcile(baseline, current);

        assertEquals(DeltaTag.NEW, report.tags().get("cubes/fact_sales/measures/qty"));
        assertEquals(DeltaTag.EXISTING, report.tags().get("cubes/fact_sales/measures/amount"));
        assertTrue(report.removedUpstreamPaths().isEmpty());
    }

    @Test
    public void dropColumn_missingMeasureTaggedRemoved() {
        DraftSchema baseline = buildBaseline();
        DraftSchema current = buildBaseline();
        current.cubes().get(0).measures().removeIf(m -> m.column().equals("qty"));

        DeltaReport report = new DeltaReconciler().reconcile(baseline, current);

        assertEquals(DeltaTag.REMOVED_UPSTREAM, report.tags().get("cubes/fact_sales/measures/qty"));
        assertEquals(DeltaTag.EXISTING, report.tags().get("cubes/fact_sales/measures/amount"));
        assertTrue(report.newPaths().isEmpty());
    }

    @Test
    public void renameCaption_stableIdUnchanged_tagsExisting() {
        DraftSchema baseline = buildBaseline();
        DraftSchema current = buildBaseline();
        // rename the measure caption (display name) but keep the column the same
        current.cubes().get(0).measures().stream()
                .filter(m -> m.column().equals("amount"))
                .findFirst()
                .ifPresent(m -> m.setName("Total Amount"));

        DeltaReport report = new DeltaReconciler().reconcile(baseline, current);

        assertEquals(
                "rename of caption must not affect stable id",
                DeltaTag.EXISTING,
                report.tags().get("cubes/fact_sales/measures/amount"));
        assertTrue(report.newPaths().isEmpty());
        assertTrue(report.removedUpstreamPaths().isEmpty());
    }

    @Test
    public void addDimension_newDimTaggedNew() {
        DraftSchema baseline = buildBaseline();
        DraftSchema current = buildBaseline();

        DraftDimension product = new DraftDimension("Product", DraftDimension.Type.STANDARD, RULE);
        product.setSourceTable("dim_product");
        product.setForeignKey("product_id");
        DraftHierarchy ph = new DraftHierarchy("Product", "id", RULE);
        ph.levels().add(new DraftLevel("Name", "name", DraftLevel.Type.REGULAR, RULE));
        product.hierarchies().add(ph);
        current.cubes().get(0).dimensions().add(product);

        DeltaReport report = new DeltaReconciler().reconcile(baseline, current);

        assertEquals(DeltaTag.NEW, report.tags().get("cubes/fact_sales/dimensions/dim_product"));
        assertNotNull(
                "level under new dim should also be tagged",
                report.tags().get("cubes/fact_sales/dimensions/dim_product/hierarchies/id/levels/name"));
        assertEquals(
                DeltaTag.NEW, report.tags().get("cubes/fact_sales/dimensions/dim_product/hierarchies/id/levels/name"));
    }

    @Test
    public void dropDimension_missingDimTaggedRemoved() {
        DraftSchema baseline = buildBaseline();
        // add product to baseline
        DraftDimension product = new DraftDimension("Product", DraftDimension.Type.STANDARD, RULE);
        product.setSourceTable("dim_product");
        product.setForeignKey("product_id");
        DraftHierarchy ph = new DraftHierarchy("Product", "id", RULE);
        ph.levels().add(new DraftLevel("Name", "name", DraftLevel.Type.REGULAR, RULE));
        product.hierarchies().add(ph);
        baseline.cubes().get(0).dimensions().add(product);

        DraftSchema current = buildBaseline(); // only customer dim

        DeltaReport report = new DeltaReconciler().reconcile(baseline, current);

        assertEquals(DeltaTag.REMOVED_UPSTREAM, report.tags().get("cubes/fact_sales/dimensions/dim_product"));
        assertEquals(
                DeltaTag.REMOVED_UPSTREAM,
                report.tags().get("cubes/fact_sales/dimensions/dim_product/hierarchies/id/levels/name"));
        assertTrue(report.newPaths().isEmpty());
    }
}
