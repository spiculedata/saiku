/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.olap.query2;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

/**
 * saiku#778 — the {@code visible} flag added to {@link ThinCalculatedMember}
 * must survive Jackson round-trip so query-time WITH-MEMBER bodies can carry
 * the same visibility intent as schema-level calculated members.
 *
 * <p>Pre-#778 bodies omit the field entirely; those must deserialise to
 * {@code visible=true} so legacy clients aren't silently hidden.
 */
public class ThinCalculatedMemberVisibleTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    public void visibleFalseRoundTrips() throws Exception {
        ThinCalculatedMember hidden = new ThinCalculatedMember();
        hidden.setVisible(false);
        String wire = json.writeValueAsString(hidden);
        assertTrue("visible serialised", wire.contains("\"visible\":false"));

        ThinCalculatedMember back = json.readValue(wire, ThinCalculatedMember.class);
        assertFalse("visible=false survives", back.isVisible());
    }

    @Test
    public void absentFieldDeserialisesAsVisibleTrue() throws Exception {
        // Pre-saiku#778 wire format — no "visible" key. Default must be true
        // so a body issued by a pre-#778 client doesn't get silently hidden.
        String legacyBody = "{\"name\":\"YoY\",\"formula\":\"...\"}";
        ThinCalculatedMember back = json.readValue(legacyBody, ThinCalculatedMember.class);
        assertTrue("absent visible → true (legacy compat)", back.isVisible());
    }
}
