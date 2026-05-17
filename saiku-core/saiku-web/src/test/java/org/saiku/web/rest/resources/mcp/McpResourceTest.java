/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.olap.ai.AiCubeSummary;
import org.saiku.service.olap.ai.AiQueryRequest;
// Stub helper builder defined below — avoids depending on the AiCubeSummary
// constructor shape (POJO with setters).
import org.saiku.service.olap.ai.AiQueryResponse;
import org.saiku.web.rest.resources.AiQueryResource;

/**
 * Unit-test the JSON-RPC framing of {@link McpResource}. Uses a stub
 * {@link AiQueryResource} so we can assert how each MCP method maps to a
 * downstream resource call — no Mondrian / olap4j wiring required.
 */
public class McpResourceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SESSION_HEADER = "Mcp-Session-Id";

    private McpResource resource;
    private McpSessionStore sessionStore;
    private StubAiQueryResource stubAi;

    @Before
    public void setUp() {
        sessionStore = new McpSessionStore(60_000L);
        stubAi = new StubAiQueryResource();
        resource = new McpResource();
        resource.setSessionStore(sessionStore);
        resource.setAiQueryResource(stubAi);
    }

    /* ----------------------------- initialize ---------------------------- */

    @Test
    public void initializeMintsSessionAndReturnsServerInfo() throws Exception {
        Response resp = resource.handle(rpc("initialize", 1, MAPPER.createObjectNode()), null);
        assertEquals(200, resp.getStatus());
        JsonNode body = MAPPER.valueToTree(resp.getEntity());
        assertEquals("2.0", body.get("jsonrpc").asText());
        assertEquals(1, body.get("id").asInt());
        JsonNode result = body.get("result");
        assertEquals("saiku", result.path("serverInfo").path("name").asText());
        assertNotNull(result.path("capabilities").get("tools"));

        String sid = resp.getHeaderString(SESSION_HEADER);
        assertNotNull("initialize should mint a session id", sid);
        assertEquals(1, sessionStore.size());

        // Subsequent tools/list with the same id is accepted.
        Response list = resource.handle(rpc("tools/list", 2, MAPPER.createObjectNode()), sid);
        assertEquals(200, list.getStatus());
        JsonNode listBody = MAPPER.valueToTree(list.getEntity());
        assertNotNull("tools/list result is present", listBody.get("result"));
        assertEquals(6, listBody.path("result").path("tools").size());
    }

    @Test
    public void initializeAcceptsClientProtocolVersion() throws Exception {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("protocolVersion", "2024-11-05");
        Response resp = resource.handle(rpc("initialize", 1, params), null);
        JsonNode body = MAPPER.valueToTree(resp.getEntity());
        assertEquals("2024-11-05", body.path("result").path("protocolVersion").asText());
    }

    /* ------------------------- session enforcement ----------------------- */

    @Test
    public void toolsListWithoutSessionReturnsInvalidRequest() throws Exception {
        Response resp = resource.handle(rpc("tools/list", 1, null), null);
        assertEquals(200, resp.getStatus());
        JsonNode body = MAPPER.valueToTree(resp.getEntity());
        // Body is a JSON-RPC error envelope, not a transport-level 4xx.
        assertEquals(-32600, body.path("error").path("code").asInt());
        assertTrue(body.path("error").path("message").asText().contains("Mcp-Session-Id"));
    }

    @Test
    public void unknownSessionIsRejected() throws Exception {
        Response resp = resource.handle(rpc("tools/list", 1, null), "nonexistent");
        JsonNode body = MAPPER.valueToTree(resp.getEntity());
        assertEquals(-32600, body.path("error").path("code").asInt());
    }

    /* ----------------------------- notifications ------------------------- */

    @Test
    public void notificationsReturn202NoBody() throws Exception {
        // id missing — this is a notification frame
        ObjectNode notif = MAPPER.createObjectNode();
        notif.put("jsonrpc", "2.0");
        notif.put("method", "notifications/initialized");
        Response resp = resource.handle(notif, "any-session");
        assertEquals(202, resp.getStatus());
        assertNull("notifications do not produce a body", resp.getEntity());
    }

    /* ----------------------------- tools/call ---------------------------- */

    @Test
    public void toolsCallListCubesDelegatesToResource() throws Exception {
        String sid = initSession();
        AiCubeSummary summary = new AiCubeSummary();
        summary.setConnectionName("foodmart");
        summary.setCatalog("FoodMart");
        summary.setSchema("FoodMart");
        summary.setCubeName("Sales");
        summary.setCubeCaption("Sales");
        summary.setDefaultMeasure("Unit Sales");
        stubAi.cubesResponse = Response.ok(List.of(summary)).build();

        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", "list_cubes");
        params.set("arguments", MAPPER.createObjectNode());
        Response resp = resource.handle(rpc("tools/call", 2, params), sid);
        JsonNode body = MAPPER.valueToTree(resp.getEntity());

        JsonNode structured = body.path("result").path("structuredContent");
        assertTrue("body should be the array AiQueryResource returned", structured.isArray());
        assertEquals("Sales", structured.get(0).path("cubeCaption").asText());
        assertEquals(false, body.path("result").path("isError").asBoolean());
    }

    @Test
    public void toolsCallRunQueryBindsArgsToAiQueryRequest() throws Exception {
        String sid = initSession();
        // Stub returns a canned AiQueryResponse so we can assert it survives
        // the envelope wrapping unchanged.
        AiQueryResponse canned = new AiQueryResponse();
        canned.setQueryId("query-42");
        canned.setStatus(AiQueryResponse.Status.SUCCESS);
        stubAi.executeResponse = Response.ok(canned).build();

        ObjectNode args = MAPPER.createObjectNode();
        args.put("cube", "foodmart/FoodMart/FoodMart/Sales");
        args.putArray("measures").addObject().put("name", "Unit Sales");
        args.put("format", "matrix"); // stripped before binding; passed as query param

        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", "run_query");
        params.set("arguments", args);
        Response resp = resource.handle(rpc("tools/call", 3, params), sid);
        JsonNode body = MAPPER.valueToTree(resp.getEntity());

        assertEquals("matrix", stubAi.executeFormat.get());
        AiQueryRequest seen = stubAi.executeRequest.get();
        assertNotNull(seen);
        assertEquals("foodmart", seen.getCube().getConnectionName());
        assertEquals("Sales", seen.getCube().getCubeName());
        assertEquals("Unit Sales", seen.getMeasures().get(0).getName());

        assertEquals(
                "query-42",
                body.path("result").path("structuredContent").path("queryId").asText());
    }

    @Test
    public void toolsCallUnknownToolReturnsMethodNotFound() throws Exception {
        String sid = initSession();
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", "no_such_tool");
        params.set("arguments", MAPPER.createObjectNode());
        Response resp = resource.handle(rpc("tools/call", 9, params), sid);
        JsonNode body = MAPPER.valueToTree(resp.getEntity());
        assertEquals(-32601, body.path("error").path("code").asInt());
    }

    @Test
    public void toolsCallMissingRequiredArgReturnsInvalidParams() throws Exception {
        String sid = initSession();
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", "describe_cube"); // requires 'cube'
        params.set("arguments", MAPPER.createObjectNode());
        Response resp = resource.handle(rpc("tools/call", 10, params), sid);
        JsonNode body = MAPPER.valueToTree(resp.getEntity());
        assertEquals(-32602, body.path("error").path("code").asInt());
    }

    /* -------------------------------- ping ------------------------------- */

    @Test
    public void pingReturnsEmptyResult() throws Exception {
        Response resp = resource.handle(rpc("ping", 5, null), null);
        JsonNode body = MAPPER.valueToTree(resp.getEntity());
        assertEquals(0, body.path("result").size());
    }

    /* -------------------------- session bookkeeping ---------------------- */

    @Test
    public void deleteSessionDropsState() {
        String sid = initSession();
        assertEquals(1, sessionStore.size());
        Response resp = resource.deleteSession(sid);
        assertEquals(204, resp.getStatus());
        assertEquals(0, sessionStore.size());
    }

    @Test
    public void sessionStoreEvictsIdleEntries() throws Exception {
        // 1 ms timeout + a 5 ms sleep guarantees the touch sees an expired
        // entry on every supported JVM clock resolution. Real production
        // values are 1h+, but the eviction path needs explicit coverage.
        McpSessionStore shortTtl = new McpSessionStore(1L);
        String sid = shortTtl.create("v1");
        Thread.sleep(5L);
        assertNull("expired entry should not resolve", shortTtl.touch(sid));
        assertEquals(0, shortTtl.size());
    }

    /* ------------------------------ helpers ------------------------------ */

    private String initSession() {
        Response resp = resource.handle(rpc("initialize", 1, MAPPER.createObjectNode()), null);
        String sid = resp.getHeaderString(SESSION_HEADER);
        assertNotNull("initialize must mint a session id", sid);
        return sid;
    }

    private static JsonNode rpc(String method, int id, JsonNode params) {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("method", method);
        req.put("id", id);
        if (params != null) req.set("params", params);
        return req;
    }

    /** Minimal stub — captures arguments and returns canned responses. */
    private static final class StubAiQueryResource extends AiQueryResource {
        Response cubesResponse = Response.ok(List.of()).build();
        Response executeResponse = Response.ok(new AiQueryResponse()).build();
        final AtomicReference<AiQueryRequest> executeRequest = new AtomicReference<>();
        final AtomicReference<String> executeFormat = new AtomicReference<>();

        @Override
        public Response listCubes() {
            return cubesResponse;
        }

        @Override
        public Response executeAi(AiQueryRequest req, String format) {
            executeRequest.set(req);
            executeFormat.set(format);
            // Defensive copy of the underlying entity to make sure the
            // resource isn't mutating its return value in flight — if it
            // does, the assertions above catch it.
            Response orig = executeResponse;
            assertNotSame(orig, null);
            Object entity = orig.getEntity();
            if (entity instanceof AiQueryResponse) {
                AiQueryResponse src = (AiQueryResponse) entity;
                AiQueryResponse copy = new AiQueryResponse();
                copy.setQueryId(src.getQueryId());
                copy.setStatus(src.getStatus());
                return Response.ok(copy).build();
            }
            return Response.ok(entity).build();
        }
    }
}
