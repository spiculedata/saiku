/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.generate.enrich.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.saiku.service.schema.generate.draft.DraftCube;
import org.saiku.service.schema.generate.draft.DraftDimension;
import org.saiku.service.schema.generate.draft.DraftHierarchy;
import org.saiku.service.schema.generate.draft.DraftLevel;
import org.saiku.service.schema.generate.draft.DraftMeasure;
import org.saiku.service.schema.generate.draft.DraftSchema;
import org.saiku.service.schema.generate.draft.Provenance;
import org.saiku.service.schema.generate.enrich.SuggestionSet;
import org.saiku.service.schema.generate.enrich.ops.AggregatorOp;
import org.saiku.service.schema.generate.enrich.ops.HierarchyOp;
import org.saiku.service.schema.generate.enrich.ops.RenameOp;
import org.saiku.service.schema.generate.enrich.ops.SuggestionOp;

public class NoopProviderTest {

    private static final Provenance RULE_PROV = new Provenance(Provenance.Source.RULE, "rule:test", 0.9);

    private static RenameOp firstRenameFor(SuggestionSet set, String targetPath) {
        for (SuggestionOp op : set.ops()) {
            if (op instanceof RenameOp r && targetPath.equals(r.targetPath())) {
                return r;
            }
        }
        return null;
    }

    @Test
    public void renamesSnakeCaseDimAndLowercaseCubeToTitleCase() {
        DraftSchema schema = new DraftSchema("Sales");
        DraftCube cube = new DraftCube("sales", "orders", RULE_PROV);
        schema.cubes().add(cube);

        DraftDimension dim = new DraftDimension("OrderDate", DraftDimension.Type.STANDARD, RULE_PROV);
        // Give it a hierarchy+level so the rename at dimension level is the clear target.
        dim.hierarchies().add(new DraftHierarchy("OrderDate", "id", RULE_PROV));
        cube.dimensions().add(dim);

        DraftDimension snakeDim = new DraftDimension("order_date", DraftDimension.Type.STANDARD, RULE_PROV);
        cube.dimensions().add(snakeDim);

        EnrichRequest req = new EnrichRequest(schema, Map.of(), 50);
        EnrichResponse resp = new NoopProvider().enrich(req);

        assertNotNull(resp);
        SuggestionSet set = resp.suggestions();
        assertNotNull(set);
        assertFalse(set.degraded());

        // Stable-id path: cube segment is sourceFactTable ("orders"), not the cube name ("sales").
        // Dim segments fall back to name() because these dims have no sourceTable set.
        RenameOp cubeRename = firstRenameFor(set, "cubes/orders");
        assertNotNull("expected rename op for cubes/orders", cubeRename);
        assertEquals("Sales", cubeRename.newCaption());

        RenameOp orderDateRename = firstRenameFor(set, "cubes/orders/dimensions/OrderDate");
        assertNotNull("expected rename op for OrderDate dim (camelCase)", orderDateRename);
        assertEquals("Order Date", orderDateRename.newCaption());

        RenameOp snakeRename = firstRenameFor(set, "cubes/orders/dimensions/order_date");
        assertNotNull("expected rename op for order_date dim (snake_case)", snakeRename);
        assertEquals("Order Date", snakeRename.newCaption());
    }

    @Test
    public void geoColumnsProduceHierarchyOpOnDimension() {
        DraftSchema schema = new DraftSchema("Sales");
        DraftCube cube = new DraftCube("Sales", "orders", RULE_PROV);
        schema.cubes().add(cube);

        DraftDimension customer = new DraftDimension("customer", DraftDimension.Type.STANDARD, RULE_PROV);
        DraftHierarchy hier = new DraftHierarchy("customer", "id", RULE_PROV);
        hier.levels().add(new DraftLevel("Name", "name", DraftLevel.Type.REGULAR, RULE_PROV));
        hier.levels().add(new DraftLevel("Country", "country_code", DraftLevel.Type.REGULAR, RULE_PROV));
        hier.levels().add(new DraftLevel("City", "city", DraftLevel.Type.REGULAR, RULE_PROV));
        customer.hierarchies().add(hier);
        cube.dimensions().add(customer);

        EnrichResponse resp = new NoopProvider().enrich(new EnrichRequest(schema, Map.of(), 50));

        boolean found = false;
        for (SuggestionOp op : resp.suggestions().ops()) {
            if (op instanceof HierarchyOp h
                    && "cubes/orders/dimensions/customer".equals(h.targetPath())
                    && h.levelColumns().contains("country_code")) {
                assertTrue(h.levelColumns().contains("city"));
                found = true;
                break;
            }
        }
        assertTrue("expected a geography HierarchyOp covering country_code", found);
    }

    @Test
    public void alreadyTitleCasedCubeEmitsNoRenameOp() {
        DraftSchema schema = new DraftSchema("Sales");
        DraftCube cube = new DraftCube("Sales", "orders", RULE_PROV);
        schema.cubes().add(cube);

        EnrichResponse resp = new NoopProvider().enrich(new EnrichRequest(schema, Map.of(), 50));

        for (SuggestionOp op : resp.suggestions().ops()) {
            if (op instanceof RenameOp r && "cubes/orders".equals(r.targetPath())) {
                throw new AssertionError("unexpected rename for already-titled cube: " + r.newCaption());
            }
        }
    }

    @Test
    public void avgPrefixedMeasureEmitsAggregatorOpAvg() {
        DraftSchema schema = new DraftSchema("Sales");
        DraftCube cube = new DraftCube("Sales", "orders", RULE_PROV);
        schema.cubes().add(cube);

        DraftMeasure m = new DraftMeasure("avg_price", "price", DraftMeasure.Aggregator.SUM, RULE_PROV);
        cube.measures().add(m);

        EnrichResponse resp = new NoopProvider().enrich(new EnrichRequest(schema, Map.of(), 50));

        AggregatorOp found = null;
        for (SuggestionOp op : resp.suggestions().ops()) {
            // Stable-id path: measure segment is column ("price"), not name ("avg_price").
            if (op instanceof AggregatorOp a && "cubes/orders/measures/price".equals(a.targetPath())) {
                found = a;
                break;
            }
        }
        assertNotNull("expected AggregatorOp on avg_price", found);
        assertEquals(DraftMeasure.Aggregator.AVG, found.newAggregator());
    }

    @Test
    public void opsAreOrderedRenamesThenHierarchiesThenAggregators() {
        DraftSchema schema = new DraftSchema("Sales");
        DraftCube cube = new DraftCube("sales", "orders", RULE_PROV);
        schema.cubes().add(cube);

        DraftDimension customer = new DraftDimension("customer", DraftDimension.Type.STANDARD, RULE_PROV);
        DraftHierarchy hier = new DraftHierarchy("customer", "id", RULE_PROV);
        hier.levels().add(new DraftLevel("Country", "country_code", DraftLevel.Type.REGULAR, RULE_PROV));
        hier.levels().add(new DraftLevel("City", "city", DraftLevel.Type.REGULAR, RULE_PROV));
        customer.hierarchies().add(hier);
        cube.dimensions().add(customer);

        cube.measures().add(new DraftMeasure("avg_price", "price", DraftMeasure.Aggregator.SUM, RULE_PROV));

        EnrichResponse resp = new NoopProvider().enrich(new EnrichRequest(schema, Map.of(), 50));
        List<SuggestionOp> ops = resp.suggestions().ops();

        int lastRename = -1;
        int firstHier = Integer.MAX_VALUE;
        int lastHier = -1;
        int firstAgg = Integer.MAX_VALUE;
        for (int i = 0; i < ops.size(); i++) {
            SuggestionOp op = ops.get(i);
            if (op instanceof RenameOp) {
                lastRename = i;
            } else if (op instanceof HierarchyOp) {
                firstHier = Math.min(firstHier, i);
                lastHier = i;
            } else if (op instanceof AggregatorOp) {
                firstAgg = Math.min(firstAgg, i);
            }
        }
        if (lastRename >= 0 && firstHier != Integer.MAX_VALUE) {
            assertTrue("renames before hierarchies", lastRename < firstHier);
        }
        if (lastHier >= 0 && firstAgg != Integer.MAX_VALUE) {
            assertTrue("hierarchies before aggregators", lastHier < firstAgg);
        }
    }
}
