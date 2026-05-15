/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.olap.query2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

/**
 * JSON-contract test for {@code ThinQueryModel.visualTotals} (saiku#777).
 *
 * <p>VISUALTOTALS support is end-to-end on Query2:
 * <ul>
 *   <li>The flag is on {@link ThinQueryModel} (this test confirms the
 *       Jackson round-trip works).</li>
 *   <li>{@code Fat.convert(...)} threads it onto the saiku-query
 *       {@code Query} via {@code q.setVisualTotals(model.isVisualTotals())}.</li>
 *   <li>The saiku-query library emits {@code VISUALTOTALS({...})} on the
 *       rows axis at MDX-generation time.</li>
 * </ul>
 *
 * <p>The AI Query API surface gets the same behaviour through
 * {@code AiSchemaConverter} — see the corresponding tests in
 * {@code AiSchemaConverterTest}.
 */
public class ThinQueryModelVisualTotalsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void visualTotalsTrueRoundTrips() throws Exception {
        ThinQueryModel m = new ThinQueryModel();
        m.setVisualTotals(true);

        String json = MAPPER.writeValueAsString(m);
        assertTrue("payload carries visualTotals: true — got: " + json, json.contains("\"visualTotals\":true"));

        ThinQueryModel back = MAPPER.readValue(json, ThinQueryModel.class);
        assertTrue("isVisualTotals() round-trips", back.isVisualTotals());
    }

    @Test
    public void visualTotalsDefaultsToFalse() throws Exception {
        ThinQueryModel m = MAPPER.readValue("{}", ThinQueryModel.class);
        assertFalse("default false when key absent", m.isVisualTotals());
    }

    @Test
    public void visualTotalsPatternRoundTrips() throws Exception {
        ThinQueryModel m = new ThinQueryModel();
        m.setVisualTotals(true);
        m.setVisualTotalsPattern("*Total*");

        String json = MAPPER.writeValueAsString(m);
        ThinQueryModel back = MAPPER.readValue(json, ThinQueryModel.class);
        assertTrue("flag carries", back.isVisualTotals());
        assertEquals("pattern carries", "*Total*", back.getVisualTotalsPattern());
    }
}
