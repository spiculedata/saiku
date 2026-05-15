/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.olap.dto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

/**
 * saiku#778 — the {@code visible} flag added to {@link SaikuMember} (and
 * inherited by {@link SaikuMeasure}) must survive Jackson round-trip so the
 * REST contract is durable. Two surfaces are tested:
 *
 * <ul>
 *   <li>The 10-arg {@link SaikuMeasure} ctor — already accepted a
 *       {@code visible} arg but used to silently drop it; this test pins
 *       that the field now lands on the wire.</li>
 *   <li>Legacy callers that build via the 7-arg {@link SaikuMember} ctor
 *       (no visible parameter) — they must default to {@code visible=true}
 *       so existing snapshots / older clients keep their behaviour.</li>
 * </ul>
 */
public class SaikuMeasureVisibleTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    public void saikuMeasureVisibleFalseRoundTrips() throws Exception {
        SaikuMeasure hidden = new SaikuMeasure(
                "Hidden Helper",
                "[Measures].[Hidden Helper]",
                "Hidden Helper",
                "",
                "[Measures]",
                "[Measures].[MeasuresLevel]",
                "[Measures].[MeasuresLevel]",
                false, // visible
                true, // calculated
                null);
        String wire = json.writeValueAsString(hidden);
        assertTrue("visible serialised", wire.contains("\"visible\":false"));

        SaikuMeasure back = json.readValue(wire, SaikuMeasure.class);
        assertFalse("visible=false survives round-trip", back.isVisible());
        assertEquals("Hidden Helper", back.getName());
        assertTrue("calculated=true preserved", back.isCalculated());
    }

    @Test
    public void saikuMeasureVisibleTrueIsTheDefault() throws Exception {
        SaikuMeasure shown = new SaikuMeasure(
                "Store Sales",
                "[Measures].[Store Sales]",
                "Store Sales",
                "",
                "[Measures]",
                "[Measures].[MeasuresLevel]",
                "[Measures].[MeasuresLevel]",
                true,
                false,
                null);
        SaikuMeasure back = json.readValue(json.writeValueAsString(shown), SaikuMeasure.class);
        assertTrue("visible=true preserved", back.isVisible());
    }

    @Test
    public void legacySaikuMemberCtorDefaultsToVisibleTrue() throws Exception {
        // 7-arg legacy ctor (no calculated, no visible) — pre-saiku#778 clients
        // must keep the always-show behaviour they had before this change.
        SaikuMember legacy = new SaikuMember(
                "Drink",
                "[Product].[Drink]",
                "Drink",
                "",
                "[Product]",
                "[Product].[Products]",
                "[Product].[Products].[Product Family]");
        SaikuMember back = json.readValue(json.writeValueAsString(legacy), SaikuMember.class);
        assertTrue("legacy ctor → visible=true", back.isVisible());
    }
}
