/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiSchema;
import org.saiku.service.olap.ai.ask.AgentSpaceRegistry;
import org.saiku.service.olap.ai.ask.AiAskApi;
import org.saiku.service.olap.ai.ask.AiAskService;
import org.saiku.service.olap.ai.ask.NlAskMessage;
import org.saiku.service.olap.ai.ask.NlAskProvider;
import org.saiku.service.olap.ai.ask.NlAskRequest;
import org.saiku.service.olap.ai.ask.NlAskResponse;

/**
 * Resource-level tests for the space-scoped streaming endpoint {@code POST
 * /ai/spaces/{id}/ask/stream} and the shared ask helpers extracted in saiku#1460. Covers the
 * behavioural fixes folded into that extraction: the 403 scope pre-flight (#1454), the terminal
 * {@code final} event on failure (#1456), and locale-safe {@code forceTool} parsing (#1458).
 */
public class AiAskSpaceStreamTest {

    private static final AiCubeRef ALLOWED = new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales");
    private static final AiCubeRef FORBIDDEN = new AiCubeRef("foodmart", "FoodMart", "FoodMart", "HR");

    private AiQueryResource resource;
    private AiSchema schema;
    private Locale defaultLocale;

    @Before
    public void setUp() {
        defaultLocale = Locale.getDefault();
        schema = new AiSchema("foodmart/FoodMart/FoodMart/Sales", "Sales", "[FoodMart].[Sales]");
        resource = new AiQueryResource();
        resource.setCubeMetadataService(ref -> schema);
    }

    @After
    public void tearDown() {
        Locale.setDefault(defaultLocale);
    }

    /** Wire an askService with a real space registry (Sales allowlisted) + a fixed provider. */
    private void wireSpaceService(NlAskProvider provider) throws Exception {
        Path tmp = Files.createTempDirectory("spaces");
        Files.writeString(
                tmp.resolve("sales.json"),
                "{\"id\":\"sales-analyst\",\"name\":\"Sales Analyst\","
                        + "\"cubeAllowlist\":["
                        + "{\"connectionName\":\"foodmart\",\"catalog\":\"FoodMart\",\"schema\":\"FoodMart\",\"cubeName\":\"Sales\"}"
                        + "]}");
        AiAskService svc = new AiAskService(ref -> schema, provider);
        svc.setSpaces(new AgentSpaceRegistry(tmp));
        resource.setAskService(svc);
    }

    private AiAskApi.AskRequest body(AiCubeRef cube) {
        AiAskApi.AskRequest b = new AiAskApi.AskRequest();
        b.setQuestion("show sales");
        b.setCube(cube);
        return b;
    }

    private static String drain(Response resp) throws Exception {
        assertTrue("streaming endpoints return a StreamingOutput entity", resp.getEntity() instanceof StreamingOutput);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ((StreamingOutput) resp.getEntity()).write(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    // ---- saiku#1454: scope denial maps to a real HTTP status, not a 200 event-stream ----

    @Test
    public void streamingSpaceEndpointReturns403ForCubeOutsideAllowlist() throws Exception {
        wireSpaceService(req -> NlAskResponse.degraded("should not be reached"));
        Response resp = resource.askInSpaceStream("sales-analyst", body(FORBIDDEN));
        assertEquals(
                "a cube outside the persona allowlist must be a real 403, not a 200 SSE error", 403, resp.getStatus());
    }

    @Test
    public void streamingSpaceEndpointReturns404ForMissingSpace() throws Exception {
        wireSpaceService(req -> NlAskResponse.degraded("should not be reached"));
        Response resp = resource.askInSpaceStream("does-not-exist", body(ALLOWED));
        assertEquals(404, resp.getStatus());
    }

    @Test
    public void streamingSpaceEndpointStreams200ForAllowlistedCube() throws Exception {
        wireSpaceService(req -> NlAskResponse.okViewChange(
                "{\"viewMode\":\"chart\",\"chartType\":\"bar\",\"reason\":\"trend\"}", "claude-x", 1, 1));
        Response resp = resource.askInSpaceStream("sales-analyst", body(ALLOWED));
        assertEquals(200, resp.getStatus());
        String frame = drain(resp);
        assertTrue("model event first: " + frame, frame.contains("event: model"));
        assertTrue("intent event present: " + frame, frame.contains("event: intent"));
        assertTrue("terminal final event present: " + frame, frame.contains("event: final"));
    }

    // ---- saiku#1456: an unexpected failure mid-stream still terminates with a final event ----

    @Test
    public void streamingEmitsTerminalFinalWhenOutcomeSupplierThrows() throws Exception {
        Path tmp = Files.createTempDirectory("spaces");
        Files.writeString(
                tmp.resolve("sales.json"),
                "{\"id\":\"sales-analyst\",\"name\":\"Sales Analyst\","
                        + "\"cubeAllowlist\":["
                        + "{\"connectionName\":\"foodmart\",\"catalog\":\"FoodMart\",\"schema\":\"FoodMart\",\"cubeName\":\"Sales\"}"
                        + "]}");
        // askInSpace passes the pre-flight (Sales is allowlisted) but blows up while producing the
        // outcome — the client must still see an error AND a terminating final, never a truncated
        // stream that hangs a client keying completion on `final`.
        AiAskService svc = new AiAskService(ref -> schema, req -> NlAskResponse.degraded("x")) {
            @Override
            public AskOutcome askInSpace(
                    String spaceId,
                    AiCubeRef ref,
                    String question,
                    List<NlAskMessage> history,
                    String cellsetDigest,
                    NlAskRequest.ForceTool forceTool,
                    org.saiku.service.olap.ai.AiQueryRequest currentQuery) {
                throw new IllegalStateException("boom");
            }
        };
        svc.setSpaces(new AgentSpaceRegistry(tmp));
        resource.setAskService(svc);

        Response resp = resource.askInSpaceStream("sales-analyst", body(ALLOWED));
        assertEquals(200, resp.getStatus());
        String frame = drain(resp);
        assertTrue("error event emitted on failure: " + frame, frame.contains("event: error"));
        assertTrue("terminal final event must follow error: " + frame, frame.contains("event: final"));
        assertTrue(
                "final must come after error in the stream",
                frame.indexOf("event: final") > frame.indexOf("event: error"));
    }

    // ---- saiku#1458: forceTool parsing is locale-independent ----

    @Test
    public void parseForceToolIsLocaleIndependent() {
        // On a tr-TR JVM, "insight".toUpperCase() is "İNSİGHT" (dotted capital I) and valueOf fails.
        // parseForceTool must use Locale.ROOT so the user's explicit pick survives.
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        assertEquals(NlAskRequest.ForceTool.INSIGHT, AiQueryResource.parseForceTool("insight"));
        assertEquals(NlAskRequest.ForceTool.QUERY, AiQueryResource.parseForceTool("query"));
        assertEquals(NlAskRequest.ForceTool.VIEW_CHANGE, AiQueryResource.parseForceTool("view_change"));
    }

    @Test
    public void parseForceToolFallsBackToAutoOnUnknownOrNull() {
        assertEquals(NlAskRequest.ForceTool.AUTO, AiQueryResource.parseForceTool(null));
        assertEquals(NlAskRequest.ForceTool.AUTO, AiQueryResource.parseForceTool("bogus"));
    }

    @Test
    public void parseForceToolNotConfiguredWhenAskServiceNull() {
        // Sanity: a null askService still returns 503 (preamble short-circuits before scope check).
        Response resp = resource.askInSpaceStream("sales-analyst", body(ALLOWED));
        assertEquals(503, resp.getStatus());
        assertNotNull(resp.getEntity());
    }
}
