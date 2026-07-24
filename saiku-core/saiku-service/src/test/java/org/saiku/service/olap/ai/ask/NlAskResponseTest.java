/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** {@link NlAskResponse#toolCallId()} carry-through across factories. */
public class NlAskResponseTest {

    @Test
    public void okQueryFourArgDefaultsToolCallIdToNull() {
        NlAskResponse resp = NlAskResponse.okQuery("{}", "claude-x", 1, 2);
        assertNull(resp.toolCallId());
    }

    @Test
    public void okQueryFiveArgCarriesToolCallId() {
        NlAskResponse resp = NlAskResponse.okQuery("{}", "claude-x", 1, 2, "toolu_abc");
        assertEquals("toolu_abc", resp.toolCallId());
    }

    @Test
    public void okInsightToolCallIdIsNull() {
        NlAskResponse resp = NlAskResponse.okInsight("{}", "claude-x", 1, 2);
        assertNull(resp.toolCallId());
    }

    @Test
    public void okViewChangeToolCallIdIsNull() {
        NlAskResponse resp = NlAskResponse.okViewChange("{}", "claude-x", 1, 2);
        assertNull(resp.toolCallId());
    }

    @Test
    public void okEmailDraftToolCallIdIsNull() {
        NlAskResponse resp = NlAskResponse.okEmailDraft("{}", "claude-x", 1, 2);
        assertNull(resp.toolCallId());
    }

    @Test
    public void refusalToolCallIdIsNull() {
        NlAskResponse resp = NlAskResponse.refusal("off-topic", "claude-x", 1, 2);
        assertNull(resp.toolCallId());
    }

    @Test
    public void degradedToolCallIdIsNull() {
        NlAskResponse resp = NlAskResponse.degraded("transport");
        assertNull(resp.toolCallId());

        NlAskResponse resp2 = NlAskResponse.degraded("parse", "claude-x");
        assertNull(resp2.toolCallId());
    }
}
