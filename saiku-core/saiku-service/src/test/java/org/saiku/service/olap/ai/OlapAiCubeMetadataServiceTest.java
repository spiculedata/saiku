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

    /* ----- saiku#877 per-loop defensive catches ----- */

    /**
     * If one dimension's hierarchy/level walk throws (e.g. Calcite-H2
     * introspection failing under mondrian-saiku#30), the dimensions
     * that come AFTER it in the iteration must still be registered.
     * Before the fix the outer dim-loop catch swallowed the throw and
     * ended iteration — every dimension after the broken one was
     * silently dropped from {@code schema.dimensions}.
     */
    @Test
    public void buildSchema_brokenDimensionDoesNotAbortRemainingDimensions() {
        OlapAiCubeMetadataService brokenSvc = new OlapAiCubeMetadataService();
        brokenSvc.setDiscoverService(new BrokenMiddleDimDiscover());
        AiSchema schema = brokenSvc.getSchema(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));

        assertTrue("Time dim survives", schema.dimensions.containsKey(AiSchema.key("Time")));
        assertTrue(
                "Product dim survives even though Broken was in between",
                schema.dimensions.containsKey(AiSchema.key("Product")));
    }

    /**
     * If one hierarchy's level walk throws, the dimension itself should
     * still register with whatever other hierarchies did succeed. The
     * partial hierarchy may be missing or empty — that's fine — but the
     * dimension's other hierarchies must not be lost.
     */
    @Test
    public void buildSchema_brokenHierarchyDoesNotAbortRemainingHierarchies() {
        OlapAiCubeMetadataService brokenSvc = new OlapAiCubeMetadataService();
        brokenSvc.setDiscoverService(new BrokenHierarchyDiscover());
        AiSchema schema = brokenSvc.getSchema(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));

        AiSchema.Dimension time = schema.dimensions.get(AiSchema.key("Time"));
        assertNotNull("Time dim itself still registered", time);
        assertTrue("good hierarchy survives", time.hierarchies.containsKey(AiSchema.key("Time By")));
    }

    /**
     * A single broken level (the failure mode reported on demo.saiku.bi)
     * must not prevent the OTHER levels in the same hierarchy from
     * being registered, and the hierarchy + dimension must still appear.
     */
    @Test
    public void buildSchema_brokenLevelDoesNotAbortRemainingLevels() {
        OlapAiCubeMetadataService brokenSvc = new OlapAiCubeMetadataService();
        brokenSvc.setDiscoverService(new BrokenLevelDiscover());
        AiSchema schema = brokenSvc.getSchema(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));

        AiSchema.Hierarchy timeBy =
                schema.dimensions.get(AiSchema.key("Time")).hierarchies.get(AiSchema.key("Time By"));
        assertNotNull("hierarchy still registered when one level fails", timeBy);
        assertTrue("good level (Year) survives", timeBy.levels.containsKey(AiSchema.key("Year")));
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

    /* ----- saiku#810 / saiku#818 probe + semantic annotations ----- */

    /**
     * Probe on, but {@code populateSampleMembers} has already proven the level
     * is queryable by fetching ≥1 member. {@code pruneUnqueryable} must not
     * re-fetch members for those levels — that is the N+1 we're hardening.
     */
    @Test
    public void probeOn_skips_redundant_fetch_when_sample_already_proves_queryable() {
        java.util.Map<String, Integer> calls = new java.util.HashMap<>();
        OlapAiCubeMetadataService probeSvc = new OlapAiCubeMetadataService();
        probeSvc.setDiscoverService(new StubDiscover() {
            @Override
            public List<org.saiku.olap.dto.SimpleCubeElement> getLevelMembers(
                    SaikuCube cube, String hierarchyName, String levelName, int searchLimit) {
                String k = hierarchyName + "/" + levelName;
                calls.merge(k, 1, Integer::sum);
                return Arrays.asList(new org.saiku.olap.dto.SimpleCubeElement(
                        "S" + k, "[" + k + "].&[S]", "[" + hierarchyName + "].[" + levelName + "]"));
            }

            @Override
            public List<org.saiku.olap.dto.SimpleCubeElement> getLevelMembers(
                    SaikuCube cube, String hierarchyName, String levelName, String q, int limit) {
                String k = hierarchyName + "/" + levelName;
                calls.merge(k, 1, Integer::sum);
                return Arrays.asList(new org.saiku.olap.dto.SimpleCubeElement(
                        "S" + k, "[" + k + "].&[S]", "[" + hierarchyName + "].[" + levelName + "]"));
            }
        });
        probeSvc.setProbeUnqueryable(true);

        probeSvc.getSchema(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));

        // Time By has Year + Quarter; Product/Product has Department.
        // Before the fix the probe re-fetched each level: count was 2 per (hier, level).
        // After the fix the sample fetch proves queryability and the probe is skipped.
        for (java.util.Map.Entry<String, Integer> e : calls.entrySet()) {
            assertTrue(
                    "getLevelMembers must not be called more than once per level when sample fetch already returned a member; got "
                            + e.getValue()
                            + " for "
                            + e.getKey(),
                    e.getValue() <= 1);
        }
    }

    /**
     * XML-sourced synonyms must register into the alias maps even when no
     * Phase-3 overlay is configured — otherwise an agent posting
     * {@code measures.name = "revenue"} would 400 with VALIDATION_ERROR.
     * The acceptance test in the issue text ("revenue → Store Sales") lives
     * here at the service-build boundary.
     */
    @Test
    public void xmlSourcedSynonymsAreRegisteredIntoAliasMapsWithoutOverlay() {
        OlapAiCubeMetadataService svc = new OlapAiCubeMetadataService();
        svc.setDiscoverService(new SemanticStubDiscover());
        AiSchema schema = svc.getSchema(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));

        // Store Sales declares synonyms=["revenue","turnover"] via XML annotation.
        // The alias map must route "revenue" to the canonical key.
        assertEquals(AiSchema.key("Store Sales"), schema.measureAliases.get(AiSchema.key("revenue")));
        assertEquals(AiSchema.key("Store Sales"), schema.measureAliases.get(AiSchema.key("turnover")));

        // Same for the Quarter level synonyms.
        AiSchema.Hierarchy timeBy =
                schema.dimensions.get(AiSchema.key("Time")).hierarchies.get(AiSchema.key("Time By"));
        assertEquals(AiSchema.key("Quarter"), timeBy.levelAliases.get(AiSchema.key("quarterly")));
        assertEquals(AiSchema.key("Quarter"), timeBy.levelAliases.get(AiSchema.key("fiscal Q")));
    }

    /**
     * buildSchema must project {@code saiku.semantic.*} annotations on measures
     * and levels into the typed {@code AiSchema.Measure}/{@link AiSchema.Level}
     * fields. Non-{@code saiku.semantic.*} annotations are ignored.
     */
    @Test
    public void buildSchemaProjectsSemanticAnnotationsFromMeasuresAndLevels() {
        OlapAiCubeMetadataService svc = new OlapAiCubeMetadataService();
        svc.setDiscoverService(new SemanticStubDiscover());
        AiSchema schema = svc.getSchema(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));

        AiSchema.Measure storeSales = schema.measures.get(AiSchema.key("Store Sales"));
        assertNotNull(storeSales);
        assertEquals("Net retail revenue.", storeSales.description);
        assertTrue(storeSales.synonyms.contains("revenue"));
        assertEquals("USD", storeSales.unit);
        assertEquals("USD", storeSales.currency);
        assertEquals("sum", storeSales.aggregationKind);

        AiSchema.Hierarchy timeBy =
                schema.dimensions.get(AiSchema.key("Time")).hierarchies.get(AiSchema.key("Time By"));
        AiSchema.Level quarter = timeBy.levels.get(AiSchema.key("Quarter"));
        assertNotNull(quarter);
        assertEquals("Calendar quarter.", quarter.description);
        assertEquals("low", quarter.cardinality);
        assertEquals("quarter", quarter.grain);
        assertTrue(quarter.synonyms.contains("quarterly"));
    }

    /** Stub that supplies saiku.semantic.* annotations on Store Sales + Quarter. */
    private static class SemanticStubDiscover extends StubDiscover {
        @Override
        public List<SaikuMember> getMeasures(SaikuCube cube) {
            List<SaikuMember> base = super.getMeasures(cube);
            if (!"Sales".equals(cube.getName())) return base;
            // Decorate the Store Sales SaikuMember with annotations.
            for (SaikuMember m : base) {
                if ("Store Sales".equals(m.getName())) {
                    java.util.Map<String, String> a = new java.util.HashMap<>();
                    a.put("saiku.semantic.description", "Net retail revenue.");
                    a.put("saiku.semantic.synonyms", "revenue, turnover");
                    a.put("saiku.semantic.unit", "USD");
                    a.put("saiku.semantic.currency", "USD");
                    a.put("saiku.semantic.aggregation_kind", "sum");
                    // Future namespace must be ignored by the parser.
                    a.put("saiku.governance.owner", "team-x");
                    m.setAnnotations(a);
                }
            }
            return base;
        }

        @Override
        public List<SaikuDimension> getAllDimensions(SaikuCube cube) throws SaikuServiceException {
            // Rebuild dims so the annotated Quarter level is baked in. The base
            // stub puts hierarchies directly into the SaikuDimension, so
            // buildSchema reads levels from dim.getHierarchies() and never falls
            // back to getAllDimensionHierarchies.
            SaikuDimension measures =
                    new SaikuDimension("Measures", "[Measures]", "Measures", "", true, new ArrayList<>());
            java.util.Map<String, String> quarterAnn = new java.util.HashMap<>();
            quarterAnn.put("saiku.semantic.description", "Calendar quarter.");
            quarterAnn.put("saiku.semantic.cardinality", "low");
            quarterAnn.put("saiku.semantic.grain", "quarter");
            quarterAnn.put("saiku.semantic.synonyms", "quarterly, fiscal Q");
            List<SaikuLevel> timeLevels = Arrays.asList(
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
                            quarterAnn));
            SaikuHierarchy timeBy = new SaikuHierarchy(
                    "Time By", "[Time].[Time By]", "Time By", "", "[Time]", true, timeLevels, new ArrayList<>());
            SaikuDimension time = new SaikuDimension("Time", "[Time]", "Time", "", true, Arrays.asList(timeBy));
            List<SaikuLevel> prodLevels = Arrays.asList(new SaikuLevel(
                    "Department",
                    "[Product].[Product].[Department]",
                    "Department",
                    "",
                    "[Product]",
                    "[Product].[Product]",
                    true,
                    "Regular",
                    new java.util.HashMap<>()));
            SaikuHierarchy prodHier = new SaikuHierarchy(
                    "Product", "[Product].[Product]", "Product", "", "[Product]", true, prodLevels, new ArrayList<>());
            SaikuDimension product =
                    new SaikuDimension("Product", "[Product]", "Product", "", true, Arrays.asList(prodHier));
            return Arrays.asList(measures, time, product);
        }
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

    /**
     * Inserts a "Broken" dimension between Time and Product whose
     * hierarchy fetch throws. Simulates the mondrian-saiku#30 failure
     * mode where Calcite blows up inside the dimension walk.
     */
    private static class BrokenMiddleDimDiscover extends StubDiscover {
        @Override
        public List<SaikuDimension> getAllDimensions(SaikuCube cube) throws SaikuServiceException {
            List<SaikuDimension> base = new ArrayList<>(super.getAllDimensions(cube));
            // Broken dim: empty hierarchies → triggers fallback to
            // getAllDimensionHierarchies, which throws below.
            SaikuDimension broken = new SaikuDimension("Broken", "[Broken]", "Broken", "", true, new ArrayList<>());
            // Insert AFTER Time, BEFORE Product so we can assert Product survives.
            base.add(2, broken);
            return base;
        }

        @Override
        public List<SaikuHierarchy> getAllDimensionHierarchies(SaikuCube cube, String dimensionName) {
            if ("Broken".equals(dimensionName)) {
                throw new RuntimeException("synthetic: Calcite introspection failed for Broken");
            }
            return super.getAllDimensionHierarchies(cube, dimensionName);
        }
    }

    /**
     * Time dimension declares two hierarchies — Time By (the good one
     * from the base stub) and Broken Hier (which has no levels and
     * triggers a throwing fallback). Used to verify per-hierarchy
     * isolation so Time By isn't lost when Broken Hier blows up.
     */
    private static class BrokenHierarchyDiscover extends StubDiscover {
        @Override
        public List<SaikuDimension> getAllDimensions(SaikuCube cube) throws SaikuServiceException {
            // Rebuild Time with TWO hierarchies — Time By (good) + Broken (bad).
            // Use the base stub's hierarchies as the source of truth.
            List<SaikuDimension> base = new ArrayList<>(super.getAllDimensions(cube));
            for (int i = 0; i < base.size(); i++) {
                SaikuDimension dim = base.get(i);
                if (!"Time".equals(dim.getName())) continue;
                List<SaikuHierarchy> hiers = new ArrayList<>(dim.getHierarchies());
                hiers.add(new SaikuHierarchy(
                        "Broken Hier",
                        "[Time].[Broken Hier]",
                        "Broken Hier",
                        "",
                        "[Time]",
                        true,
                        new ArrayList<>(),
                        new ArrayList<>()));
                base.set(
                        i,
                        new SaikuDimension(
                                dim.getName(),
                                dim.getUniqueName(),
                                dim.getCaption(),
                                dim.getDescription(),
                                true,
                                hiers));
            }
            return base;
        }

        @Override
        public List<SaikuLevel> getAllHierarchyLevels(SaikuCube cube, String dimensionName, String hierarchyName) {
            if ("Broken Hier".equals(hierarchyName)) {
                throw new RuntimeException("synthetic: Calcite introspection failed for Broken Hier");
            }
            return super.getAllHierarchyLevels(cube, dimensionName, hierarchyName);
        }
    }

    /**
     * Throws when fetching annotations on a specific level — exercises
     * the per-level catch. Year + Quarter both exist on Time By; one
     * blows up, the other must still register.
     */
    private static class BrokenLevelDiscover extends StubDiscover {
        @Override
        public List<SaikuDimension> getAllDimensions(SaikuCube cube) throws SaikuServiceException {
            List<SaikuDimension> base = new ArrayList<>(super.getAllDimensions(cube));
            // Replace Quarter level with one whose annotations getter throws.
            for (int i = 0; i < base.size(); i++) {
                SaikuDimension dim = base.get(i);
                if (!"Time".equals(dim.getName())) continue;
                List<SaikuHierarchy> newHiers = new ArrayList<>();
                for (SaikuHierarchy h : dim.getHierarchies()) {
                    List<SaikuLevel> newLevels = new ArrayList<>();
                    for (SaikuLevel l : h.getLevels()) {
                        if ("Quarter".equals(l.getName())) {
                            // SaikuLevel constructor takes an annotations map; substitute a
                            // map that throws on any access to simulate a discover-time blowup.
                            newLevels.add(new SaikuLevel(
                                    l.getName(),
                                    l.getUniqueName(),
                                    l.getCaption(),
                                    l.getDescription(),
                                    l.getDimensionUniqueName(),
                                    l.getHierarchyUniqueName(),
                                    true,
                                    l.getLevelType(),
                                    new java.util.HashMap<>() {
                                        @Override
                                        public String get(Object key) {
                                            throw new RuntimeException(
                                                    "synthetic: annotations fetch failed for Quarter");
                                        }

                                        @Override
                                        public java.util.Set<java.util.Map.Entry<String, String>> entrySet() {
                                            throw new RuntimeException(
                                                    "synthetic: annotations fetch failed for Quarter");
                                        }
                                    }));
                        } else {
                            newLevels.add(l);
                        }
                    }
                    newHiers.add(new SaikuHierarchy(
                            h.getName(),
                            h.getUniqueName(),
                            h.getCaption(),
                            h.getDescription(),
                            h.getDimensionUniqueName(),
                            true,
                            newLevels,
                            new ArrayList<>()));
                }
                base.set(
                        i,
                        new SaikuDimension(
                                dim.getName(),
                                dim.getUniqueName(),
                                dim.getCaption(),
                                dim.getDescription(),
                                true,
                                newHiers));
            }
            return base;
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
