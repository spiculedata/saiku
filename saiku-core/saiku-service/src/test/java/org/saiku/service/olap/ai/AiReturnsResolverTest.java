/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Test;

/**
 * saiku#782 — resolve bare-caption {@code returns=} tokens into the fully
 * qualified MDX form Mondrian's {@code DRILLTHROUGH ... RETURN} expects.
 */
public class AiReturnsResolverTest {

    private AiSchema schema;

    @Before
    public void setUp() {
        // Mini FoodMart-shape: Time / Time By / Year + Quarter; Product /
        // Products / Product Family; measures Store Sales + Unit Sales.
        schema = new AiSchema("cube-1", "Sales", "[Sales]");

        schema.measures.put(AiSchema.key("Store Sales"), measure("Store Sales", "[Measures].[Store Sales]"));
        schema.measures.put(AiSchema.key("Unit Sales"), measure("Unit Sales", "[Measures].[Unit Sales]"));

        AiSchema.Dimension time = new AiSchema.Dimension("Time", "[Time]");
        AiSchema.Hierarchy timeBy = new AiSchema.Hierarchy("Time By", "[Time].[Time By]");
        timeBy.levels.put(AiSchema.key("Year"), new AiSchema.Level("Year", "[Time].[Time By].[Year]"));
        timeBy.levels.put(AiSchema.key("Quarter"), new AiSchema.Level("Quarter", "[Time].[Time By].[Quarter]"));
        time.hierarchies.put(AiSchema.key("Time By"), timeBy);
        schema.dimensions.put(AiSchema.key("Time"), time);

        AiSchema.Dimension product = new AiSchema.Dimension("Product", "[Product]");
        AiSchema.Hierarchy products = new AiSchema.Hierarchy("Products", "[Product].[Products]");
        products.levels.put(
                AiSchema.key("Product Family"),
                new AiSchema.Level("Product Family", "[Product].[Products].[Product Family]"));
        product.hierarchies.put(AiSchema.key("Products"), products);
        schema.dimensions.put(AiSchema.key("Product"), product);
    }

    private static AiSchema.Measure measure(String name, String uniqueName) {
        return new AiSchema.Measure(name, uniqueName);
    }

    @Test
    public void nullReturnsPassesThroughUnchanged() {
        assertNull(AiReturnsResolver.resolve(null, schema));
        assertEquals("", AiReturnsResolver.resolve("", schema));
        assertEquals("  ", AiReturnsResolver.resolve("  ", schema));
    }

    @Test
    public void bareCaptionsResolveToQualifiedMdx() {
        String out = AiReturnsResolver.resolve("Year,Product Family,Store Sales", schema);
        assertEquals("[Time].[Time By].[Year],[Product].[Products].[Product Family],[Measures].[Store Sales]", out);
    }

    @Test
    public void bracketedTokensPassThrough() {
        String input = "[Time].[Time By].[Year],[Measures].[Store Sales]";
        assertEquals("pass-through preserves MDX form", input, AiReturnsResolver.resolve(input, schema));
    }

    @Test
    public void mixedFormsResolvePerToken() {
        // First token bracketed (kept), second bare (rewritten).
        String out = AiReturnsResolver.resolve("[Time].[Time By].[Year],Store Sales", schema);
        assertEquals("[Time].[Time By].[Year],[Measures].[Store Sales]", out);
    }

    @Test
    public void resolutionIsCaseInsensitive() {
        String out = AiReturnsResolver.resolve("YEAR,store sales", schema);
        assertEquals("[Time].[Time By].[Year],[Measures].[Store Sales]", out);
    }

    @Test
    public void unknownTokenRaisesValidationErrorWithCandidates() {
        try {
            AiReturnsResolver.resolve("Year,No Such Column", schema);
            fail("expected AiValidationException");
        } catch (AiValidationException e) {
            assertEquals("returns", e.getField());
            assertTrue("message names the offending token", e.getMessage().contains("No Such Column"));
            assertNotNull(e.getAvailable());
            assertTrue("candidates include 'Store Sales'", e.getAvailable().contains("Store Sales"));
            assertTrue("candidates include 'Product Family'", e.getAvailable().contains("Product Family"));
        }
    }

    @Test
    public void straySpacesAndEmptyEntriesTolerated() {
        // Trailing comma and surrounding whitespace must not crash and must
        // not produce empty MDX entries.
        String out = AiReturnsResolver.resolve(" Year , , Product Family ,", schema);
        assertEquals("[Time].[Time By].[Year],[Product].[Products].[Product Family]", out);
    }

    @Test
    public void nullSchemaPassesThrough() {
        // Defensive: when no schema is available the resolver must leave the
        // value alone — the caller's existing Mondrian-error translation
        // path handles whatever the raw value produces.
        assertEquals("Year,Quarter", AiReturnsResolver.resolve("Year,Quarter", null));
    }
}
