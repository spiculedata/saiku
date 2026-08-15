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

    /* ---------------------- saiku#902 PII gate ---------------------- */

    @Test
    public void piiFlaggedMeasureRefused() {
        // Flip the Store Sales measure into PII mode and try to drill it.
        schema.measures.get(AiSchema.key("Store Sales")).pii = true;
        try {
            AiReturnsResolver.resolve("Store Sales", schema);
            fail("expected AiPiiException for PII-flagged measure");
        } catch (AiPiiException expected) {
            assertEquals("returns", expected.getField());
            assertTrue(
                    "message must name the offending column: " + expected.getMessage(),
                    expected.getMessage().contains("Store Sales"));
            assertTrue(
                    "message must reference the annotation so a CISO reading audit can confirm: "
                            + expected.getMessage(),
                    expected.getMessage().contains("saiku.semantic.pii"));
            // Candidate list excludes the PII column.
            assertTrue("Unit Sales is non-PII", expected.getAvailable().contains("Unit Sales"));
            assertTrue("Year is non-PII", expected.getAvailable().contains("Year"));
            for (String c : expected.getAvailable()) {
                assertEquals(
                        "candidates list MUST NOT contain the PII measure name (would defeat redact)",
                        false,
                        "Store Sales".equals(c));
            }
        }
    }

    @Test
    public void piiFlaggedLevelRefused() {
        // Flag the Year level and try a mixed drillthrough.
        schema.dimensions
                .get(AiSchema.key("Time"))
                .hierarchies
                .get(AiSchema.key("Time By"))
                .levels
                .get(AiSchema.key("Year"))
                .pii = true;
        try {
            AiReturnsResolver.resolve("Unit Sales, Year", schema);
            fail("expected AiPiiException for PII-flagged level");
        } catch (AiPiiException expected) {
            assertEquals("returns", expected.getField());
            assertTrue(expected.getMessage().contains("Year"));
            for (String c : expected.getAvailable()) {
                assertEquals(false, "Year".equals(c));
            }
        }
    }

    @Test
    public void piiFlaggedMeasureRefusedViaAlias() {
        // Belt-and-braces: a PII measure reached via display-name alias must
        // also be refused. Otherwise an agent that knows the "Revenue" alias
        // could drill the PII slot.
        schema.measures.get(AiSchema.key("Store Sales")).pii = true;
        schema.measureAliases.put("revenue", AiSchema.key("Store Sales"));
        try {
            AiReturnsResolver.resolve("revenue", schema);
            fail("expected AiPiiException via alias path");
        } catch (AiPiiException expected) {
            assertEquals("returns", expected.getField());
        }
    }

    @Test
    public void piiFlaggedLevelRefusedViaAlias() {
        // Same for level aliases.
        AiSchema.Hierarchy timeBy =
                schema.dimensions.get(AiSchema.key("Time")).hierarchies.get(AiSchema.key("Time By"));
        timeBy.levels.get(AiSchema.key("Year")).pii = true;
        timeBy.levelAliases.put("annual", AiSchema.key("Year"));
        try {
            AiReturnsResolver.resolve("annual", schema);
            fail("expected AiPiiException via level alias path");
        } catch (AiPiiException expected) {
            assertEquals("returns", expected.getField());
        }
    }

    @Test
    public void preBracketedMdxIsAlsoRefusedWhenItRefersToPii() {
        // saiku#1848 — REVERSES a previously documented design choice, deliberately.
        //
        // This test used to assert the opposite: pre-bracketed tokens passed through verbatim even
        // when they named a PII column. The stated rationale was that writing such MDX was "only
        // possible if they already knew the structure outside the describe endpoint, which we
        // redact".
        //
        // That premise does not hold. AiSchema.toAgentView() redacts a PII measure's
        // displayName / description / synonyms / unit / currency — but KEEPS its `name` AND
        // `uniqueName` (see redactMeasure). Verified against the real code:
        //
        //     PII measure present in agent view : true
        //       name       : Salary
        //       uniqueName : [Measures].[Salary]
        //       description: null
        //
        // So the qualified spelling is handed to the agent by Saiku itself, and the refusal message
        // for the bare form actively recommends it ("or a fully-qualified MDX identifier like
        // [Time].[Time].[Year]"). An agent following its own self-correction hint walked around the
        // gate by design rather than by malice — no out-of-band knowledge required.
        //
        // The gate is policy about a COLUMN, so it now applies to every spelling of that column.
        // Unknown bracketed identifiers still pass through untouched (see
        // unknownBracketedIdentifierStillPassesThrough) — this adds a refusal, never a new
        // rejection, so Mondrian remains the judge of MDX it alone understands.
        schema.measures.get(AiSchema.key("Store Sales")).pii = true;
        try {
            AiReturnsResolver.resolve("[Measures].[Store Sales],Unit Sales", schema);
            fail("expected AiPiiException for the qualified spelling of a PII column");
        } catch (AiPiiException expected) {
            assertEquals("returns", expected.getField());
        }
    }

    @Test
    public void unknownBracketedIdentifierStillPassesThrough() {
        // The saiku#1848 gate must not become a validator: an identifier the schema doesn't know
        // is still handed to Mondrian unchanged, exactly as before.
        String out = AiReturnsResolver.resolve("[Some].[Unknown].[Thing],Unit Sales", schema);
        assertEquals("[Some].[Unknown].[Thing],[Measures].[Unit Sales]", out);
    }

    @Test
    public void unknownColumnStillThrowsBaseAiValidationException() {
        // Sanity: regular validation errors are still AiValidationException
        // (not AiPiiException). The controller's two-stage catch depends on
        // this — AiPiiException is the more specific catch, then plain
        // AiValidationException.
        try {
            AiReturnsResolver.resolve("DoesNotExist", schema);
            fail("expected AiValidationException for unknown column");
        } catch (AiPiiException pii) {
            fail("PII exception should not fire for a plain unknown column");
        } catch (AiValidationException expected) {
            assertEquals("returns", expected.getField());
        }
    }
}
