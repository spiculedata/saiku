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
import org.saiku.olap.util.exception.SaikuOlapException;
import org.saiku.service.olap.OlapDiscoverService;
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
        AiSchema schema = svc.getSchema(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
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

        assertFalse(
                "Measures dimension should NOT appear as a regular dim",
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
        AiSchema schema = svc.getSchema(new AiCubeRef("FOODMART", "FOODMART", "FOODMART", "sALeS"));
        assertEquals("Sales", schema.getCubeName());
    }

    /* ----- saiku#810 probe / prune ----- */

    /**
     * Probe off (default) — Quarter survives even when getLevelMembers
     * would have thrown for it. Confirms the probe path is fully opt-in.
     */
    @Test
    public void probeOffKeepsUnqueryableLevels() {
        OlapAiCubeMetadataService probeSvc = new OlapAiCubeMetadataService();
        probeSvc.setDiscoverService(new ProbeStubDiscover());
        AiSchema schema = probeSvc.getSchema(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
        AiSchema.Hierarchy timeBy =
                schema.dimensions.get(AiSchema.key("Time")).hierarchies.get(AiSchema.key("Time By"));
        assertTrue("Quarter present when probe off", timeBy.levels.containsKey(AiSchema.key("Quarter")));
    }

    /**
     * Probe on — the Quarter level (which {@link ProbeStubDiscover} marks
     * unqueryable) gets pruned, Year survives. The remaining
     * {@code Time/Time By} hierarchy still has Year so it isn't dropped.
     */
    @Test
    public void probeOnPrunesUnqueryableLevel() {
        OlapAiCubeMetadataService probeSvc = new OlapAiCubeMetadataService();
        probeSvc.setDiscoverService(new ProbeStubDiscover());
        probeSvc.setProbeUnqueryable(true);
        AiSchema schema = probeSvc.getSchema(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
        AiSchema.Hierarchy timeBy =
                schema.dimensions.get(AiSchema.key("Time")).hierarchies.get(AiSchema.key("Time By"));
        assertNotNull(timeBy);
        assertTrue("Year survives", timeBy.levels.containsKey(AiSchema.key("Year")));
        assertFalse("Quarter pruned", timeBy.levels.containsKey(AiSchema.key("Quarter")));
    }

    /**
     * Probe on — if every non-(All) level in a hierarchy fails the probe,
     * the whole hierarchy is dropped. If that was the dim's only hier, the
     * dim is dropped too.
     */
    @Test
    public void probeOnPrunesEntirelyUnqueryableHierarchyAndDim() {
        OlapAiCubeMetadataService probeSvc = new OlapAiCubeMetadataService();
        probeSvc.setDiscoverService(new ProbeStubDiscover() {
            @Override
            public List<org.saiku.olap.dto.SimpleCubeElement> getLevelMembers(
                    SaikuCube cube, String hierarchyName, String levelName, String q, int limit) {
                // Time By is fully unqueryable; Product is fine.
                if ("Time By".equals(hierarchyName)) {
                    throw new RuntimeException("synthetic: Time By unqueryable");
                }
                return new ArrayList<>();
            }
        });
        probeSvc.setProbeUnqueryable(true);
        AiSchema schema = probeSvc.getSchema(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
        assertFalse(
                "Time dim pruned (its only hier was unqueryable)", schema.dimensions.containsKey(AiSchema.key("Time")));
        assertTrue("Product dim survives", schema.dimensions.containsKey(AiSchema.key("Product")));
    }

    /* ----- saiku#778 visible flag ----- */

    /**
     * AI schema must surface the {@code visible} flag from
     * {@link SaikuMember#isVisible()} so admin tooling can see hidden helper
     * measures (e.g. ratios that exist only to support visible measures).
     * Defaults remain {@code true} for unset legacy fixtures.
     */
    @Test
    public void buildSchemaSurfacesVisibleFlagFromDiscoverMember() {
        OlapAiCubeMetadataService visSvc = new OlapAiCubeMetadataService();
        visSvc.setDiscoverService(new StubDiscover() {
            @Override
            public List<SaikuMember> getMeasures(SaikuCube cube) {
                if ("HR".equals(cube.getName())) {
                    return super.getMeasures(cube);
                }
                // Sales: Store Sales visible, helper "Hidden Helper" invisible.
                return Arrays.asList(
                        new org.saiku.olap.dto.SaikuMeasure(
                                "Store Sales",
                                "[Measures].[Store Sales]",
                                "Store Sales",
                                "",
                                "[Measures]",
                                "[Measures].[MeasuresLevel]",
                                "[Measures].[MeasuresLevel]",
                                true, // visible
                                false, // calculated
                                null), // measureGroup
                        new org.saiku.olap.dto.SaikuMeasure(
                                "Hidden Helper",
                                "[Measures].[Hidden Helper]",
                                "Hidden Helper",
                                "",
                                "[Measures]",
                                "[Measures].[MeasuresLevel]",
                                "[Measures].[MeasuresLevel]",
                                false, // visible
                                true, // calculated
                                null));
            }
        });
        AiSchema schema = visSvc.getSchema(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
        AiSchema.Measure storeSales = schema.measures.get(AiSchema.key("Store Sales"));
        AiSchema.Measure hidden = schema.measures.get(AiSchema.key("Hidden Helper"));
        assertNotNull(storeSales);
        assertNotNull(hidden);
        assertTrue("visible measure carries visible=true", Boolean.TRUE.equals(storeSales.visible));
        assertFalse("hidden measure carries visible=false", Boolean.TRUE.equals(hidden.visible));
    }

    /** Stub that fails getLevelMembers for Quarter only. */
    private static class ProbeStubDiscover extends StubDiscover {
        @Override
        public List<org.saiku.olap.dto.SimpleCubeElement> getLevelMembers(
                SaikuCube cube, String hierarchyName, String levelName, String q, int limit) {
            if ("Quarter".equals(levelName)) {
                throw new RuntimeException("synthetic: Quarter unqueryable for saiku#810 test");
            }
            return new ArrayList<>();
        }
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
                return Arrays.asList(new SaikuMember(
                        "Headcount",
                        "[Measures].[Headcount]",
                        "Headcount",
                        "",
                        "[Measures]",
                        "[Measures].[MeasuresLevel]",
                        "[Measures].[MeasuresLevel]"));
            }
            return Arrays.asList(
                    new SaikuMember(
                            "Store Sales",
                            "[Measures].[Store Sales]",
                            "Store Sales",
                            "",
                            "[Measures]",
                            "[Measures].[MeasuresLevel]",
                            "[Measures].[MeasuresLevel]"),
                    new SaikuMember(
                            "Unit Sales",
                            "[Measures].[Unit Sales]",
                            "Unit Sales",
                            "",
                            "[Measures]",
                            "[Measures].[MeasuresLevel]",
                            "[Measures].[MeasuresLevel]"));
        }

        @Override
        public List<SaikuDimension> getAllDimensions(SaikuCube cube) throws SaikuServiceException {
            // SaikuDimension(name, uniqueName, caption, description, visible, hierarchies)
            SaikuDimension measures =
                    new SaikuDimension("Measures", "[Measures]", "Measures", "", true, new ArrayList<>());
            SaikuDimension time =
                    new SaikuDimension("Time", "[Time]", "Time", "", true, Arrays.asList(timeByHierarchy()));
            SaikuDimension product =
                    new SaikuDimension("Product", "[Product]", "Product", "", true, Arrays.asList(productHierarchy()));
            return Arrays.asList(measures, time, product);
        }

        private SaikuHierarchy timeByHierarchy() {
            // SaikuLevel(name, uniqueName, caption, description, dimUniq, hierUniq, visible, levelType, annotations)
            List<SaikuLevel> levels = Arrays.asList(
                    new SaikuLevel(
                            "Year",
                            "[Time].[Time By].[Year]",
                            "Year",
                            "",
                            "[Time]",
                            "[Time].[Time By]",
                            true,
                            "Regular",
                            new java.util.HashMap<>()),
                    new SaikuLevel(
                            "Quarter",
                            "[Time].[Time By].[Quarter]",
                            "Quarter",
                            "",
                            "[Time]",
                            "[Time].[Time By]",
                            true,
                            "Regular",
                            new java.util.HashMap<>()));
            // SaikuHierarchy(name, uniqueName, caption, description, dimUniq, visible, levels, rootmembers)
            return new SaikuHierarchy(
                    "Time By", "[Time].[Time By]", "Time By", "", "[Time]", true, levels, new ArrayList<>());
        }

        private SaikuHierarchy productHierarchy() {
            List<SaikuLevel> levels = Arrays.asList(new SaikuLevel(
                    "Department",
                    "[Product].[Product].[Department]",
                    "Department",
                    "",
                    "[Product]",
                    "[Product].[Product]",
                    true,
                    "Regular",
                    new java.util.HashMap<>()));
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
