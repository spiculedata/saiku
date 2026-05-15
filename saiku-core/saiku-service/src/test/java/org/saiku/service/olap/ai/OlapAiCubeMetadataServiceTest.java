/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.saiku.olap.dto.SaikuCube;
import org.saiku.olap.dto.SaikuDimension;
import org.saiku.olap.dto.SaikuHierarchy;
import org.saiku.olap.dto.SaikuLevel;
import org.saiku.olap.dto.SaikuMember;
import org.saiku.olap.dto.SimpleCubeElement;
import org.saiku.service.olap.OlapDiscoverService;
import org.saiku.olap.util.exception.SaikuOlapException;
import org.saiku.service.util.exception.SaikuServiceException;

/**
 * Unit tests for {@link OlapAiCubeMetadataService}. Drives a stubbed
 * {@link OlapDiscoverService} so we don't need a live olap4j connection.
 */
public class OlapAiCubeMetadataServiceTest {

    private OlapAiCubeMetadataService svc;

    @Before
    public void setUp() {
        svc = new OlapAiCubeMetadataService();
        svc.setDiscoverService(new StubDiscover());
    }

    @Test
    public void listCubesReturnsAllRegisteredCubes() {
        List<AiCubeSummary> cubes = svc.listCubes();
        assertEquals(2, cubes.size());
        AiCubeSummary sales = findCube(cubes, "Sales");
        assertNotNull(sales);
        assertEquals("foodmart", sales.getConnectionName());
        assertEquals("FoodMart", sales.getCatalog());
        assertEquals("FoodMart", sales.getSchema());
        assertEquals("Sales", sales.getCubeCaption());
        assertEquals(2, sales.getMeasureCount());
        assertEquals("Store Sales", sales.getDefaultMeasure());
    }

    @Test
    public void getSchemaResolvesByName() {
        AiSchema schema = svc.getSchema(
                new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
        assertNotNull(schema);
        assertEquals("Sales", schema.getCubeName());
        // SaikuCube.getUniqueName() returns [conn].[cat].[schema].[name]
        assertEquals("[foodmart].[FoodMart].[FoodMart].[Sales]", schema.getCubeUniqueName());

        assertTrue("measures present", schema.measures.containsKey(AiSchema.key("Store Sales")));
        assertTrue("measures present", schema.measures.containsKey(AiSchema.key("Unit Sales")));

        AiSchema.Dimension time = schema.dimensions.get(AiSchema.key("Time"));
        assertNotNull(time);
        AiSchema.Hierarchy timeBy = time.hierarchies.get(AiSchema.key("Time By"));
        assertNotNull(timeBy);
        assertTrue(timeBy.levels.containsKey(AiSchema.key("Year")));
        assertTrue(timeBy.levels.containsKey(AiSchema.key("Quarter")));

        assertFalse("Measures dimension should NOT appear as a regular dim",
                schema.dimensions.containsKey(AiSchema.key("Measures")));
    }

    @Test
    public void getSchemaCachesPerCube() {
        AiCubeRef ref = new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales");
        AiSchema first = svc.getSchema(ref);
        AiSchema second = svc.getSchema(ref);
        assertTrue("cached instance reused", first == second);
    }

    @Test
    public void invalidateCacheForcesRebuild() {
        AiCubeRef ref = new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales");
        AiSchema first = svc.getSchema(ref);
        svc.invalidateCache();
        AiSchema second = svc.getSchema(ref);
        assertTrue("new instance after invalidate", first != second);
    }

    @Test
    public void unknownCubeThrowsValidationWithCandidates() {
        try {
            svc.getSchema(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Nonsense"));
            fail("expected AiValidationException");
        } catch (AiValidationException e) {
            assertEquals("cube", e.getField());
            assertTrue("candidate list includes Sales", e.getAvailable().contains("Sales"));
        }
    }

    @Test
    public void cubeMatchIsCaseInsensitive() {
        AiSchema schema = svc.getSchema(
                new AiCubeRef("FOODMART", "FOODMART", "FOODMART", "sALeS"));
        assertEquals("Sales", schema.getCubeName());
    }

    /* ----------------------------- helpers ---------------------------------- */

    private static AiCubeSummary findCube(List<AiCubeSummary> all, String name) {
        for (AiCubeSummary c : all) if (name.equals(c.getCubeName())) return c;
        return null;
    }

    /** Hand-rolled OlapDiscoverService that yields a tiny Foodmart-shaped schema. */
    private static class StubDiscover extends OlapDiscoverService {

        private SaikuCube sales() {
            return new SaikuCube("foodmart", "[FoodMart].[Sales]", "Sales", "Sales", "FoodMart", "FoodMart");
        }

        private SaikuCube hr() {
            return new SaikuCube("foodmart", "[FoodMart].[HR]", "HR", "HR", "FoodMart", "FoodMart");
        }

        @Override
        public List<SaikuCube> getAllCubes() throws SaikuOlapException {
            return Arrays.asList(sales(), hr());
        }

        @Override
        public List<SaikuMember> getMeasures(SaikuCube cube) {
            // SaikuMember(name, uniqueName, caption, description, dimUniq, hierUniq, levelUniq)
            if ("HR".equals(cube.getName())) {
                return Arrays.asList(
                        new SaikuMember("Headcount", "[Measures].[Headcount]", "Headcount", "",
                                "[Measures]", "[Measures].[MeasuresLevel]", "[Measures].[MeasuresLevel]"));
            }
            return Arrays.asList(
                    new SaikuMember("Store Sales", "[Measures].[Store Sales]", "Store Sales", "",
                            "[Measures]", "[Measures].[MeasuresLevel]", "[Measures].[MeasuresLevel]"),
                    new SaikuMember("Unit Sales", "[Measures].[Unit Sales]", "Unit Sales", "",
                            "[Measures]", "[Measures].[MeasuresLevel]", "[Measures].[MeasuresLevel]"));
        }

        @Override
        public List<SaikuDimension> getAllDimensions(SaikuCube cube) throws SaikuServiceException {
            // SaikuDimension(name, uniqueName, caption, description, visible, hierarchies)
            SaikuDimension measures = new SaikuDimension(
                    "Measures", "[Measures]", "Measures", "", true, new ArrayList<>());
            SaikuDimension time = new SaikuDimension(
                    "Time", "[Time]", "Time", "", true, Arrays.asList(timeByHierarchy()));
            SaikuDimension product = new SaikuDimension(
                    "Product", "[Product]", "Product", "", true, Arrays.asList(productHierarchy()));
            return Arrays.asList(measures, time, product);
        }

        private SaikuHierarchy timeByHierarchy() {
            // SaikuLevel(name, uniqueName, caption, description, dimUniq, hierUniq, visible, levelType, annotations)
            List<SaikuLevel> levels = Arrays.asList(
                    new SaikuLevel("Year", "[Time].[Time By].[Year]", "Year", "",
                            "[Time]", "[Time].[Time By]", true, "Regular", new java.util.HashMap<>()),
                    new SaikuLevel("Quarter", "[Time].[Time By].[Quarter]", "Quarter", "",
                            "[Time]", "[Time].[Time By]", true, "Regular", new java.util.HashMap<>()));
            // SaikuHierarchy(name, uniqueName, caption, description, dimUniq, visible, levels, rootmembers)
            return new SaikuHierarchy(
                    "Time By", "[Time].[Time By]", "Time By", "", "[Time]", true, levels, new ArrayList<>());
        }

        private SaikuHierarchy productHierarchy() {
            List<SaikuLevel> levels = Arrays.asList(
                    new SaikuLevel("Department", "[Product].[Product].[Department]", "Department", "",
                            "[Product]", "[Product].[Product]", true, "Regular", new java.util.HashMap<>()));
            return new SaikuHierarchy(
                    "Product", "[Product].[Product]", "Product", "", "[Product]", true, levels, new ArrayList<>());
        }

        @Override
        public List<SaikuHierarchy> getAllDimensionHierarchies(SaikuCube cube, String dimensionName) {
            if ("Time".equals(dimensionName)) return Arrays.asList(timeByHierarchy());
            if ("Product".equals(dimensionName)) return Arrays.asList(productHierarchy());
            return new ArrayList<>();
        }

        @Override
        public List<SaikuLevel> getAllHierarchyLevels(SaikuCube cube, String dimensionName, String hierarchyName) {
            if ("Time By".equals(hierarchyName)) return timeByHierarchy().getLevels();
            if ("Product".equals(hierarchyName)) return productHierarchy().getLevels();
            return new ArrayList<>();
        }
    }
}
