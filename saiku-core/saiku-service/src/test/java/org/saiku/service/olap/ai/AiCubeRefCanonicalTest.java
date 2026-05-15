/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;
import org.saiku.olap.dto.SaikuCube;
import org.saiku.olap.query2.ThinQuery;

/**
 * Pins the saiku#811 fix: when a schema carries a {@code canonicalCube}
 * populated by the metadata service, {@link AiSchemaConverter#convert}
 * builds a {@link SaikuCube} whose connectionName / catalog / schema /
 * cubeName all reflect the canonical (Mondrian-cased) values — not the
 * agent's input case. This is what stops the "validator says yes,
 * downstream native-cube loader says no" failure mode from producing
 * opaque 500s on case-mismatched cube refs.
 */
public class AiCubeRefCanonicalTest {

    /** Build a minimal valid schema for the converter to chew on. */
    private static AiSchema schemaWithCanonical(AiCubeRef canonical) {
        AiSchema s = new AiSchema(
                canonical.getConnectionName() + "/" + canonical.getCatalog() + "/" + canonical.getSchema() + "/"
                        + canonical.getCubeName(),
                canonical.getCubeName(),
                "[" + canonical.getConnectionName() + "].["
                        + canonical.getCatalog() + "].["
                        + canonical.getSchema() + "].["
                        + canonical.getCubeName() + "]");
        s.canonicalCube = canonical;

        AiSchema.Measure m = new AiSchema.Measure("Unit Sales", "[Measures].[Unit Sales]");
        s.measures.put(AiSchema.key("Unit Sales"), m);

        AiSchema.Dimension d = new AiSchema.Dimension("Product", "[Product]");
        AiSchema.Hierarchy h = new AiSchema.Hierarchy("Products", "[Product].[Products]");
        AiSchema.Level all = new AiSchema.Level("(All)", "[Product].[Products].[(All)]");
        AiSchema.Level l = new AiSchema.Level("Product Family", "[Product].[Products].[Product Family]");
        h.levels.put(AiSchema.key("(All)"), all);
        h.levels.put(AiSchema.key("Product Family"), l);
        d.hierarchies.put(AiSchema.key("Products"), h);
        s.dimensions.put(AiSchema.key("Product"), d);

        return s;
    }

    private static AiQueryRequest minimalRequest(AiCubeRef agentRef) {
        AiQueryRequest req = new AiQueryRequest();
        req.setCube(agentRef);
        AiMeasureSelection ms = new AiMeasureSelection();
        ms.setName("Unit Sales");
        req.getMeasures().add(ms);
        AiAxisSelection row = new AiAxisSelection();
        row.setDimension("Product");
        row.setHierarchy("Products");
        row.setLevel("Product Family");
        req.getRows().add(row);
        return req;
    }

    /**
     * Agent posts lowercase cubeName. The converter must produce a
     * SaikuCube whose name is the canonical "Sales", not the agent's
     * "sales" — otherwise the downstream native-cube lookup mismatches.
     */
    @Test
    public void canonicalCubeWinsOverAgentCase_cubeName() {
        AiCubeRef canonical = new AiCubeRef("unknown_foodmart", "FoodMart", "FoodMart", "Sales");
        AiCubeRef agentRef = new AiCubeRef("unknown_foodmart", "FoodMart", "FoodMart", "sales");

        AiSchema schema = schemaWithCanonical(canonical);
        AiQueryRequest req = minimalRequest(agentRef);

        ThinQuery tq = new AiSchemaConverter().convert(req, schema);
        SaikuCube cube = tq.getCube();

        assertNotNull(cube);
        assertEquals("Sales", cube.getName());
        assertEquals("unknown_foodmart", cube.getConnection());
        assertEquals("FoodMart", cube.getCatalog());
        assertEquals("FoodMart", cube.getSchema());
    }

    /**
     * Mixed-case connectionName must canonicalise too. This was the
     * harder leg of saiku#811 — pre-fix it NPE'd the connection lookup
     * because the lookup is fully case-sensitive. The schema-side fix
     * (canonicalCube wins) eliminates the bad input before it reaches
     * that lookup.
     */
    @Test
    public void canonicalCubeWinsOverAgentCase_connectionName() {
        AiCubeRef canonical = new AiCubeRef("unknown_foodmart", "FoodMart", "FoodMart", "Sales");
        AiCubeRef agentRef = new AiCubeRef("Unknown_Foodmart", "FoodMart", "FoodMart", "Sales");

        AiSchema schema = schemaWithCanonical(canonical);
        AiQueryRequest req = minimalRequest(agentRef);

        ThinQuery tq = new AiSchemaConverter().convert(req, schema);
        SaikuCube cube = tq.getCube();

        assertEquals("unknown_foodmart", cube.getConnection());
    }

    /**
     * When {@code canonicalCube} is NOT set (test fixture path, or any
     * AiSchema constructed without going through the metadata service),
     * the converter falls back to the agent-supplied ref so existing
     * unit-test scaffolding keeps working.
     */
    @Test
    public void fallsBackToAgentRefWhenCanonicalAbsent() {
        AiCubeRef agentRef = new AiCubeRef("conn", "cat", "sch", "MyCube");

        AiSchema schema = new AiSchema("conn/cat/sch/MyCube", "MyCube", "[conn].[cat].[sch].[MyCube]");
        AiSchema.Measure m = new AiSchema.Measure("Unit Sales", "[Measures].[Unit Sales]");
        schema.measures.put(AiSchema.key("Unit Sales"), m);
        AiSchema.Dimension d = new AiSchema.Dimension("Product", "[Product]");
        AiSchema.Hierarchy h = new AiSchema.Hierarchy("Products", "[Product].[Products]");
        AiSchema.Level all = new AiSchema.Level("(All)", "[Product].[Products].[(All)]");
        AiSchema.Level l = new AiSchema.Level("Product Family", "[Product].[Products].[Product Family]");
        h.levels.put(AiSchema.key("(All)"), all);
        h.levels.put(AiSchema.key("Product Family"), l);
        d.hierarchies.put(AiSchema.key("Products"), h);
        schema.dimensions.put(AiSchema.key("Product"), d);

        AiQueryRequest req = minimalRequest(agentRef);

        ThinQuery tq = new AiSchemaConverter().convert(req, schema);
        SaikuCube cube = tq.getCube();

        assertEquals("MyCube", cube.getName());
        assertEquals("conn", cube.getConnection());
    }
}
