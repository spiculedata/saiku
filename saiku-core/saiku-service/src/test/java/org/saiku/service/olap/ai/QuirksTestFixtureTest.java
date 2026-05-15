/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Smoke test for {@link QuirksTestFixture}. Verifies the fixture loads
 * end-to-end through {@link OlapAiCubeMetadataService} and the produced
 * {@link AiSchema} contains every documented quirk shape. Snapshot tests
 * (Phase 3.B) and property-based tests (Phase 3.C) build on this fixture,
 * so a quick sanity check here catches misconfigurations early.
 */
public class QuirksTestFixtureTest {

    @Test
    public void fixtureLoadsThroughMetadataService() {
        OlapAiCubeMetadataService svc = new OlapAiCubeMetadataService();
        svc.setDiscoverService(QuirksTestFixture.discover());

        AiSchema schema = svc.getSchema(QuirksTestFixture.cubeRef());

        assertNotNull(schema);
        assertEquals(QuirksTestFixture.CUBE, schema.getCubeName());

        // canonicalCube set by the saiku#811 fix path.
        assertNotNull("canonicalCube must be populated by the service", schema.canonicalCube);
        assertEquals(QuirksTestFixture.CONNECTION, schema.canonicalCube.getConnectionName());

        // Measures
        assertTrue(schema.measures.containsKey(AiSchema.key("Unit Sales")));
        assertTrue(schema.measures.containsKey(AiSchema.key("Number of Employees")));

        // Dimensions — Measures is filtered out by the service.
        assertTrue(schema.dimensions.containsKey(AiSchema.key("Time")));
        assertTrue(schema.dimensions.containsKey(AiSchema.key("Customer")));
        assertTrue(schema.dimensions.containsKey(AiSchema.key("Employee")));
        assertTrue(schema.dimensions.containsKey(AiSchema.key("Store2")));
        assertEquals("Measures dim filtered out of dim list", 4, schema.dimensions.size());

        // hasAll=false hier — saiku#807 trigger.
        AiSchema.Hierarchy timeHier =
                schema.dimensions.get(AiSchema.key("Time")).hierarchies.get(AiSchema.key("Time"));
        assertNotNull(timeHier);
        assertEquals("Time/Time has 2 levels (no (All))", 2, timeHier.levels.size());
        assertTrue(timeHier.levels.containsKey(AiSchema.key("Year")));
        assertTrue(timeHier.levels.containsKey(AiSchema.key("Quarter")));
    }

    @Test
    public void customerDimHasThreeHiers() {
        OlapAiCubeMetadataService svc = new OlapAiCubeMetadataService();
        svc.setDiscoverService(QuirksTestFixture.discover());
        AiSchema schema = svc.getSchema(QuirksTestFixture.cubeRef());

        AiSchema.Dimension cust = schema.dimensions.get(AiSchema.key("Customer"));
        assertNotNull(cust);
        assertEquals(3, cust.hierarchies.size());
        assertTrue(cust.hierarchies.containsKey(AiSchema.key("Customers")));
        assertTrue(cust.hierarchies.containsKey(AiSchema.key("Gender")));
        assertTrue(cust.hierarchies.containsKey(AiSchema.key("Marital Status")));
    }

    @Test
    public void employeeDimExposesClosureHier() {
        OlapAiCubeMetadataService svc = new OlapAiCubeMetadataService();
        svc.setDiscoverService(QuirksTestFixture.discover());
        AiSchema schema = svc.getSchema(QuirksTestFixture.cubeRef());

        AiSchema.Dimension emp = schema.dimensions.get(AiSchema.key("Employee"));
        assertNotNull(emp);
        // Salary (numeric-keyed) + Employee$Manager Id$Parent (closure).
        assertTrue(emp.hierarchies.containsKey(AiSchema.key("Salary")));
        assertTrue(
                "saiku#810 closure hier listed in schema (the bug being demonstrated)",
                emp.hierarchies.containsKey(AiSchema.key("Employee$Manager Id$Parent")));
    }

    @Test
    public void directSchemaPathSkipsServiceButPreservesShape() {
        OlapAiCubeMetadataService svc = new OlapAiCubeMetadataService();
        svc.setDiscoverService(QuirksTestFixture.discover());
        AiSchema viaService = svc.getSchema(QuirksTestFixture.cubeRef());
        AiSchema direct = QuirksTestFixture.directSchema();

        assertEquals(viaService.dimensions.keySet(), direct.dimensions.keySet());
        assertEquals(viaService.measures.keySet(), direct.measures.keySet());
        assertEquals(
                "canonicalCube cubeName matches across both paths",
                viaService.canonicalCube.getCubeName(),
                direct.canonicalCube.getCubeName());
    }

    @Test
    public void probeWithDefaultStubKeepsEveryLevel() {
        // The default stub returns empty for getLevelMembers — saiku#810
        // probe treats that as "queryable" (no exception thrown), so
        // every level survives.
        OlapAiCubeMetadataService svc = new OlapAiCubeMetadataService();
        svc.setDiscoverService(QuirksTestFixture.discover());
        svc.setProbeUnqueryable(true);

        AiSchema schema = svc.getSchema(QuirksTestFixture.cubeRef());

        assertEquals(4, schema.dimensions.size());

        AiSchema.Hierarchy salary =
                schema.dimensions.get(AiSchema.key("Employee")).hierarchies.get(AiSchema.key("Salary"));
        assertNotNull(salary);
        assertTrue(salary.levels.containsKey(AiSchema.key("Salary")));
    }
}
