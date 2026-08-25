/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.generate.draft;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

public class DraftSchemaTest {

    @Test
    public void buildsCubeWithDimensionHierarchyLevelAndMeasureAndProvenanceEverywhere() {
        Provenance ruleProv = new Provenance(Provenance.Source.RULE, "rule:test", 0.9);

        DraftSchema schema = new DraftSchema("Sales");

        DraftCube cube = new DraftCube("Orders", "orders", ruleProv);
        schema.cubes().add(cube);

        DraftDimension dim = new DraftDimension("Customer", DraftDimension.Type.STANDARD, ruleProv);
        dim.setSourceTable("customers");
        cube.dimensions().add(dim);

        DraftHierarchy hier = new DraftHierarchy("Customer", "id", ruleProv);
        dim.hierarchies().add(hier);

        DraftLevel level = new DraftLevel("Name", "name", DraftLevel.Type.REGULAR, ruleProv);
        hier.levels().add(level);

        DraftMeasure measure = new DraftMeasure("Total", "amount", DraftMeasure.Aggregator.SUM, ruleProv);
        cube.measures().add(measure);

        // Navigation
        assertEquals("Sales", schema.name());
        assertEquals(1, schema.cubes().size());
        assertSame(cube, schema.cubes().get(0));
        assertEquals("orders", cube.sourceFactTable());
        assertEquals(1, cube.dimensions().size());
        assertSame(dim, cube.dimensions().get(0));
        assertEquals(DraftDimension.Type.STANDARD, dim.type());
        assertEquals("customers", dim.sourceTable());
        assertEquals(1, dim.hierarchies().size());
        assertSame(hier, dim.hierarchies().get(0));
        assertEquals("id", hier.primaryKey());
        assertNull(hier.join());
        assertEquals(1, hier.levels().size());
        assertSame(level, hier.levels().get(0));
        assertEquals(DraftLevel.Type.REGULAR, level.type());
        assertEquals(1, cube.measures().size());
        assertSame(measure, cube.measures().get(0));
        assertEquals(DraftMeasure.Aggregator.SUM, measure.aggregator());

        // Provenance present on every element
        assertNotNull(cube.provenance());
        assertNotNull(dim.provenance());
        assertNotNull(hier.provenance());
        assertNotNull(level.provenance());
        assertNotNull(measure.provenance());
    }

    @Test
    public void provenanceSourceMustNotBeNull() {
        try {
            new Provenance(null, "r", 1.0);
            org.junit.Assert.fail("expected NPE");
        } catch (NullPointerException expected) {
            // ok
        }
    }

    @Test
    public void provenanceRuleIdMustNotBeNull() {
        try {
            new Provenance(Provenance.Source.RULE, null, 1.0);
            org.junit.Assert.fail("expected NPE");
        } catch (NullPointerException expected) {
            // ok
        }
    }

    @Test
    public void mutationSupportedForOpReplay() {
        Provenance rule = new Provenance(Provenance.Source.RULE, "r1", 0.5);
        Provenance user = new Provenance(Provenance.Source.USER, "u1", 1.0);

        DraftMeasure m = new DraftMeasure("Total", "amount", DraftMeasure.Aggregator.SUM, rule);
        m.setAggregator(DraftMeasure.Aggregator.AVG);
        m.setProvenance(user);
        m.setName("Average Total");

        assertEquals(DraftMeasure.Aggregator.AVG, m.aggregator());
        assertEquals("Average Total", m.name());
        assertEquals(Provenance.Source.USER, m.provenance().source());
    }

    @Test
    public void draftJoinHoldsOneHopSnowflake() {
        DraftJoin j = new DraftJoin("products", "category_id", "categories", "id");
        assertEquals("products", j.leftTable());
        assertEquals("category_id", j.leftKey());
        assertEquals("categories", j.rightTable());
        assertEquals("id", j.rightKey());
    }
}
