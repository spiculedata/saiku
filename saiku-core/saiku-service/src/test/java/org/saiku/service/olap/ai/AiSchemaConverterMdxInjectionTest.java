/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Before;
import org.junit.Test;

/**
 * saiku#786 — pin the MDX-injection validator on agent-supplied {@code
 * members[]} entries. The fix landed in commit 8b45aee1 but lacked JUnit
 * coverage; until this test class regressions could only be caught by the
 * live fuzz harness (`saiku-launcher/test-ai-live.sh`).
 *
 * <p>Two surfaces are exercised: axis-selection {@code members[]} and
 * filter {@code members[]}. For each, the same canonical injection
 * payloads must produce a 400-shaped {@link AiValidationException} with a
 * field path that walks back to the offending entry — never reach the
 * MDX emitter, never reach Mondrian.
 */
public class AiSchemaConverterMdxInjectionTest {

    private AiSchema schema;
    private AiSchemaConverter converter;

    @Before
    public void setUp() {
        schema = new AiSchema("cube-1", "Sales", "[Sales]");
        schema.measures.put(
                AiSchema.key("Store Sales"), new AiSchema.Measure("Store Sales", "[Measures].[Store Sales]"));

        AiSchema.Dimension product = new AiSchema.Dimension("Product", "[Product]");
        AiSchema.Hierarchy products = new AiSchema.Hierarchy("Products", "[Product].[Products]");
        products.levels.put(
                AiSchema.key("Product Family"),
                new AiSchema.Level("Product Family", "[Product].[Products].[Product Family]"));
        product.hierarchies.put(AiSchema.key("Products"), products);
        schema.dimensions.put(AiSchema.key("Product"), product);

        // Time dim — used only by the filter test, since saiku#784 rejects a
        // filter that targets a hierarchy already on rows.
        AiSchema.Dimension time = new AiSchema.Dimension("Time", "[Time]");
        AiSchema.Hierarchy timeBy = new AiSchema.Hierarchy("Time By", "[Time].[Time By]");
        timeBy.levels.put(AiSchema.key("Year"), new AiSchema.Level("Year", "[Time].[Time By].[Year]"));
        time.hierarchies.put(AiSchema.key("Time By"), timeBy);
        schema.dimensions.put(AiSchema.key("Time"), time);

        converter = new AiSchemaConverter();
    }

    private AiQueryRequest baseReq() {
        AiQueryRequest req = new AiQueryRequest();
        req.setCube(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
        req.setMeasures(Collections.singletonList(new AiMeasureSelection("Store Sales")));
        return req;
    }

    /* --------------------------- validator-direct ---------------------------- */

    @Test
    public void validatorAcceptsWellFormedRefs() {
        // Canonical shapes the live AI surface receives every day.
        AiSchemaConverter.validateMemberRef("[Foo]", "x");
        AiSchemaConverter.validateMemberRef("[Foo].[Bar]", "x");
        AiSchemaConverter.validateMemberRef("[Time].[Time By].[Year].[1997]", "x");
        AiSchemaConverter.validateMemberRef("[Time].[Time By].&[123]", "x");
        // Whitespace-tolerant — agents sometimes pad
        AiSchemaConverter.validateMemberRef(" [Foo].[Bar] ", "x");
    }

    @Test
    public void validatorRejectsCrossjoinInjection() {
        assertRejects(
                "[Product].[Products].[Drink], Crossjoin([Time].[Time].Members, [Store].[Stores].Members)",
                "members[0]");
    }

    @Test
    public void validatorRejectsOperatorInjection() {
        assertRejects("[Foo] + [Bar]", "x");
        assertRejects("[Foo] * [Bar]", "x");
        assertRejects("[Foo] - [Bar]", "x");
    }

    @Test
    public void validatorRejectsFunctionCall() {
        assertRejects("Children([Time].[1997])", "x");
        assertRejects("[Time].[1997].Children", "x");
        assertRejects("StrToMember(\"[Foo]\")", "x");
    }

    @Test
    public void validatorRejectsStatementSeparator() {
        assertRejects("[Foo]; DROP TABLE", "x");
        assertRejects("[Foo] WITH MEMBER", "x");
    }

    @Test
    public void validatorRejectsBareIdentifier() {
        // Must be bracketed — `Foo` alone is parser-ambiguous.
        assertRejects("Foo", "x");
        assertRejects("Foo.Bar", "x");
    }

    @Test
    public void validatorRejectsNull() {
        assertRejects(null, "x");
    }

    /* ----------------------- end-to-end via converter ------------------------ */

    @Test
    public void injectionInAxisMembersRaisesFieldPath() {
        AiQueryRequest req = baseReq();
        AiAxisSelection sel = new AiAxisSelection("Product", "Products", "Product Family");
        sel.setMembers(Arrays.asList(
                "[Product].[Products].[Drink], Crossjoin([Time].[Time].Members, [Store].[Stores].Members)"));
        req.setRows(Collections.singletonList(sel));
        try {
            converter.convert(req, schema);
            fail("expected AiValidationException; converter let injection through");
        } catch (AiValidationException e) {
            assertNotNull(e.getField());
            assertTrue(
                    "field path walks back to the offending entry: " + e.getField(),
                    e.getField().endsWith(".members[0]"));
            assertTrue(
                    "error message names members[]",
                    e.getMessage().toLowerCase().contains("member"));
        }
    }

    @Test
    public void injectionInFilterMembersRaisesFieldPath() {
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(new AiAxisSelection("Product", "Products", "Product Family")));
        // Filter targets Time (not Product) to avoid saiku#784's
        // axis-filter-hierarchy overlap rejection — we want the validator to
        // reach members[] validation, not bounce earlier.
        AiFilterSelection f = new AiFilterSelection(
                "Time", "Time By", "Year", Arrays.asList("[Time].[Time By].[Year], Crossjoin([Foo], [Bar])"));
        req.setFilters(Collections.singletonList(f));
        try {
            converter.convert(req, schema);
            fail("expected AiValidationException; filter accepted MDX-injection payload");
        } catch (AiValidationException e) {
            assertNotNull(e.getField());
            assertTrue(
                    "field path walks back to filters[].members[]: " + e.getField(),
                    e.getField().endsWith(".members[0]"));
        }
    }

    @Test
    public void wellFormedMembersStillConvert() {
        // Sanity: the validator doesn't reject legitimate refs that look
        // similar in shape — make sure the happy path isn't broken.
        AiQueryRequest req = baseReq();
        AiAxisSelection sel = new AiAxisSelection("Product", "Products", "Product Family");
        sel.setMembers(Arrays.asList("[Product].[Products].[Drink]", "[Product].[Products].[Food]"));
        req.setRows(Collections.singletonList(sel));
        // Shouldn't throw.
        assertEquals("Sales", converter.convert(req, schema).getCube().getName());
    }

    /* ------------------------------ helpers ------------------------------- */

    private static void assertRejects(String memberRef, String fieldPath) {
        try {
            AiSchemaConverter.validateMemberRef(memberRef, fieldPath);
            fail("expected AiValidationException for " + memberRef);
        } catch (AiValidationException e) {
            assertEquals(fieldPath, e.getField());
        }
    }
}
