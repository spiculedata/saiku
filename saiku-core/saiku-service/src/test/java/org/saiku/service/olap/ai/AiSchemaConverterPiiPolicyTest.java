/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * saiku#1918 (17a) — the PII gate on the query path.
 *
 * <p>Before this, {@code saiku.semantic.pii=true} was enforced in exactly one place: the
 * drillthrough {@code returns=} resolver. The {@code /ai/schema} response redacted a PII level's
 * display name, description, synonyms and sample members — but deliberately kept its {@code name}
 * and {@code uniqueName}, because the agent needs the shape of the schema. Those are precisely the
 * two strings {@code rows[].level} accepts, so the redaction was cosmetic: any level the schema
 * showed as {@code [REDACTED]} was still fully queryable, and every member caption came back per
 * person. "Aggregated egress" was quietly per-person egress on a PII axis.
 *
 * <p>These tests pin the contract:
 * <ul>
 *   <li>a PII level is refused on rows, on columns, and as a filter target;</li>
 *   <li>a PII measure is refused in {@code measures[]};</li>
 *   <li>a display-name ALIAS of a PII column is refused too — an alias must not be a bypass;</li>
 *   <li>a non-PII query is completely unaffected (zero impact on unannotated cubes);</li>
 *   <li>an unresolvable name still produces the ordinary "unknown level" error, not a PII verdict —
 *       a PII refusal on a typo would be a misleading self-correction signal.</li>
 * </ul>
 */
public class AiSchemaConverterPiiPolicyTest {

    private AiSchema schema;
    private AiSchemaConverter converter;

    @Before
    public void setUp() {
        schema = new AiSchema("foodmart/FoodMart/FoodMart/Sales", "Sales", "[FoodMart].[Sales]");

        AiSchema.Measure storeSales = new AiSchema.Measure("Store Sales", "[Measures].[Store Sales]");
        schema.measures.put(AiSchema.key("Store Sales"), storeSales);
        // A PII MEASURE — the measure-shaped half of the annotation. Real cubes get this from a
        // `saiku.semantic.pii` annotation on a calculated member, e.g. "Customer Email".
        AiSchema.Measure email = new AiSchema.Measure("Customer Email", "[Measures].[Customer Email]");
        email.pii = true;
        schema.measures.put(AiSchema.key("Customer Email"), email);
        // Phase-3 enrichment gives the PII measure a friendly display alias. The alias is the
        // bypass this test exists to close.
        schema.measureAliases.put(AiSchema.key("Email Address"), AiSchema.key("Customer Email"));

        AiSchema.Dimension store = new AiSchema.Dimension("Store", "[Store]");
        AiSchema.Hierarchy storeH = new AiSchema.Hierarchy("Store", "[Store].[Store]");
        storeH.levels.put(AiSchema.key("Store Name"), new AiSchema.Level("Store Name", "[Store].[Store].[Store Name]"));
        storeH.levels.put(
                AiSchema.key("Store Manager"), new AiSchema.Level("Store Manager", "[Store].[Store].[Store Manager]"));
        store.hierarchies.put(AiSchema.key("Store"), storeH);
        schema.dimensions.put(AiSchema.key("Store"), store);

        // PII dimension: the classic "Customer" hierarchy where the leaf level is a person.
        AiSchema.Dimension customer = new AiSchema.Dimension("Customer", "[Customer]");
        AiSchema.Hierarchy custH = new AiSchema.Hierarchy("Customer", "[Customer].[Customer]");
        custH.levels.put(AiSchema.key("Country"), new AiSchema.Level("Country", "[Customer].[Customer].[Country]"));
        AiSchema.Level fullName = new AiSchema.Level("Full Name", "[Customer].[Customer].[Full Name]");
        fullName.pii = true;
        custH.levels.put(AiSchema.key("Full Name"), fullName);
        // Same alias shape as the measure: an enriched display name for the PII level.
        custH.levelAliases.put(AiSchema.key("Customer Name"), AiSchema.key("Full Name"));
        customer.hierarchies.put(AiSchema.key("Customer"), custH);
        schema.dimensions.put(AiSchema.key("Customer"), customer);

        converter = new AiSchemaConverter();
    }

    private AiQueryRequest baseReq() {
        AiQueryRequest req = new AiQueryRequest();
        req.setCube(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
        req.setMeasures(Collections.singletonList(new AiMeasureSelection("Store Sales")));
        return req;
    }

    private static void assertPiiRefused(String field, Runnable r) {
        try {
            r.run();
            fail("expected the PII gate to refuse this request");
        } catch (AiPiiException e) {
            // The dedicated subclass matters: downstream tooling identifies the PII refusal without
            // string-matching the message, so a copy-edit can't silently downgrade it to a
            // generic validation error.
            assertEquals(field, e.getField());
            assertTrue(
                    "refusal must name the annotation that caused it, got: " + e.getMessage(),
                    e.getMessage().contains("saiku.semantic.pii=true"));
        }
    }

    @Test
    public void pii_level_on_rows_is_refused() {
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(new AiAxisSelection("Customer", "Customer", "Full Name")));
        assertPiiRefused("rows[0].level", () -> converter.convert(req, schema));
    }

    @Test
    public void pii_level_on_columns_is_refused() {
        // Columns, not rows: the PII axis is just as per-person whichever side of the query it is
        // on, and the header cell carries the caption either way.
        AiQueryRequest req = baseReq();
        req.setColumns(Collections.singletonList(new AiAxisSelection("Customer", "Customer", "Full Name")));
        assertPiiRefused("columns[0].level", () -> converter.convert(req, schema));
    }

    @Test
    public void pii_level_as_a_filter_target_is_refused() {
        // An `in`-filter over a PII level is a per-person SELECTION even though the measure is an
        // aggregate — the row header echoes the selected caption back, and the caller chose which
        // people. Refusing only the axes would leave this as the easy way around the gate.
        AiQueryRequest req = baseReq();
        AiFilterSelection f = new AiFilterSelection();
        f.setDimension("Customer");
        f.setHierarchy("Customer");
        f.setLevel("Full Name");
        f.setMembers(List.of("[Customer].[Customer].[Full Name].&[W]"));
        req.setFilters(Collections.singletonList(f));
        assertPiiRefused("filters[0].level", () -> converter.convert(req, schema));
    }

    @Test
    public void pii_level_reached_through_a_display_alias_is_refused() {
        // Phase-3 enrichment maps display names onto canonical keys. Without alias-aware
        // resolution, "Customer Name" would be a one-word bypass for "Full Name".
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(new AiAxisSelection("Customer", "Customer", "Customer Name")));
        assertPiiRefused("rows[0].level", () -> converter.convert(req, schema));
    }

    @Test
    public void pii_measure_is_refused() {
        AiQueryRequest req = baseReq();
        req.setMeasures(Collections.singletonList(new AiMeasureSelection("Customer Email")));
        assertPiiRefused("measures[0].name", () -> converter.convert(req, schema));
    }

    @Test
    public void pii_measure_reached_through_a_display_alias_is_refused() {
        AiQueryRequest req = baseReq();
        req.setMeasures(Collections.singletonList(new AiMeasureSelection("Email Address")));
        assertPiiRefused("measures[0].name", () -> converter.convert(req, schema));
    }

    @Test
    public void non_pii_query_is_unaffected() {
        // The gate must be inert on an ordinary cube: a parent level over the same dimension, a
        // normal measure, no annotations in sight — the query converts exactly as it did before.
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(new AiAxisSelection("Customer", "Customer", "Country")));
        req.setColumns(Collections.singletonList(new AiAxisSelection("Store", "Store", "Store Name")));
        String mdx = converter.convert(req, schema).getMdx();
        assertTrue(mdx, mdx.contains("Country"));
        assertTrue(mdx, mdx.contains("Store Sales"));
    }

    @Test
    public void unknown_level_still_reports_unknown_not_pii() {
        // A typo must not be reported as a PII refusal. "Unknown level" carries a self-correction
        // contract (retry with a valid name) that a PII verdict would break, and it would leak the
        // existence of the policy to a caller who got the name wrong.
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(new AiAxisSelection("Customer", "Customer", "No Such Level")));
        try {
            converter.convert(req, schema);
            fail("expected the ordinary unknown-level validation error");
        } catch (AiPiiException e) {
            fail("a typo must not be reported as a PII refusal: " + e.getMessage());
        } catch (AiValidationException e) {
            assertEquals("rows[0].level", e.getField());
        }
    }

    @Test
    public void pii_level_is_still_visible_in_the_schema_shape() {
        // The gate must not have been "fixed" by also hiding the level from /ai/schema: the agent
        // still needs to know the column EXISTS so it can pick a parent level instead. Only the
        // values are redacted — which is what toAgentView() already did.
        AiSchema view = schema.toAgentView();
        AiSchema.Level piiLevel = view.dimensions
                .get(AiSchema.key("Customer"))
                .hierarchies
                .get(AiSchema.key("Customer"))
                .levels
                .get(AiSchema.key("Full Name"));
        assertTrue("PII level must remain listed in the schema", piiLevel != null);
        assertTrue("PII flag must survive redaction", piiLevel.pii);
        assertEquals(
                "sample members must be the redaction sentinel",
                "[REDACTED]",
                piiLevel.sampleMembers.get(0).getCaption());
    }
}
