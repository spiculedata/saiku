/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;
import org.saiku.service.olap.ai.AiCubeRef;

/** Records + SPI contract tests — no network. */
public class NlAskSpiTest {

    private static final AiCubeRef CUBE = new AiCubeRef("conn", "cat", "sch", "Sales");
    private static final String CUBE_SCHEMA_JSON = "{\"cubeName\":\"Sales\"}";
    private static final String REQUEST_SCHEMA_JSON = "{\"type\":\"object\"}";

    @Test
    public void noopProviderReturnsDegraded() {
        NlAskRequest req = new NlAskRequest(CUBE, "show sales", CUBE_SCHEMA_JSON, REQUEST_SCHEMA_JSON, List.of());
        NlAskResponse resp = new NoopNlAskProvider().ask(req);

        assertTrue("noop must return degraded", resp.degraded());
        assertEquals(NoopNlAskProvider.REASON, resp.reason());
        assertNull(resp.aiQueryRequestJson());
        assertEquals(-1, resp.inputTokens());
        assertEquals(-1, resp.outputTokens());
    }

    @Test
    public void requestRejectsBlankQuestion() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> new NlAskRequest(CUBE, "   ", CUBE_SCHEMA_JSON, REQUEST_SCHEMA_JSON, List.of()));
        assertTrue(ex.getMessage().contains("non-blank"));
    }

    @Test
    public void requestRejectsNullCubeRef() {
        assertThrows(
                NullPointerException.class,
                () -> new NlAskRequest(null, "q", CUBE_SCHEMA_JSON, REQUEST_SCHEMA_JSON, List.of()));
    }

    @Test
    public void requestDefensivelyCopiesHistory() {
        // null history → empty
        NlAskRequest a = new NlAskRequest(CUBE, "q", CUBE_SCHEMA_JSON, REQUEST_SCHEMA_JSON, null);
        assertTrue(a.history().isEmpty());

        // mutating the source after construction must not affect the record
        java.util.ArrayList<NlAskMessage> src = new java.util.ArrayList<>();
        src.add(NlAskMessage.user("first"));
        NlAskRequest b = new NlAskRequest(CUBE, "q", CUBE_SCHEMA_JSON, REQUEST_SCHEMA_JSON, src);
        src.add(NlAskMessage.user("second"));
        assertEquals(1, b.history().size());
    }

    @Test
    public void messageFactoryEnforcesRole() {
        NlAskMessage u = NlAskMessage.user("hi");
        NlAskMessage a = NlAskMessage.assistant("hello");
        assertEquals(NlAskMessage.Role.USER, u.role());
        assertEquals("user", u.role().wireName());
        assertEquals(NlAskMessage.Role.ASSISTANT, a.role());
        assertEquals("assistant", a.role().wireName());
    }

    @Test
    public void responseFactoriesPopulateExpectedFields() {
        NlAskResponse ok = NlAskResponse.ok("{}", "claude-x", 42, 17);
        assertFalse(ok.degraded());
        assertEquals("{}", ok.aiQueryRequestJson());
        assertEquals("claude-x", ok.model());
        assertEquals(42, ok.inputTokens());
        assertEquals(17, ok.outputTokens());

        NlAskResponse d1 = NlAskResponse.degraded("transport");
        assertTrue(d1.degraded());
        assertEquals("transport", d1.reason());
        assertNull(d1.aiQueryRequestJson());

        NlAskResponse d2 = NlAskResponse.degraded("parse", "claude-x");
        assertTrue(d2.degraded());
        assertEquals("claude-x", d2.model());
    }
}
