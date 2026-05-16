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
 * saiku#818 required_filters enforcement contract. The converter must:
 * <ul>
 *   <li>throw {@link AiValidationException} ({@code field="filters"}) when a level
 *       touched by the request declares {@code requiredFilters} and the request's
 *       {@code filters[]} doesn't satisfy them;</li>
 *   <li>treat an empty {@code members[]} on the filter as <em>not satisfied</em>
 *       — "you must actually pick a year";</li>
 *   <li>list every required filter across the cube in the {@code available} array,
 *       so the agent can construct a complete query in one retry;</li>
 *   <li>fall back to a clean MDX emit when no level the request touches has
 *       {@code requiredFilters} (opt-out semantics — zero impact on unannotated cubes).</li>
 * </ul>
 */
public class AiSchemaConverterRequiredFiltersTest {

    private AiSchema schema;
    private AiSchemaConverter converter;

    @Before
    public void setUp() {
        schema = new AiSchema("foodmart/FoodMart/FoodMart/Sales", "Sales", "[FoodMart].[Sales]");
        schema.measures.put(
                AiSchema.key("Store Sales"), new AiSchema.Measure("Store Sales", "[Measures].[Store Sales]"));

        AiSchema.Dimension time = new AiSchema.Dimension("Time", "[Time]");
        AiSchema.Hierarchy timeBy = new AiSchema.Hierarchy("Time By", "[Time].[Time By]");
        timeBy.levels.put(AiSchema.key("Year"), new AiSchema.Level("Year", "[Time].[Time By].[Year]"));
        AiSchema.Level quarter = new AiSchema.Level("Quarter", "[Time].[Time By].[Quarter]");
        // Quarter requires a filter on Product/Department — picked on purpose to be
        // a DIFFERENT hierarchy so the same-hier overlap guard doesn't fire first.
        // (The real-world pattern is "querying customer data, cube needs a date
        // partition predicate"; we mirror the shape with non-overlapping hierarchies.)
        quarter.requiredFilters = Arrays.asList(new AiSchema.RequiredFilter("Product", "Department"));
        timeBy.levels.put(AiSchema.key("Quarter"), quarter);
        time.hierarchies.put(AiSchema.key("Time By"), timeBy);
        schema.dimensions.put(AiSchema.key("Time"), time);

        AiSchema.Dimension product = new AiSchema.Dimension("Product", "[Product]");
        AiSchema.Hierarchy productH = new AiSchema.Hierarchy("Product", "[Product].[Product]");
        productH.levels.put(
                AiSchema.key("Department"), new AiSchema.Level("Department", "[Product].[Product].[Department]"));
        product.hierarchies.put(AiSchema.key("Product"), productH);
        schema.dimensions.put(AiSchema.key("Product"), product);

        converter = new AiSchemaConverter();
    }

    private AiQueryRequest baseReq() {
        AiQueryRequest req = new AiQueryRequest();
        req.setCube(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
        req.setMeasures(Collections.singletonList(new AiMeasureSelection("Store Sales")));
        return req;
    }

    @Test
    public void missing_required_filter_is_rejected_with_validation_envelope() {
        AiQueryRequest req = baseReq();
        // Touch the Quarter level (which requires Year) without a Year filter.
        req.setRows(Collections.singletonList(new AiAxisSelection("Time", "Time By", "Quarter")));

        try {
            converter.convert(req, schema);
            fail("expected AiValidationException for missing required filter");
        } catch (AiValidationException e) {
            assertEquals("filters", e.getField());
            assertNotNull("available[] must list the required filter", e.getAvailable());
            // The available list carries the cube-wide required-filter set so the
            // agent can construct a complete retry without a /schema round-trip.
            assertTrue(
                    "available[] should include the missing 'Product/Department' requirement, got " + e.getAvailable(),
                    e.getAvailable().contains("Product/Department"));
        }
    }

    @Test
    public void empty_members_on_required_filter_does_not_satisfy() {
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(new AiAxisSelection("Time", "Time By", "Quarter")));
        AiFilterSelection f = new AiFilterSelection();
        f.setDimension("Product");
        f.setHierarchy("Product");
        f.setLevel("Department");
        f.setMembers(Collections.emptyList()); // <-- empty: "you must actually pick a department"
        req.setFilters(Collections.singletonList(f));

        try {
            converter.convert(req, schema);
            fail("expected rejection — empty members[] does not satisfy a required filter");
        } catch (AiValidationException e) {
            assertEquals("filters", e.getField());
        }
    }

    @Test
    public void required_filter_satisfied_lets_query_build() {
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(new AiAxisSelection("Time", "Time By", "Quarter")));
        AiFilterSelection f = new AiFilterSelection();
        f.setDimension("Product");
        f.setHierarchy("Product");
        f.setLevel("Department");
        f.setMembers(Collections.singletonList("[Product].[Product].[Department].&[Food]"));
        req.setFilters(Collections.singletonList(f));

        // No exception — converter produces a real ThinQuery.
        assertNotNull(converter.convert(req, schema));
    }

    @Test
    public void unannotated_cube_has_no_required_filter_check() {
        // Product/Department has no requiredFilters — should build cleanly.
        AiQueryRequest req = baseReq();
        req.setRows(Collections.singletonList(new AiAxisSelection("Product", "Product", "Department")));
        assertNotNull(converter.convert(req, schema));
    }

    @Test
    public void required_filter_check_also_fires_for_filter_axis_usage() {
        // Touching the required-filter-bearing level (Quarter) via filters[]
        // also triggers the check — Quarter still requires Product/Department.
        // Use a different non-Time hierarchy on rows so the overlap guard doesn't
        // intervene.
        AiQueryRequest req = baseReq();
        AiSchema.Dimension extra = new AiSchema.Dimension("Store", "[Store]");
        AiSchema.Hierarchy storeH = new AiSchema.Hierarchy("Store", "[Store].[Store]");
        storeH.levels.put(AiSchema.key("Country"), new AiSchema.Level("Country", "[Store].[Store].[Country]"));
        extra.hierarchies.put(AiSchema.key("Store"), storeH);
        schema.dimensions.put(AiSchema.key("Store"), extra);

        req.setRows(Collections.singletonList(new AiAxisSelection("Store", "Store", "Country")));
        AiFilterSelection f = new AiFilterSelection();
        f.setDimension("Time");
        f.setHierarchy("Time By");
        f.setLevel("Quarter");
        f.setMembers(Collections.singletonList("[Time].[Time By].[Quarter].&[1997].&[Q1]"));
        req.setFilters(Collections.singletonList(f));

        try {
            converter.convert(req, schema);
            fail("expected rejection — Quarter is used and Product/Department filter is missing");
        } catch (AiValidationException e) {
            assertEquals("filters", e.getField());
        }
    }
}
