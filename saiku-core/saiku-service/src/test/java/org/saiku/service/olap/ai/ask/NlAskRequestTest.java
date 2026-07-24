/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;
import org.saiku.service.olap.ai.AiCubeRef;

/** {@link NlAskRequest#toolTranscript()} default + {@link NlAskRequest#withToolTranscript} contract. */
public class NlAskRequestTest {

    private static final AiCubeRef CUBE = new AiCubeRef("conn", "cat", "sch", "Sales");
    private static final String CUBE_SCHEMA_JSON = "{\"cubeName\":\"Sales\"}";
    private static final String REQUEST_SCHEMA_JSON = "{\"type\":\"object\"}";

    @Test
    public void convenienceCtorDefaultsToolTranscriptToEmpty() {
        NlAskRequest req = new NlAskRequest(CUBE, "show sales", CUBE_SCHEMA_JSON, REQUEST_SCHEMA_JSON, List.of());
        assertTrue(req.toolTranscript().isEmpty());
    }

    @Test
    public void withToolTranscriptReturnsCopyWithTranscriptAndUnchangedOtherFields() {
        NlAskRequest req = new NlAskRequest(CUBE, "show sales", CUBE_SCHEMA_JSON, REQUEST_SCHEMA_JSON, List.of());
        ToolTurn turn = new ToolTurn("toolu_1", "emit_query", "{}", "3 rows");

        NlAskRequest withTranscript = req.withToolTranscript(List.of(turn));

        assertEquals(1, withTranscript.toolTranscript().size());
        assertEquals(turn, withTranscript.toolTranscript().get(0));
        assertEquals(req.cubeRef(), withTranscript.cubeRef());
        assertEquals(req.question(), withTranscript.question());
        assertEquals(req.cubeSchemaJson(), withTranscript.cubeSchemaJson());
        assertEquals(req.requestJsonSchema(), withTranscript.requestJsonSchema());
        assertEquals(req.history(), withTranscript.history());
        assertEquals(req.cellsetDigest(), withTranscript.cellsetDigest());
        assertEquals(req.forceTool(), withTranscript.forceTool());
        assertEquals(req.currentQueryJson(), withTranscript.currentQueryJson());
        assertEquals(req.skillsFragment(), withTranscript.skillsFragment());
        assertEquals(req.spaceSystemPrompt(), withTranscript.spaceSystemPrompt());
    }

    @Test
    public void withNullToolTranscriptNormalizesToEmpty() {
        NlAskRequest req = new NlAskRequest(CUBE, "show sales", CUBE_SCHEMA_JSON, REQUEST_SCHEMA_JSON, List.of());
        NlAskRequest withTranscript = req.withToolTranscript(null);
        assertTrue(withTranscript.toolTranscript().isEmpty());
    }
}
