/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

/** Round-trip + validation contract for {@link ToolTurn}. */
public class ToolTurnTest {

    @Test
    public void roundTripsAllFourFields() {
        ToolTurn turn = new ToolTurn("toolu_1", "emit_query", "{\"a\":1}", "3 rows");
        assertEquals("toolu_1", turn.toolCallId());
        assertEquals("emit_query", turn.toolName());
        assertEquals("{\"a\":1}", turn.toolInputJson());
        assertEquals("3 rows", turn.resultDigest());
    }

    @Test
    public void nullResultDigestIsAllowed() {
        ToolTurn turn = new ToolTurn("toolu_1", "emit_query", "{}", null);
        assertNull(turn.resultDigest());
    }

    @Test
    public void nullToolCallIdThrows() {
        assertThrows(NullPointerException.class, () -> new ToolTurn(null, "emit_query", "{}", "x"));
    }

    @Test
    public void blankToolCallIdThrows() {
        assertThrows(IllegalArgumentException.class, () -> new ToolTurn("   ", "emit_query", "{}", "x"));
    }

    @Test
    public void nullToolNameThrows() {
        assertThrows(NullPointerException.class, () -> new ToolTurn("toolu_1", null, "{}", "x"));
    }

    @Test
    public void blankToolNameThrows() {
        assertThrows(IllegalArgumentException.class, () -> new ToolTurn("toolu_1", "   ", "{}", "x"));
    }

    @Test
    public void nullToolInputJsonThrows() {
        assertThrows(NullPointerException.class, () -> new ToolTurn("toolu_1", "emit_query", null, "x"));
    }
}
