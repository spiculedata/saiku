/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.saiku.olap.dto.SaikuCube;
import org.saiku.olap.dto.SaikuDimension;
import org.saiku.olap.dto.SaikuHierarchy;
import org.saiku.olap.dto.SaikuLevel;
import org.saiku.olap.dto.SaikuMember;
import org.saiku.olap.dto.SimpleCubeElement;
import org.saiku.service.olap.OlapDiscoverService;

/**
 * saiku#2326 (CWE-284/524/863) — the AI schema cache must be scoped to the
 * CALLER's visibility, and every cache hit must re-check that the caller can
 * still see the cube.
 *
 * <p>Before the fix the singleton cache was keyed by
 * {@code connection/catalog/schema/cube} only: the FIRST caller's role-scoped
 * snapshot (Mondrian filters dimensions, hierarchies, levels and sample
 * members by the caller's role on the role-applied connection) was served
 * verbatim to every later caller, and a caller with no grant on the
 * datasource at all got a 200 off the cache-hit path because that path never
 * reached {@code findCube()}. Reverting the fix flips
 * {@link #getSchema_cacheHitForDifferentRoleSet_rebuildsUnderCallerRole()},
 * {@link #getSchema_cacheHit_whenCubeNotVisibleToCaller_throwsAiValidationException()}
 * and {@link #getSchema_sameRoleSet_sharesEntry()} red.
 */
public class OlapAiCubeMetadataServiceVisibilityTest {

    private static final AiCubeRef SALES = new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales");
    private static final AiCubeRef PEOPLE = new AiCubeRef("otherconn", "HR", "HR", "People");

    /** "admin" (Mondrian root) or "calmgr" (California manager) — flips the stub's responses. */
    private String currentRole = "admin";

    /** What the caller's role-scoped enumeration currently exposes. Tests revoke grants. */
    private boolean salesVisible = true;

    /** Visibility fingerprint the stub {@link AiVisibilityContext} returns. */
    private String currentVisKey = "admin";

    private RoleScopedDiscover discover;
    private OlapAiCubeMetadataService svc;

    @Before
    public void setUp() {
        svc = new OlapAiCubeMetadataService();
        discover = new RoleScopedDiscover();
        svc.setDiscoverService(discover);
        svc.setAiVisibilityContext(connectionName -> currentVisKey);
    }

    /**
     * An admin warms the cache (full schema: Gender dimension, Los Angeles in the
     * Store City sample). A California-manager caller then describes the same cube
     * and must get THEIR role's schema — no Gender dimension, no Los Angeles —
     * rebuilt through their own role-applied enumeration, not the admin snapshot.
     */
    @Test
    public void getSchema_cacheHitForDifferentRoleSet_rebuildsUnderCallerRole() {
        currentRole = "admin";
        currentVisKey = "roles:ROLE_ADMIN,ROLE_USER";
        AiSchema adminSchema = svc.getSchema(SALES);
        assertTrue("admin sees Gender", adminSchema.dimensions.containsKey(AiSchema.key("Gender")));
        assertTrue(
                "admin's Store City sample carries Los Angeles",
                sampleCaptures(adminSchema, "Store", "Stores").contains("[Store].[Stores].[USA].[CA].[Los Angeles]"));

        currentRole = "calmgr";
        currentVisKey = "roles:ROLE_USER,ROLE_California manager";
        AiSchema mgrSchema = svc.getSchema(SALES);

        assertFalse(
                "the restricted role must NOT see the admin's Gender dimension",
                mgrSchema.dimensions.containsKey(AiSchema.key("Gender")));
        assertFalse(
                "the restricted role must NOT see the admin's Los Angeles sample member",
                sampleCaptures(mgrSchema, "Store", "Stores").contains("[Store].[Stores].[USA].[CA].[Los Angeles]"));
        assertEquals(
                "the schema must be rebuilt through the caller's own enumeration (once per role set)",
                2,
                discover.getAllDimensionsCalls.get("Sales").intValue());
        assertNotSame(adminSchema, mgrSchema);
    }

    /**
     * No-grant variant: after the cache is warm, a caller whose enumeration no
     * longer exposes the cube (no datasource grant — the saiku#1968 fail-closed
     * case, or another workspace) must get the same AiValidationException the
     * cold path raises, NOT the cached snapshot.
     */
    @Test
    public void getSchema_cacheHit_whenCubeNotVisibleToCaller_throwsAiValidationException() {
        currentVisKey = "roles:ROLE_ADMIN,ROLE_USER";
        svc.getSchema(SALES); // warm

        salesVisible = false; // the caller lost the grant / other workspace
        try {
            svc.getSchema(SALES);
            fail("expected AiValidationException for a cube the caller cannot see");
        } catch (AiValidationException e) {
            assertEquals("cube", e.getField());
        }
    }

    /**
     * Two callers (or two calls) under the SAME visibility fingerprint share one
     * cache entry — the schema is enumerated once, not per user.
     */
    @Test
    public void getSchema_sameRoleSet_sharesEntry() {
        currentRole = "calmgr";
        currentVisKey = "roles:ROLE_USER,ROLE_California manager";
        AiSchema first = svc.getSchema(SALES);
        AiSchema second = svc.getSchema(SALES);
        assertSame("same visibility context shares the entry", first, second);
        assertEquals(
                "only one enumeration for the whole role set",
                1,
                discover.getAllDimensionsCalls.get("Sales").intValue());
    }

    /**
     * refreshConnection-style invalidation drops only the named connection's
     * entries; other connections' snapshots survive.
     */
    @Test
    public void refreshConnection_invalidatesEntriesForThatConnectionOnly() {
        currentVisKey = "roles:ROLE_ADMIN,ROLE_USER";
        svc.getSchema(SALES);
        svc.getSchema(PEOPLE);
        assertEquals(1, discover.getAllDimensionsCalls.get("Sales").intValue());
        assertEquals(1, discover.getAllDimensionsCalls.get("People").intValue());

        svc.invalidateSchemaCache("foodmart");

        svc.getSchema(SALES);
        assertEquals(
                "the refreshed connection's entry was dropped and rebuilt",
                2,
                discover.getAllDimensionsCalls.get("Sales").intValue());
        svc.getSchema(PEOPLE);
        assertEquals(
                "another connection's entry must be retained",
                1,
                discover.getAllDimensionsCalls.get("People").intValue());
    }

    /**
     * The serialised cubeId (AiSchema.getCubeId) must stay the role-less base
     * key — the visibility fingerprint is a cache-map concern only and must not
     * leak into the agent-facing schema body.
     */
    @Test
    public void aiSchemaCubeIdStaysRoleLess() {
        currentVisKey = "roles:ROLE_USER,ROLE_California manager";
        AiSchema schema = svc.getSchema(SALES);
        assertEquals("foodmart/FoodMart/FoodMart/Sales", schema.getCubeId());
        assertFalse(schema.getCubeId().contains("roles:"));
    }

    /**
     * Without a visibility provider installed the cache keeps its historical
     * role-less behaviour (unit tests, and providers collapse unsecured
     * datasources to the same shared key anyway).
     */
    @Test
    public void withoutVisibilityProvider_legacyKeyingStillCaches() {
        OlapAiCubeMetadataService legacy = new OlapAiCubeMetadataService();
        legacy.setDiscoverService(new RoleScopedDiscover());
        AiSchema first = legacy.getSchema(SALES);
        AiSchema second = legacy.getSchema(SALES);
        assertSame(first, second);
        assertEquals("foodmart/FoodMart/FoodMart/Sales", first.getCubeId());
    }

    private static Set<String> sampleCaptures(AiSchema schema, String dim, String hier) {
        AiSchema.Dimension d = schema.dimensions.get(AiSchema.key(dim));
        assertNotNull("dimension " + dim + " present", d);
        AiSchema.Hierarchy h = d.hierarchies.get(AiSchema.key(hier));
        assertNotNull("hierarchy " + hier + " present", h);
        Set<String> out = new LinkedHashSet<>();
        for (AiSchema.Level level : h.levels.values()) {
            for (AiSchema.MemberSample m : level.sampleMembers) {
                out.add(m.uniqueName);
            }
        }
        return out;
    }

    /**
     * A discover stub whose answers depend on a thread-local "current role",
     * mirroring how Mondrian's role-applied olap4j metadata filters cubes,
     * hierarchies and members per caller. California manager loses the Gender
     * hierarchy and the Los Angeles store member; admin (root) sees everything.
     */
    private class RoleScopedDiscover extends OlapDiscoverService {

        final java.util.Map<String, Integer> getAllDimensionsCalls = new java.util.HashMap<>();

        private SaikuCube sales() {
            return new SaikuCube("foodmart", "[FoodMart].[Sales]", "Sales", "Sales", "FoodMart", "FoodMart");
        }

        private SaikuCube people() {
            return new SaikuCube("otherconn", "[HR].[People]", "People", "People", "HR", "HR");
        }

        @Override
        public List<SaikuCube> getAllCubes() {
            List<SaikuCube> out = new ArrayList<>();
            if (salesVisible) {
                out.add(sales());
            }
            out.add(people());
            return out;
        }

        @Override
        public List<SaikuMember> getMeasures(SaikuCube cube) {
            return Arrays.asList(new SaikuMember(
                    "Store Sales",
                    "[Measures].[Store Sales]",
                    "Store Sales",
                    "",
                    "[Measures]",
                    "[Measures].[MeasuresLevel]",
                    "[Measures].[MeasuresLevel]"));
        }

        @Override
        public List<SaikuDimension> getAllDimensions(SaikuCube cube) {
            getAllDimensionsCalls.merge(cube.getName(), 1, Integer::sum);
            List<SaikuDimension> dims = new ArrayList<>();
            dims.add(dimension("Measures", "Measures", new ArrayList<>()));
            if ("People".equals(cube.getName())) {
                dims.add(dimension(
                        "Employee",
                        "[Employee]",
                        Arrays.asList(hierarchy(
                                "Employee", "[Employee].[Employee]", "[Employee]", levels(level("Employee"))))));
                return dims;
            }
            dims.add(dimension(
                    "Time",
                    "[Time]",
                    Arrays.asList(hierarchy(
                            "Time By", "[Time].[Time By]", "[Time]", levels(level("Year"), level("Quarter"))))));
            dims.add(dimension(
                    "Product",
                    "[Product]",
                    Arrays.asList(
                            hierarchy("Product", "[Product].[Product]", "[Product]", levels(level("Department"))))));
            // [Gender] is access='none' for California manager (FoodMart4.xml).
            if ("admin".equals(currentRole)) {
                dims.add(dimension(
                        "Gender",
                        "[Gender]",
                        Arrays.asList(hierarchy("Gender", "[Gender].[Gender]", "[Gender]", levels(level("Gender"))))));
            }
            dims.add(dimension(
                    "Store",
                    "[Store]",
                    Arrays.asList(hierarchy("Stores", "[Store].[Stores]", "[Store]", levels(level("Store City"))))));
            return dims;
        }

        private SaikuDimension dimension(String name, String uniqueName, List<SaikuHierarchy> hiers) {
            return new SaikuDimension(name, uniqueName, name, "", true, hiers);
        }

        private SaikuHierarchy hierarchy(String name, String uniqueName, String dimUniq, List<SaikuLevel> levels) {
            return new SaikuHierarchy(name, uniqueName, name, "", dimUniq, true, levels, new ArrayList<>());
        }

        private List<SaikuLevel> levels(SaikuLevel... ls) {
            return Arrays.asList(ls);
        }

        private SaikuLevel level(String name) {
            return new SaikuLevel(name, "[" + name + "]", name, "", "", "", true, "Regular", new java.util.HashMap<>());
        }

        @Override
        public List<SimpleCubeElement> getLevelMembers(
                SaikuCube cube, String hierarchyName, String levelName, int limit) {
            return membersFor(hierarchyName, levelName);
        }

        @Override
        public List<SimpleCubeElement> getLevelMembers(
                SaikuCube cube, String hierarchyName, String levelName, String q, int limit) {
            return membersFor(hierarchyName, levelName);
        }

        private List<SimpleCubeElement> membersFor(String hierarchyName, String levelName) {
            if ("Stores".equals(hierarchyName) && "Store City".equals(levelName)) {
                List<SimpleCubeElement> out = new ArrayList<>();
                for (String city :
                        new String[] {"Alameda", "Beverly Hills", "Los Angeles", "San Diego", "San Francisco"}) {
                    if ("calmgr".equals(currentRole) && "Los Angeles".equals(city)) {
                        continue; // the role's member grants hide it
                    }
                    out.add(new SimpleCubeElement(city, "[Store].[Stores].[USA].[CA].[" + city + "]", city));
                }
                return out;
            }
            if ("Gender".equals(hierarchyName)) {
                return Arrays.asList(new SimpleCubeElement("M", "[Gender].[Gender].[M]", "M"));
            }
            return Arrays.asList(new SimpleCubeElement(
                    "S",
                    "[" + hierarchyName + "].[" + levelName + "].&[S]",
                    "[" + hierarchyName + "].[" + levelName + "]"));
        }
    }
}
