/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 */
package org.saiku.service.olap.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Before;
import org.junit.Test;

/**
 * saiku#1774 — an axis whose {@code members[]} sit at an ANCESTOR level of the axis
 * {@code level} must emit {@code Descendants(<set>, <level>)} rather than being
 * rejected or collapsed.
 *
 * <p>Why this exists: an App Builder context pill scopes a coarse level (warehouse
 * State Province) while a tile groups by a finer one (Warehouse Name). The old
 * merge collapsed the tile's axis down to the pill's level, so a 13-warehouse
 * ranked list silently became a single "WA" row. Descendants() keeps the tile's own
 * grain, and — unlike a WHERE slicer — stays axis-only, so it never trips Mondrian's
 * "hierarchy appears in more than one independent axis" rule.
 */
public class AiSchemaConverterAncestorAxisTest {

    private AiSchema schema;
    private AiSchemaConverter converter;

    @Before
    public void setUp() {
        schema = new AiSchema("foodmart/FoodMart/FoodMart/Warehouse", "Warehouse", "[FoodMart].[Warehouse]");
        schema.measures.put(
                AiSchema.key("Units Shipped"), new AiSchema.Measure("Units Shipped", "[Measures].[Units Shipped]"));

        // Warehouse -> Warehouses -> (All)/Country/State Province/City/Warehouse Name.
        AiSchema.Dimension wh = new AiSchema.Dimension("Warehouse", "[Warehouse]");
        AiSchema.Hierarchy whs = new AiSchema.Hierarchy("Warehouses", "[Warehouse].[Warehouses]");
        whs.levels.put(AiSchema.key("(All)"), new AiSchema.Level("(All)", "[Warehouse].[Warehouses].[(All)]"));
        whs.levels.put(AiSchema.key("Country"), new AiSchema.Level("Country", "[Warehouse].[Warehouses].[Country]"));
        whs.levels.put(
                AiSchema.key("State Province"),
                new AiSchema.Level("State Province", "[Warehouse].[Warehouses].[State Province]"));
        whs.levels.put(AiSchema.key("City"), new AiSchema.Level("City", "[Warehouse].[Warehouses].[City]"));
        whs.levels.put(
                AiSchema.key("Warehouse Name"),
                new AiSchema.Level("Warehouse Name", "[Warehouse].[Warehouses].[Warehouse Name]"));
        wh.hierarchies.put(AiSchema.key("Warehouses"), whs);
        schema.dimensions.put(AiSchema.key("Warehouse"), wh);

        converter = new AiSchemaConverter();
    }

    private AiQueryRequest baseReq() {
        AiQueryRequest req = new AiQueryRequest();
        req.setCube(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Warehouse"));
        req.setMeasures(Collections.singletonList(new AiMeasureSelection("Units Shipped")));
        return req;
    }

    private AiAxisSelection axis(String level, String... members) {
        AiAxisSelection a = new AiAxisSelection();
        a.setDimension("Warehouse");
        a.setHierarchy("Warehouses");
        a.setLevel(level);
        a.setMembers(Arrays.asList(members));
        return a;
    }

    @Test
    public void ancestorMemberOnAxisEmitsDescendants() {
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(axis("Warehouse Name", "[Warehouse].[Warehouses].[USA].[WA]")));

        String mdx = converter.convert(req, schema).getMdx();

        assertTrue(
                "expected Descendants() for an ancestor-scoped axis, got:\n" + mdx,
                mdx.contains("Descendants({[Warehouse].[Warehouses].[USA].[WA]}, "
                        + "[Warehouse].[Warehouses].[Warehouse Name])"));
        // The tile's own grain survives — this is the whole point.
        assertTrue(mdx.contains("[Warehouse].[Warehouses].[Warehouse Name]"));
    }

    @Test
    public void severalAncestorsAtTheSameLevelDescendTogether() {
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(
                axis("Warehouse Name", "[Warehouse].[Warehouses].[USA].[WA]", "[Warehouse].[Warehouses].[USA].[OR]")));

        String mdx = converter.convert(req, schema).getMdx();

        assertTrue(
                "expected one Descendants() over the whole member set, got:\n" + mdx,
                mdx.contains("Descendants({[Warehouse].[Warehouses].[USA].[WA], "
                        + "[Warehouse].[Warehouses].[USA].[OR]}, "
                        + "[Warehouse].[Warehouses].[Warehouse Name])"));
    }

    @Test
    public void sameLevelMembersStillEmitAPlainSet() {
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(
                axis("State Province", "[Warehouse].[Warehouses].[USA].[WA]", "[Warehouse].[Warehouses].[USA].[OR]")));

        String mdx = converter.convert(req, schema).getMdx();

        assertTrue("same-level members must not be wrapped, got:\n" + mdx, !mdx.contains("Descendants("));
        assertTrue(mdx.contains("{[Warehouse].[Warehouses].[USA].[WA], [Warehouse].[Warehouses].[USA].[OR]}"));
    }

    @Test
    public void descendantMemberIsStillRejected() {
        // A member DEEPER than the axis level is not an ancestor scope — descending
        // from it would return nothing, so the old clear validation error must stand.
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(axis("Country", "[Warehouse].[Warehouses].[USA].[WA].[Seattle]")));

        try {
            converter.convert(req, schema);
            fail("expected a validation error for a descendant-level member on the axis");
        } catch (AiValidationException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("but the axis declares level"));
        }
    }

    @Test
    public void mixedDepthMembersAreStillRejected() {
        // Half ancestors, half not — ambiguous, so fall through to strict validation
        // rather than guessing which the author meant.
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(axis(
                "Warehouse Name",
                "[Warehouse].[Warehouses].[USA].[WA]",
                "[Warehouse].[Warehouses].[USA].[OR].[Portland]")));

        try {
            converter.convert(req, schema);
            fail("expected a validation error for mixed-depth axis members");
        } catch (AiValidationException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("but the axis declares level"));
        }
    }

    @Test
    public void ancestorFromAnotherDimensionIsStillRejected() {
        // Depth is relaxed on the ancestor path; dim/hierarchy agreement is NOT.
        AiQueryRequest req = baseReq();
        AiAxisSelection a = axis("Warehouse Name", "[Store].[Stores].[USA].[WA]");
        req.setRows(Collections.singletonList(a));

        try {
            converter.convert(req, schema);
            fail("expected a validation error for a member from another dimension");
        } catch (AiValidationException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("belongs to dimension"));
        }
    }

    @Test
    public void noMembersStillEmitsAllMembers() {
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(axis("Warehouse Name")));
        String mdx = converter.convert(req, schema).getMdx();
        assertEquals(
                "unchanged when no members are supplied",
                true,
                mdx.contains("[Warehouse].[Warehouses].[Warehouse Name].Members"));
    }
}
