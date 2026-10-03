/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * saiku#1434 — the diff engine. Rename pairing is the load-bearing behaviour here: without it a
 * deliberate rename is indistinguishable from a removal plus an addition, and every dashboard
 * using the old name is reported as broken with no way for the reviewer to tell it was
 * intentional.
 */
class ModelDiffEngineTest {

    private final ModelDiffService service = new ModelDiffService();

    private static final String BEFORE = "<Schema name='FoodMart'><Cube name='Sales'>"
            + "<Measure name='Unit Sales' column='unit_sales' aggregator='sum'/>"
            + "<Measure name='Store Cost' column='store_cost' aggregator='sum'/>"
            + "<Measure name='Store Sales' column='store_sales' aggregator='sum'/>"
            + "<Dimension name='Product'><Hierarchy><Level name='Product Family' column='product_family'/>"
            + "<Level name='Product Name' column='product_name'/></Hierarchy></Dimension>"
            + "</Cube></Schema>";

    private List<ModelChange> diff(String after) {
        return service.diffStrings(BEFORE, after).changes();
    }

    @Test
    void noChangesProducesNoDiff() {
        assertTrue(diff(BEFORE).isEmpty());
    }

    @Test
    void detectsAnAddedMeasure() {
        List<ModelChange> changes =
                diff(BEFORE.replace("</Cube>", "<Measure name='Gross Margin' column='gm'/></Cube>"));
        assertEquals(1, changes.size());
        ModelChange change = changes.get(0);
        assertEquals(ModelChange.Type.ADDED, change.type());
        assertEquals("Gross Margin", change.toName());
        assertFalse(change.breaking(), "an addition cannot orphan a reference");
    }

    @Test
    void detectsARemovedMeasureAsBreaking() {
        List<ModelChange> changes =
                diff(BEFORE.replace("<Measure name='Store Cost' column='store_cost' aggregator='sum'/>", ""));
        assertEquals(1, changes.size());
        assertEquals(ModelChange.Type.REMOVED, changes.get(0).type());
        assertEquals("Store Cost", changes.get(0).fromName());
        assertTrue(changes.get(0).breaking());
    }

    @Test
    void detectsARenameAsOneBreakingChange() {
        List<ModelChange> changes = diff(BEFORE.replace("name='Store Cost'", "name='Cost of Goods'"));
        assertEquals(1, changes.size(), "a rename must not be reported as a remove plus an add");
        ModelChange change = changes.get(0);
        assertEquals(ModelChange.Type.RENAMED, change.type());
        assertEquals("Store Cost", change.fromName());
        assertEquals("Cost of Goods", change.toName());
        assertTrue(change.breaking(), "dashboards referencing the old name break");
    }

    @Test
    void detectsARenamedLevel() {
        List<ModelChange> changes = diff(BEFORE.replace("name='Product Family'", "name='Product Line'"));
        assertEquals(1, changes.size());
        assertEquals(ModelChange.Type.RENAMED, changes.get(0).type());
        assertEquals("Product Family", changes.get(0).fromName());
        assertEquals("Product Line", changes.get(0).toName());
    }

    @Test
    void detectsARenamedCube() {
        String after = BEFORE.replace("name='Sales'", "name='Sales Cube'");
        assertTrue(diff(after).stream().anyMatch(c -> c.type() == ModelChange.Type.RENAMED));
    }

    @Test
    void reportsDefinitionChurnAsNonBreaking() {
        // A measure whose aggregator changed keeps its name and every reference resolves.
        String churn = BEFORE.replace(
                "<Measure name='Store Sales' column='store_sales' aggregator='sum'/>",
                "<Measure name='Store Sales' column='store_sales' aggregator='avg'/>");
        List<ModelChange> modified = diff(churn);
        assertEquals(1, modified.size());
        assertEquals(ModelChange.Type.MODIFIED, modified.get(0).type());
        assertFalse(modified.get(0).breaking());
    }

    @Test
    void aRemovalAndAnUnrelatedAdditionAreNotPairedAsARename() {
        // Different column ⇒ different signature ⇒ not the same element renamed. Pairing these
        // would hide a real removal behind a rename and lose the reviewer's warning.
        String after = BEFORE.replace("<Measure name='Store Cost' column='store_cost' aggregator='sum'/>", "")
                .replace(
                        "</Cube>",
                        "<Measure name='Nonsense' column='totally_different' aggregator='min'/>" + "</Cube>");
        List<ModelChange> changes = diff(after);
        assertTrue(changes.stream()
                .anyMatch(c -> c.type() == ModelChange.Type.REMOVED && "Store Cost".equals(c.fromName())));
        assertTrue(changes.stream().anyMatch(c -> c.type() == ModelChange.Type.ADDED && "Nonsense".equals(c.toName())));
    }

    @Test
    void crossFormatDiffIsRejected() {
        String yaml = "semantic_model:\n- name: TPCDS\n  metrics:\n  - name: total_sales\n";
        ModelDiffException e = assertThrows(ModelDiffException.class, () -> service.diffStrings(BEFORE, yaml));
        assertEquals(ModelDiffException.Reason.CROSS_FORMAT, e.reason());
    }

    @Test
    void changeOrderIsStable() {
        String after = BEFORE.replace("name='Store Cost'", "name='Cost'")
                .replace("</Cube>", "<Measure name='Gross Margin' column='gm'/></Cube>");
        List<ModelChange> first = diff(after);
        List<ModelChange> second = diff(after);
        assertEquals(
                first.stream().map(c -> c.type() + ":" + c.path()).toList(),
                second.stream().map(c -> c.type() + ":" + c.path()).toList());
    }

    @Test
    void similarityHelperIsSymmetricAndBounded() {
        assertEquals(1.0d, ModelDiffEngine.similarity("column=a;aggregator=sum", "aggregator=sum;column=a"), 1e-9);
        assertEquals(0.0d, ModelDiffEngine.similarity("column=a", "column=b"), 1e-9);
        assertEquals(1.0d, ModelDiffEngine.similarity("", ""), 1e-9);
    }
}
