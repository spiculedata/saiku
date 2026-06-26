/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.saiku.service.olap.ai.AiCubeMetadataService;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiSchema;

/** Unit tests for {@link AiAskService}. Provider + metadata service are stubbed. */
public class AiAskServiceTest {

    private static final AiCubeRef CUBE = new AiCubeRef("conn", "cat", "sch", "Sales");

    @Test
    public void degradesWhenCubeRefNull() {
        AiAskService svc = new AiAskService(fixedSchemaService(emptySchema()), stub(NlAskResponse.ok("{}", "m", 0, 0)));
        AiAskService.AskOutcome out = svc.ask(null, "q", List.of());
        assertTrue(out.degraded());
        assertEquals("cube ref required", out.reason());
    }

    @Test
    public void degradesWhenQuestionBlank() {
        AiAskService svc = new AiAskService(fixedSchemaService(emptySchema()), stub(NlAskResponse.ok("{}", "m", 0, 0)));
        AiAskService.AskOutcome out = svc.ask(CUBE, "   ", List.of());
        assertTrue(out.degraded());
        assertEquals("question must be non-blank", out.reason());
    }

    @Test
    public void degradesWhenSchemaLoadThrows() {
        AiAskService svc = new AiAskService(
                ref -> {
                    throw new RuntimeException("cube not found");
                },
                stub(NlAskResponse.ok("{}", "m", 0, 0)));
        AiAskService.AskOutcome out = svc.ask(CUBE, "show sales", List.of());
        assertTrue(out.degraded());
        assertTrue(out.reason().contains("cube not found"));
    }

    @Test
    public void degradesWhenProviderDegrades() {
        AiAskService svc =
                new AiAskService(fixedSchemaService(emptySchema()), stub(NlAskResponse.degraded("not configured")));
        AiAskService.AskOutcome out = svc.ask(CUBE, "show sales", List.of());
        assertTrue(out.degraded());
        assertEquals("not configured", out.reason());
        assertNull(out.request());
    }

    @Test
    public void degradesWhenProviderEmitsInvalidJson() {
        AiAskService svc = new AiAskService(
                fixedSchemaService(emptySchema()), stub(NlAskResponse.ok("not-valid-json", "claude-x", 1, 1)));
        AiAskService.AskOutcome out = svc.ask(CUBE, "show sales", List.of());
        assertTrue(out.degraded());
        assertTrue(out.reason().contains("provider emitted invalid JSON"));
        assertEquals("claude-x", out.model());
    }

    @Test
    public void returnsParsedAiQueryRequestOnSuccess() {
        String emittedJson = "{"
                + "\"cube\":{\"connectionName\":\"conn\",\"catalog\":\"cat\",\"schema\":\"sch\",\"cubeName\":\"Sales\"},"
                + "\"measures\":[{\"name\":\"Store Sales\"}],"
                + "\"rows\":[{\"dimension\":\"Customers\",\"level\":\"Country\"}]"
                + "}";
        AiAskService svc = new AiAskService(
                fixedSchemaService(emptySchema()), stub(NlAskResponse.ok(emittedJson, "claude-x", 100, 50)));

        AiAskService.AskOutcome out = svc.ask(CUBE, "show sales by country", List.of());

        assertFalse(out.degraded());
        assertNotNull(out.request());
        assertEquals("Sales", out.request().getCube().getCubeName());
        assertEquals(1, out.request().getMeasures().size());
        assertEquals("Store Sales", out.request().getMeasures().get(0).getName());
        assertEquals("claude-x", out.model());
    }

    @Test
    public void passesQuestionAndHistoryToProviderUnchanged() {
        AtomicReference<NlAskRequest> seen = new AtomicReference<>();
        NlAskProvider capturing = req -> {
            seen.set(req);
            return NlAskResponse.ok("{\"cube\":{\"cubeName\":\"Sales\"},\"measures\":[]}", "m", 0, 0);
        };
        AiAskService svc = new AiAskService(fixedSchemaService(emptySchema()), capturing);

        svc.ask(CUBE, "show sales by country", List.of(NlAskMessage.user("earlier"), NlAskMessage.assistant("ack")));

        NlAskRequest captured = seen.get();
        assertNotNull(captured);
        assertEquals("show sales by country", captured.question());
        assertEquals(2, captured.history().size());
        assertEquals(CUBE, captured.cubeRef());
        // Schema + request schema should both be non-empty JSON.
        assertTrue(captured.cubeSchemaJson().startsWith("{"));
        assertTrue(captured.requestJsonSchema().contains("AiQueryRequest"));
    }

    // ---------- helpers ----------

    private static AiCubeMetadataService fixedSchemaService(AiSchema schema) {
        return ref -> schema;
    }

    private static AiSchema emptySchema() {
        return new AiSchema("conn/cat/sch/Sales", "Sales", "[Sales]");
    }

    private static NlAskProvider stub(NlAskResponse fixed) {
        return req -> fixed;
    }
}
