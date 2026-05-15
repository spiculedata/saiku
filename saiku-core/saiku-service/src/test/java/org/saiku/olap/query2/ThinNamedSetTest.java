/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.olap.query2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import org.junit.Test;

/**
 * JSON round-trip tests for {@link ThinNamedSet} on {@link ThinQueryModel}
 * (saiku#775). Covers the Query2 / QUERYMODEL surface: the DTO is
 * serialised + deserialised cleanly so Query2Resource can land named
 * sets on a per-query model alongside calculated members.
 */
public class ThinNamedSetTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void namedSetRoundTripsThroughJackson() throws Exception {
        ThinNamedSet ns =
                new ThinNamedSet("Premium Customers", "TopCount([Customer].[Name].Members, 50, [Measures].[Revenue])");

        String json = MAPPER.writeValueAsString(ns);
        assertTrue("name in payload — got: " + json, json.contains("\"Premium Customers\""));
        assertTrue("expression in payload — got: " + json, json.contains("TopCount"));

        ThinNamedSet back = MAPPER.readValue(json, ThinNamedSet.class);
        assertEquals("Premium Customers", back.getName());
        assertEquals(ns.getExpression(), back.getExpression());
    }

    @Test
    public void modelCarriesNamedSetsList() throws Exception {
        ThinQueryModel m = new ThinQueryModel();
        m.setNamedSets(Arrays.asList(
                new ThinNamedSet("Top Stores", "TopCount([Store].[Name].Members, 10, [Measures].[Sales])"),
                new ThinNamedSet("Drink Family", "{[Product].[Products].[Drink]}")));

        String json = MAPPER.writeValueAsString(m);
        assertTrue("namedSets key present in payload — got: " + json, json.contains("\"namedSets\""));

        ThinQueryModel back = MAPPER.readValue(json, ThinQueryModel.class);
        assertNotNull(back.getNamedSets());
        assertEquals(2, back.getNamedSets().size());
        assertEquals("Top Stores", back.getNamedSets().get(0).getName());
        assertEquals("Drink Family", back.getNamedSets().get(1).getName());
    }

    @Test
    public void modelWithoutNamedSetsDeserializesToEmpty() throws Exception {
        // A pre-saiku#775 query body has no namedSets key; deserialisation
        // must default to an empty list (not null) so callers can safely
        // iterate.
        String json = "{}";
        ThinQueryModel m = MAPPER.readValue(json, ThinQueryModel.class);
        assertNotNull(m.getNamedSets());
        assertEquals(0, m.getNamedSets().size());
    }

    @Test
    public void setNamedSetsNullCoercesToEmpty() {
        ThinQueryModel m = new ThinQueryModel();
        m.setNamedSets(null);
        assertNotNull("null setter coerces to empty list", m.getNamedSets());
        assertEquals(0, m.getNamedSets().size());
    }
}
