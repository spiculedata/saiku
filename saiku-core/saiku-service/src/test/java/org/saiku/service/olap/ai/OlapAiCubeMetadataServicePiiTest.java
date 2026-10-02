/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
 * saiku#1918 (17a) — {@code /ai/members/search} must refuse a PII level.
 *
 * <p>The {@code /ai/schema} response replaces a PII level's sample members with a single
 * {@code [REDACTED]} sentinel, so the schema view is careful about exactly the values member search
 * hands back one page at a time. Before this, {@code GET /ai/members/search} (and the MCP
 * {@code search_members} tool behind it) would enumerate them anyway — a per-person caption
 * endpoint sitting one HTTP call away from the surface that went to the trouble of redacting it.
 *
 * <p>Refusing is the right shape rather than returning an empty list: "no members" is
 * indistinguishable from a genuinely empty dimension, so an agent would keep paging a level that
 * will never answer.
 */
public class OlapAiCubeMetadataServicePiiTest {

    private static final String PII_KEY = "saiku.semantic.pii";

    private OlapAiCubeMetadataService svc;
    /** Every warehouse call for the PII level — sample fetch and search alike. Must stay 0. */
    private AtomicInteger fullNameCalls;

    @Before
    public void setUp() {
        fullNameCalls = new AtomicInteger();
        svc = new OlapAiCubeMetadataService();
        svc.setDiscoverService(new PiiStubDiscover());
        // Phase-3 enrichment overlay: gives the PII level a friendly display name, which is how an
        // alias-based bypass would actually be attempted in production.
        svc.setEnrichmentProvider(ref -> {
            AiSchemaEnrichment overlay = new AiSchemaEnrichment();
            overlay.getRenames().put("dimensions.Customer.hierarchies.Customer.levels.Full Name", "Customer Name");
            return overlay;
        });
    }

    private static Map<String, String> annotations(boolean pii) {
        Map<String, String> a = new HashMap<>();
        if (pii) a.put(PII_KEY, "true");
        return a;
    }

    @Test
    public void searchMembersRefusesAPiiLevel() {
        try {
            svc.searchMembers(
                    new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"),
                    "Customer",
                    "Customer",
                    "Full Name",
                    null,
                    20);
            fail("expected a PII refusal for a saiku.semantic.pii level");
        } catch (AiPiiException e) {
            assertEquals("level", e.getField());
            assertTrue(
                    "refusal must name the annotation so an operator can confirm the gate fired, got: "
                            + e.getMessage(),
                    e.getMessage().contains(PII_KEY));
        }
        // The important half: nothing ever asked the warehouse for this level's members. Not the
        // sample fetch during schema build, and not the search itself — the gate is upstream of
        // both, so the captions never enter the JVM at all.
        assertEquals("must not reach the warehouse for a PII level", 0, fullNameCalls.get());
    }

    @Test
    public void searchMembersRefusesAPiiLevelReachedThroughADisplayAlias() {
        // Phase-3 enrichment aliases are resolved before the PII check, so an enriched display name
        // cannot be used to walk around it.
        try {
            svc.searchMembers(
                    new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"),
                    "Customer",
                    "Customer",
                    "Customer Name",
                    null,
                    20);
            fail("expected a PII refusal via the display alias");
        } catch (AiPiiException e) {
            assertEquals("level", e.getField());
        }
    }

    @Test
    public void searchMembersStillWorksForANonPiiLevelOnTheSameDimension() {
        // The gate must be per-level, not per-dimension: a cube where the leaf is PII still has a
        // perfectly legitimate Country level an agent needs to slice by.
        List<SimpleCubeElement> hits = svc.searchMembers(
                new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"),
                "Customer",
                "Customer",
                "Country",
                null,
                20);
        assertEquals(1, hits.size());
        assertEquals("USA", hits.get(0).getName());
        assertEquals("the PII level must still be off limits", 0, fullNameCalls.get());
    }

    /** Cube with a Customer hierarchy: Country (clean) and Full Name (PII). */
    private class PiiStubDiscover extends OlapDiscoverService {

        @Override
        public List<SaikuCube> getAllCubes() {
            return Arrays.asList(
                    new SaikuCube("foodmart", "[FoodMart].[Sales]", "Sales", "Sales", "FoodMart", "FoodMart"));
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
            SaikuLevel country = new SaikuLevel(
                    "Country",
                    "[Customer].[Customer].[Country]",
                    "Country",
                    "",
                    "[Customer]",
                    "[Customer].[Customer]",
                    true,
                    "Regular",
                    annotations(false));
            SaikuLevel fullName = new SaikuLevel(
                    "Full Name",
                    "[Customer].[Customer].[Full Name]",
                    "Full Name",
                    "",
                    "[Customer]",
                    "[Customer].[Customer]",
                    true,
                    "Regular",
                    annotations(true));
            SaikuHierarchy customer = new SaikuHierarchy(
                    "Customer",
                    "[Customer].[Customer]",
                    "Customer",
                    "",
                    "[Customer]",
                    true,
                    Arrays.asList(country, fullName),
                    new ArrayList<>());
            SaikuDimension cust =
                    new SaikuDimension("Customer", "[Customer]", "Customer", "", true, Arrays.asList(customer));
            return Arrays.asList(cust);
        }

        @Override
        public List<SimpleCubeElement> getLevelMembers(
                SaikuCube cube, String hierarchyName, String levelName, String q, int limit) {
            if ("Full Name".equals(levelName)) {
                fullNameCalls.incrementAndGet();
                return Arrays.asList(new SimpleCubeElement("Wanda Maximoff", "[Customer].[Customer].[Full Name].&[W]"));
            }
            return Arrays.asList(new SimpleCubeElement("USA", "[Customer].[Customer].[Country].&[USA]"));
        }
    }
}
