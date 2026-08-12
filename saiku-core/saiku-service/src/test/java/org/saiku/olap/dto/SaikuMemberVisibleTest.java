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
import org.saiku.olap.util.ObjectUtil;

/**
 * saiku#835 — the {@code visible} flag on regular (non-measure) dimension members.
 * Mirrors {@link SaikuMeasureVisibleTest}: the flag must survive the Jackson
 * round-trip, legacy construction must stay visible-by-default, and the
 * {@code $visible} property coercion must fail OPEN on anything unrecognised
 * (hiding a member the author didn't hide is worse than showing one they did).
 */
public class SaikuMemberVisibleTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    public void memberVisibleFalseRoundTrips() throws Exception {
        SaikuMember hidden = new SaikuMember(
                "Unknown",
                "[Customer].[Geography].[Unknown]",
                "Unknown",
                "sentinel bucket",
                "[Customer]",
                "[Customer].[Geography]",
                "[Customer].[Geography].[Country]",
                false);
        hidden.setVisible(false);

        String wire = json.writeValueAsString(hidden);
        assertTrue("visible serialised", wire.contains("\"visible\":false"));

        SaikuMember back = json.readValue(wire, SaikuMember.class);
        assertFalse("visible=false survives round-trip", back.isVisible());
        assertEquals("Unknown", back.getName());
    }

    @Test
    public void legacyConstructionDefaultsToVisible() {
        SaikuMember m = new SaikuMember(
                "USA",
                "[Store].[Stores].[USA]",
                "USA",
                null,
                "[Store]",
                "[Store].[Stores]",
                "[Store].[Stores].[Store Country]",
                false);
        assertTrue("no property set -> visible", m.isVisible() == null || m.isVisible());
    }

    // ── $visible coercion (ObjectUtil.coerceVisible) — dialect-value matrix ──

    @Test
    public void booleanValuesPassThrough() {
        assertTrue(ObjectUtil.coerceVisible(Boolean.TRUE));
        assertFalse(ObjectUtil.coerceVisible(Boolean.FALSE));
    }

    @Test
    public void xmlaStyleStringsAndNumericsCoerce() {
        assertFalse(ObjectUtil.coerceVisible("false"));
        assertFalse(ObjectUtil.coerceVisible("FALSE"));
        assertFalse(ObjectUtil.coerceVisible("0"));
        assertTrue(ObjectUtil.coerceVisible("true"));
        assertFalse(ObjectUtil.coerceVisible(0));
        assertTrue(ObjectUtil.coerceVisible(1));
    }

    @Test
    public void unknownValuesFailOpenToVisible() {
        assertTrue("null property -> visible", ObjectUtil.coerceVisible(null));
        assertTrue("unrecognised string -> visible", ObjectUtil.coerceVisible("maybe"));
        assertTrue("unrecognised type -> visible", ObjectUtil.coerceVisible(new Object()));
    }
}
