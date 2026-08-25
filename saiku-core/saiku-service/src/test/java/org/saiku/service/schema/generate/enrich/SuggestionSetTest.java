/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.generate.enrich;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.Test;
import org.saiku.service.schema.generate.draft.DraftMeasure;
import org.saiku.service.schema.generate.enrich.ops.AggregatorOp;
import org.saiku.service.schema.generate.enrich.ops.DegenerateDimOp;
import org.saiku.service.schema.generate.enrich.ops.HierarchyOp;
import org.saiku.service.schema.generate.enrich.ops.IgnoreOp;
import org.saiku.service.schema.generate.enrich.ops.RenameOp;
import org.saiku.service.schema.generate.enrich.ops.SuggestionOp;

public class SuggestionSetTest {

    @Test
    public void roundTripsAllOpTypesPreservingDiscriminatorAndFields() throws Exception {
        SuggestionSet set = new SuggestionSet();
        set.add(new RenameOp(
                "cubes/Sales/measures/Amount",
                "amount",
                "Amount",
                "Total amount sold",
                0.92,
                "Caption casing + description"));
        set.add(new HierarchyOp(
                "cubes/Sales/dimensions/Date",
                "Calendar",
                List.of("year", "quarter", "month", "day"),
                0.8,
                "Detected date-parts hierarchy"));
        set.add(new AggregatorOp(
                "cubes/Sales/measures/UnitPrice",
                DraftMeasure.Aggregator.SUM,
                DraftMeasure.Aggregator.AVG,
                0.75,
                "Price should be averaged, not summed"));
        set.add(new DegenerateDimOp(
                "cubes/Sales", "order_number", "Order Number", 0.65, "High-cardinality text col on fact"));
        set.add(new IgnoreOp("cubes/Sales/measures/InternalFlag", 0.9, "Internal boolean, not a measure"));

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(set);
        assertNotNull(json);
        assertTrue("JSON should include discriminator 'op'", json.contains("\"op\":"));
        assertTrue(json.contains("\"rename\""));
        assertTrue(json.contains("\"hierarchy\""));
        assertTrue(json.contains("\"aggregator\""));
        assertTrue(json.contains("\"degenerateDim\""));
        assertTrue(json.contains("\"ignore\""));

        SuggestionSet round = mapper.readValue(json, SuggestionSet.class);
        assertEquals(5, round.ops().size());
        assertFalse(round.degraded());

        RenameOp r = (RenameOp) round.ops().get(0);
        assertEquals("cubes/Sales/measures/Amount", r.targetPath());
        assertEquals("amount", r.oldCaption());
        assertEquals("Amount", r.newCaption());
        assertEquals("Total amount sold", r.description());
        assertEquals(0.92, r.confidence(), 1e-9);
        assertEquals("Caption casing + description", r.rationale());

        HierarchyOp h = (HierarchyOp) round.ops().get(1);
        assertEquals("cubes/Sales/dimensions/Date", h.targetPath());
        assertEquals("Calendar", h.hierarchyName());
        assertEquals(List.of("year", "quarter", "month", "day"), h.levelColumns());
        assertEquals(0.8, h.confidence(), 1e-9);

        AggregatorOp a = (AggregatorOp) round.ops().get(2);
        assertEquals("cubes/Sales/measures/UnitPrice", a.targetPath());
        assertEquals(DraftMeasure.Aggregator.SUM, a.oldAggregator());
        assertEquals(DraftMeasure.Aggregator.AVG, a.newAggregator());

        DegenerateDimOp d = (DegenerateDimOp) round.ops().get(3);
        assertEquals("cubes/Sales", d.targetPath());
        assertEquals("order_number", d.factColumn());
        assertEquals("Order Number", d.dimName());

        IgnoreOp i = (IgnoreOp) round.ops().get(4);
        assertEquals("cubes/Sales/measures/InternalFlag", i.targetPath());
        assertEquals(0.9, i.confidence(), 1e-9);
        assertEquals("Internal boolean, not a measure", i.rationale());
    }

    @Test
    public void roundTripsSingleRenameOpPolymorphically() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SuggestionOp op = new RenameOp("cubes/Sales", "sales", "Sales", null, 0.5, "Title case cube name");
        String json = mapper.writeValueAsString(op);
        assertTrue("must include discriminator", json.contains("\"op\":\"rename\""));
        SuggestionOp back = mapper.readValue(json, SuggestionOp.class);
        assertTrue(back instanceof RenameOp);
        assertEquals("cubes/Sales", back.targetPath());
        assertEquals(0.5, back.confidence(), 1e-9);
    }

    @Test
    public void degradedFlagRoundTrips() throws Exception {
        SuggestionSet set = new SuggestionSet();
        set.setDegraded(true);
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(set);
        SuggestionSet round = mapper.readValue(json, SuggestionSet.class);
        assertTrue(round.degraded());
        assertEquals(0, round.ops().size());
    }
}
