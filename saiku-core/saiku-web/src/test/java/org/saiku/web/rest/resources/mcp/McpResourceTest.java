/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.core.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.olap.ai.AiCubeSummary;
import org.saiku.service.olap.ai.AiPolicy;
import org.saiku.service.olap.ai.AiPolicyGuard;
import org.saiku.service.olap.ai.AiQueryRequest;
// Stub helper builder defined below — avoids depending on the AiCubeSummary
// constructor shape (POJO with setters).
import org.saiku.service.olap.ai.AiQueryResponse;
import org.saiku.service.olap.ai.audit.AiAuditEntry;
import org.saiku.service.olap.ai.audit.AiAuditLog;
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
    public void initializeClampsAnUnsupportedProtocolVersion() throws Exception {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("protocolVersion", "2024-11-05");
        Response resp = resource.handle(rpc("initialize", 1, params), null);
        JsonNode body = MAPPER.valueToTree(resp.getEntity());
        // saiku#2542: only the versions this server implements are negotiable; an arbitrary
        // client string is clamped to the supported version, never echoed verbatim.
        assertEquals("2025-03-26", body.path("result").path("protocolVersion").asText());
    }

    @Test
    public void initializeClampsAnOversizedProtocolVersion() throws Exception {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("protocolVersion", "9".repeat(300_000));
        Response resp = resource.handle(rpc("initialize", 1, params), null);
        assertEquals(200, resp.getStatus());
        JsonNode body = MAPPER.valueToTree(resp.getEntity());
        String echoed = MAPPER.writeValueAsString(body);
        assertTrue("response must not echo the oversized string", echoed.length() < 4_000);
        assertEquals("2025-03-26", body.path("result").path("protocolVersion").asText());
    }

    @Test
    public void initializeKeepsASupportedProtocolVersion() throws Exception {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("protocolVersion", "2025-03-26");
        Response resp = resource.handle(rpc("initialize", 1, params), null);
        JsonNode body = MAPPER.valueToTree(resp.getEntity());
        assertEquals("2025-03-26", body.path("result").path("protocolVersion").asText());
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
    public void unknownSessionIsA404() throws Exception {
        // saiku#2542: a present-but-invalid session id is a transport-level 404, per the
        // streamable-http spec — not a JSON-RPC error over 200.
        Response resp = resource.handle(rpc("tools/list", 1, null), "nonexistent");
        assertEquals(404, resp.getStatus());
    }

    /* ------------------- saiku#2542: principal binding ------------------- */

    private static void login(String principal, String... roles) {
        java.util.List<org.springframework.security.core.GrantedAuthority> authorities = new java.util.ArrayList<>();
        for (String role : roles) {
            authorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority(role));
        }
        org.springframework.security.core.context.SecurityContext ctx =
                org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                principal, "x", authorities));
        org.springframework.security.core.context.SecurityContextHolder.setContext(ctx);
    }

    @After
    public void clearAuth() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    public void aForeignPrincipalCannotUseAnotherUsersSession() throws Exception {
        login("alice", "ROLE_ADMIN");
        Response init = resource.handle(rpc("initialize", 1, MAPPER.createObjectNode()), null);
        String sid = init.getHeaderString(SESSION_HEADER);

        login("bob", "ROLE_USER"); // bob asks for alice's session
        Response foreign = resource.handle(rpc("tools/list", 2, MAPPER.createObjectNode()), sid);
        assertEquals(404, foreign.getStatus());

        login("alice", "ROLE_ADMIN"); // the owner is unaffected
        Response owner = resource.handle(rpc("tools/list", 3, MAPPER.createObjectNode()), sid);
        assertEquals(200, owner.getStatus());
    }

    @Test
    public void aForeignPrincipalCannotDeleteAnotherUsersSession() throws Exception {
        login("alice", "ROLE_ADMIN");
        Response init = resource.handle(rpc("initialize", 1, MAPPER.createObjectNode()), null);
        String sid = init.getHeaderString(SESSION_HEADER);

        login("bob", "ROLE_USER");
        Response foreignDelete = resource.deleteSession(sid);
        assertEquals("a foreign DELETE leaks nothing and must not destroy the session", 204, foreignDelete.getStatus());
        assertEquals(1, sessionStore.size());

        login("alice", "ROLE_ADMIN");
        Response ownerDelete = resource.deleteSession(sid);
        assertEquals(204, ownerDelete.getStatus());
        assertEquals(0, sessionStore.size());
    }

    @Test
    public void toolsCallWithoutSessionIsRejectedBeforeToolAudit() throws Exception {
        AiAuditLog auditLog = wireAuditLog();
        Response resp =
                resource.handle(rpc("tools/call", 1, callParams("list_cubes", MAPPER.createObjectNode())), null);
        JsonNode body = MAPPER.valueToTree(resp.getEntity());
        assertEquals(-32600, body.path("error").path("code").asInt());
        assertEquals(0, auditLog.recent(10, 0, null, null).size());
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
        // saiku#878 follow-up: MCP spec requires structuredContent to be a
        // JSON object — list responses get wrapped under "cubes" so
        // claude.ai's ClaudeAiToolResultRequest validator accepts them.
        assertTrue("structuredContent must be a JSON object", structured.isObject());
        JsonNode cubes = structured.path("cubes");
        assertTrue("cubes[] survives the wrap", cubes.isArray());
        assertEquals("Sales", cubes.get(0).path("cubeCaption").asText());
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
        String sid = shortTtl.create("v1", null);
        Thread.sleep(5L);
        assertNull("expired entry should not resolve", shortTtl.touch(sid, null));
        assertEquals(0, shortTtl.size());
    }

    /* ----------------------- error echo (saiku#1920) ---------------------- */

    @Test
    public void internalErrorDoesNotEchoExceptionMessage() throws Exception {
        String sid = initSession();
        stubAi.listCubesFailure = new IllegalStateException("jdbc:postgresql://warehouse:5432/dw?password=hunter2");

        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", "list_cubes");
        params.set("arguments", MAPPER.createObjectNode());
        Response resp = resource.handle(rpc("tools/call", 2, params), sid);
        JsonNode body = MAPPER.valueToTree(resp.getEntity());

        String message = body.path("error").path("message").asText();
        assertFalse("must not echo the exception text: " + message, message.contains("jdbc:"));
        assertFalse("must not echo the exception text: " + message, message.contains("hunter2"));
        assertTrue("must carry a log correlation ref: " + message, message.contains("ref="));
    }

    /* ----------------- saiku#2540: tools/call audit entries ---------------- */

    /** Wire a real audit log over a temp file and return it for reading back. */
    private AiAuditLog wireAuditLog() throws Exception {
        Path file = Files.createTempFile("mcp-audit-", ".jsonl");
        file.toFile().deleteOnExit();
        AiAuditLog auditLog = new AiAuditLog(file, true);
        resource.setAuditLog(auditLog);
        resource.setAiPolicyGuard(new AiPolicyGuard(AiPolicy.AGGREGATED));
        return auditLog;
    }

    /** saiku#2540: every tools/call produces exactly one ai-audit entry carrying
     *  user, tool, target cube, decision and outcome — the REST /ai/ filter
     *  never sees the in-process call, so this is the only entry. */
    @Test
    public void toolsCallRunQueryWritesOneAuditEntryWithToolAndCube() throws Exception {
        AiAuditLog auditLog = wireAuditLog();
        String sid = initSession();
        stubAi.executeResponse = Response.ok(cannedQueryResponse()).build();

        ObjectNode args = MAPPER.createObjectNode();
        args.put("cube", "foodmart/FoodMart/FoodMart/Sales");
        args.set(
                "measures",
                MAPPER.createArrayNode().add(MAPPER.createObjectNode().put("name", "Unit Sales")));
        resource.handle(rpc("tools/call", 7, callParams("run_query", args)), sid);

        List<AiAuditEntry> entries = auditLog.recent(10, 0, null, null);
        assertEquals("exactly one entry for one tools/call", 1, entries.size());
        AiAuditEntry e = entries.get(0);
        assertEquals("/saiku/api/mcp", e.endpoint);
        assertEquals("run_query", e.tool);
        assertEquals("foodmart/FoodMart/FoodMart/Sales", e.cube);
        assertEquals("no principal bound in a unit test — anonymous, like the REST filter", "anonymous", e.user);
        assertEquals(AiAuditEntry.OUTCOME_SUCCESS, e.outcome);
        assertEquals(AiAuditEntry.DECISION_ALLOW, e.policyDecision);
        assertEquals("aggregated", e.policy);
        assertNotNull(e.latencyMs);
        assertNull("no error on success", e.error);
    }

    /** saiku#2540: a policy denial must be audited as DENIED — the MCP envelope
     *  returns it as a 200 tool error, which an envelope-only auditor would
     *  misread as allow + success. */
    @Test
    public void policyDeniedToolCallIsAuditedAsDenied() throws Exception {
        AiAuditLog auditLog = wireAuditLog();
        // A real resource behind a schema-only guard: executeAi throws
        // AiPolicyViolation before touching any service.
        AiQueryResource guarded = new AiQueryResource();
        guarded.setAiPolicyGuard(new AiPolicyGuard(AiPolicy.SCHEMA_ONLY));
        resource.setAiQueryResource(guarded);
        String sid = initSession();

        ObjectNode args = MAPPER.createObjectNode();
        args.put("cube", "foodmart/FoodMart/FoodMart/Sales");
        args.set(
                "measures",
                MAPPER.createArrayNode().add(MAPPER.createObjectNode().put("name", "Unit Sales")));
        Response resp = resource.handle(rpc("tools/call", 8, callParams("run_query", args)), sid);
        assertEquals("the denial still surfaces as a 200 tool error", 200, resp.getStatus());

        List<AiAuditEntry> entries = auditLog.recent(10, 0, null, null);
        assertEquals(1, entries.size());
        AiAuditEntry e = entries.get(0);
        assertEquals("run_query", e.tool);
        assertEquals("foodmart/FoodMart/FoodMart/Sales", e.cube);
        assertEquals(AiAuditEntry.OUTCOME_DENIED, e.outcome);
        assertEquals(AiAuditEntry.DECISION_DENY, e.policyDecision);
        assertEquals("the deployment's active tier, read off the guard", "aggregated", e.policy);
        assertEquals("short leak-safe token naming the required tier, never the message", "policy:aggregated", e.error);
    }

    /** saiku#2540 (review): a tool call that fails through a non-2xx Response
     *  (badRequest / NOT_FOUND / 5xx from the in-process AI resource) must NOT
     *  be audited as allow + success — 400 maps to validation_error. */
    @Test
    public void badRequestResponseIsAuditedAsValidationErrorNotSuccess() throws Exception {
        AiAuditLog auditLog = wireAuditLog();
        String sid = initSession();
        stubAi.executeResponse = Response.status(400)
                .entity(Map.of("error", "validation failed"))
                .build();

        ObjectNode args = MAPPER.createObjectNode();
        args.put("cube", "foodmart/FoodMart/FoodMart/Sales");
        Response resp = resource.handle(rpc("tools/call", 11, callParams("run_query", args)), sid);
        JsonNode body = MAPPER.valueToTree(resp.getEntity());
        // saiku#2540 (second review): the VALIDATION_ERROR body is the
        // self-correction contract — a 400 comes back as a TOOL RESULT with
        // isError:true carrying field/available, never a bare -32602.
        assertEquals("a 400 is a 200 tool error, not an RPC error", 200, resp.getStatus());
        assertTrue(
                "isError marks the tool failure",
                body.path("result").path("isError").asBoolean(false));
        assertEquals(
                "the VALIDATION_ERROR envelope reaches the agent",
                "validation failed",
                body.path("result").path("structuredContent").path("error").asText());

        List<AiAuditEntry> entries = auditLog.recent(10, 0, null, null);
        assertEquals(1, entries.size());
        AiAuditEntry e = entries.get(0);
        assertEquals("run_query", e.tool);
        assertEquals(
                "a non-2xx Response is a validation failure, not success",
                AiAuditEntry.OUTCOME_VALIDATION_ERROR,
                e.outcome);
        assertEquals(AiAuditEntry.DECISION_ALLOW, e.policyDecision);
        assertEquals("http_400", e.error);
    }

    /** saiku#2540 (review): a 404 — e.g. the owner-scoped drillthrough IDOR
     *  guard on a foreign queryId — is audited as an error, never as success. */
    @Test
    public void notFoundDrillthroughIsAuditedAsErrorNotSuccess() throws Exception {
        AiAuditLog auditLog = wireAuditLog();
        String sid = initSession();
        stubAi.drillthroughResponse =
                Response.status(404).entity(Map.of("error", "unknown queryId")).build();

        ObjectNode args = MAPPER.createObjectNode();
        args.put("queryId", "someone-elses-query");
        Response resp = resource.handle(rpc("tools/call", 12, callParams("drillthrough", args)), sid);
        JsonNode body = MAPPER.valueToTree(resp.getEntity());
        // saiku#2540 (second review): per the spec table a 404 (unknown queryId)
        // is a TOOL RESULT the agent can act on, not a -32603 RPC error.
        assertEquals("a 404 is a 200 tool error", 200, resp.getStatus());
        assertTrue(
                "isError marks the tool failure",
                body.path("result").path("isError").asBoolean(false));
        assertEquals(
                "unknown queryId",
                body.path("result").path("structuredContent").path("error").asText());

        List<AiAuditEntry> entries = auditLog.recent(10, 0, null, null);
        assertEquals(1, entries.size());
        AiAuditEntry e = entries.get(0);
        assertEquals("drillthrough", e.tool);
        assertEquals(AiAuditEntry.OUTCOME_ERROR, e.outcome);
        assertEquals(AiAuditEntry.DECISION_ALLOW, e.policyDecision);
        assertEquals("http_404", e.error);
    }

    /** saiku#2540: failures are audited too — an unknown tool records the tool
     *  name it was given and an error outcome, still exactly one entry. */
    @Test
    public void unknownToolIsAuditedAsError() throws Exception {
        AiAuditLog auditLog = wireAuditLog();
        String sid = initSession();
        resource.handle(rpc("tools/call", 9, callParams("no_such_tool", MAPPER.createObjectNode())), sid);

        List<AiAuditEntry> entries = auditLog.recent(10, 0, null, null);
        assertEquals(1, entries.size());
        AiAuditEntry e = entries.get(0);
        assertEquals("no_such_tool", e.tool);
        assertEquals(AiAuditEntry.OUTCOME_ERROR, e.outcome);
        assertEquals(AiAuditEntry.DECISION_ALLOW, e.policyDecision);
        assertEquals("unknown_tool", e.error);
    }

    /** saiku#2540: the Ossie tools carry the model, not a cube — including on
     *  the denial path (real guarded resource, schema-only). */
    @Test
    public void ossieToolCallAuditCarriesTheModel() throws Exception {
        AiAuditLog auditLog = wireAuditLog();
        org.saiku.web.rest.resources.AiOssieResource guarded = new org.saiku.web.rest.resources.AiOssieResource();
        guarded.setAiPolicyGuard(new AiPolicyGuard(AiPolicy.SCHEMA_ONLY));
        resource.setAiOssieResource(guarded);
        String sid = initSession();

        ObjectNode args = MAPPER.createObjectNode();
        args.put("connection", "Flights");
        args.putArray("values").addObject().put("metric", "flight_count");
        resource.handle(rpc("tools/call", 10, callParams("run_ossie_query", args)), sid);

        List<AiAuditEntry> entries = auditLog.recent(10, 0, null, null);
        assertEquals(1, entries.size());
        AiAuditEntry e = entries.get(0);
        assertEquals("run_ossie_query", e.tool);
        assertNull("no cube target on an Ossie tool", e.cube);
        assertEquals("Flights", e.model);
        assertEquals(AiAuditEntry.OUTCOME_DENIED, e.outcome);
        assertEquals(AiAuditEntry.DECISION_DENY, e.policyDecision);
    }

    /** saiku#2540: the audit target comes from the raw arguments — the
     *  structured cube object is joined to the canonical ref the entry carries. */
    @Test
    public void targetCubeJoinsTheStructuredCubeObject() {
        ObjectNode cube = MAPPER.createObjectNode();
        cube.put("connectionName", "foodmart");
        cube.put("catalog", "FoodMart");
        cube.put("schema", "FoodMart");
        cube.put("cubeName", "Sales");
        ObjectNode args = MAPPER.createObjectNode();
        args.set("cube", cube);
        assertEquals("foodmart/FoodMart/FoodMart/Sales", McpResource.targetCube("run_query", args));
        assertEquals(
                "Flights", McpResource.targetCube("describe_cube", searchArgs().put("cube", "Flights")));
        assertNull("list_cubes has no cube target", McpResource.targetCube("list_cubes", searchArgs()));
        assertNull("drillthrough has no cube target", McpResource.targetCube("drillthrough", searchArgs()));
    }

    private static AiQueryResponse cannedQueryResponse() {
        AiQueryResponse resp = new AiQueryResponse();
        resp.setQueryId("query-audit-1");
        resp.setStatus(AiQueryResponse.Status.SUCCESS);
        return resp;
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

    /* ----------------- saiku#2325: server-side limit bounds ---------------- */

    private void callTool(String name, ObjectNode arguments) throws Exception {
        String sid = initSession();
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments);
        resource.handle(rpc("tools/call", 50, params), sid);
    }

    private static ObjectNode searchArgs() {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("cube", "c/c/c/c");
        args.put("dimension", "Customers");
        args.put("level", "Name");
        return args;
    }

    private static ObjectNode runQueryArgs() {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("cube", "c/c/c/c");
        args.set(
                "measures",
                MAPPER.createArrayNode().add(MAPPER.createObjectNode().put("name", "Unit Sales")));
        return args;
    }

    @Test
    public void searchMembersLimitIsClampedToSchemaMaximum() throws Exception {
        callTool("search_members", searchArgs().put("limit", 2_000_000_000));
        assertEquals(Integer.valueOf(McpResource.SEARCH_MEMBERS_MAX), stubAi.searchLimit.get());
        assertEquals(500, McpResource.SEARCH_MEMBERS_MAX);
    }

    @Test
    public void searchMembersWithinMaximumIsHonoured() throws Exception {
        callTool("search_members", searchArgs().put("limit", 7));
        assertEquals(Integer.valueOf(7), stubAi.searchLimit.get());
    }

    @Test
    public void searchMembersSchemaMaximumMatchesServerClamp() throws Exception {
        String sid = initSession();
        Response list = resource.handle(rpc("tools/list", 2, MAPPER.createObjectNode()), sid);
        JsonNode tools = MAPPER.valueToTree(list.getEntity()).path("result").path("tools");
        for (JsonNode tool : tools) {
            if ("search_members".equals(tool.path("name").asText())) {
                assertEquals(
                        McpResource.SEARCH_MEMBERS_MAX,
                        tool.path("inputSchema")
                                .path("properties")
                                .path("limit")
                                .path("maximum")
                                .asInt());
            }
        }
    }

    @Test
    public void runQueryWithoutLimitDefaultsTo100() throws Exception {
        callTool("run_query", runQueryArgs());
        assertEquals(100, stubAi.executeRequest.get().getLimit());
        assertEquals(100, McpResource.RUN_QUERY_DEFAULT_LIMIT);
    }

    @Test
    public void runQueryExplicitLimitIsHonoured() throws Exception {
        callTool("run_query", runQueryArgs().put("limit", 250));
        assertEquals(250, stubAi.executeRequest.get().getLimit());
    }

    @Test
    public void runQueryZeroLimitResolvesToServerMaximum() throws Exception {
        callTool("run_query", runQueryArgs().put("limit", 0));
        assertEquals(10_000, stubAi.executeRequest.get().getLimit());
    }

    @Test
    public void runQueryNegativeLimitResolvesToServerMaximum() throws Exception {
        callTool("run_query", runQueryArgs().put("limit", -3));
        assertEquals(10_000, stubAi.executeRequest.get().getLimit());
    }

    @Test
    public void runQueryHugeLimitIsClampedToServerMaximum() throws Exception {
        callTool("run_query", runQueryArgs().put("limit", 2_000_000_000));
        assertEquals(10_000, stubAi.executeRequest.get().getLimit());
    }

    /** Minimal stub — captures arguments and returns canned responses. */
    /* --------------------- Ossie tools (saiku#1410) -------------------- */

    @Test
    public void ossieOntologyToolAdvertisedWhenResourceWired() throws Exception {
        StubAiOssieResource stubOssie = new StubAiOssieResource();
        resource.setAiOssieResource(stubOssie);
        String sid = openSession();
        Response list = resource.handle(rpc("tools/list", 2, MAPPER.createObjectNode()), sid);
        JsonNode listBody = MAPPER.valueToTree(list.getEntity());
        List<String> toolNames = new java.util.ArrayList<>();
        listBody.path("result")
                .path("tools")
                .forEach(t -> toolNames.add(t.path("name").asText()));
        // 6 OLAP tools + 6 Ossie tools (list, describe, describe_ontology, search, run, preview)
        assertEquals(12, toolNames.size());
        assertTrue("describe_ossie_ontology should be advertised", toolNames.contains("describe_ossie_ontology"));
    }

    @Test
    public void describeOssieOntologyDelegatesToResource() throws Exception {
        StubAiOssieResource stubOssie = new StubAiOssieResource();
        stubOssie.ontologyResponse =
                Response.ok(java.util.Map.of("ontology", List.of())).build();
        resource.setAiOssieResource(stubOssie);
        String sid = openSession();
        ObjectNode args = MAPPER.createObjectNode();
        args.put("connection", "Pharma");
        args.put("model", "Pharma");
        Response resp = resource.handle(rpc("tools/call", 3, callParams("describe_ossie_ontology", args)), sid);
        assertEquals(200, resp.getStatus());
        assertEquals("Pharma", stubOssie.ontologyConnection.get());
        assertEquals("Pharma", stubOssie.ontologyModel.get());
    }

    private String openSession() throws Exception {
        Response init = resource.handle(rpc("initialize", 1, MAPPER.createObjectNode()), null);
        return init.getHeaderString(SESSION_HEADER);
    }

    /** saiku#2322: a policy denial on the Ossie tools must surface as an isError tool result
     *  carrying the PERMISSION_DENIED envelope — not the generic -32603 "Internal error" branch
     *  (see also #1920's exception-echo concern), and not a successful empty result. */
    @Test
    public void runOssieQueryUnderSchemaOnlyReturnsPermissionDeniedToolError() throws Exception {
        org.saiku.web.rest.resources.AiOssieResource guarded = new org.saiku.web.rest.resources.AiOssieResource();
        guarded.setAiPolicyGuard(
                new org.saiku.service.olap.ai.AiPolicyGuard(org.saiku.service.olap.ai.AiPolicy.SCHEMA_ONLY));
        resource.setAiOssieResource(guarded);
        String sid = openSession();

        ObjectNode args = MAPPER.createObjectNode();
        args.put("connection", "Flights");
        args.putArray("rows").addObject().put("dataset", "flight").put("field", "carrier_id");
        args.putArray("values").addObject().put("metric", "flight_count");
        Response resp = resource.handle(rpc("tools/call", 4, callParams("run_ossie_query", args)), sid);
        JsonNode body = MAPPER.valueToTree(resp.getEntity());

        assertEquals("a policy denial is a tool-level result, not an RPC error", 200, resp.getStatus());
        assertNull("no JSON-RPC error envelope", body.get("error"));
        JsonNode result = body.path("result");
        assertTrue(result.path("isError").asBoolean());
        JsonNode structured = result.path("structuredContent");
        assertEquals("PERMISSION_DENIED", structured.path("status").asText());
        assertEquals("ai.policy", structured.path("field").asText());
        // run_ossie_query is the aggregate surface — schema-only must be told to raise to
        // 'aggregated' (row-detail's raw rows would say 'full').
        assertEquals("aggregated", structured.path("available").get(0).asText());
    }

    private ObjectNode callParams(String tool, ObjectNode args) {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", tool);
        params.set("arguments", args);
        return params;
    }

    /* -------- saiku#878: structuredContent must always be a JSON object -------- */

    @Test
    public void wrapToolResult_wrapsBareArrayUnderItems() {
        ArrayNode arr = MAPPER.createArrayNode();
        arr.add(MAPPER.createObjectNode().put("name", "Sales"));
        ObjectNode result = resource.wrapToolResult(arr);
        JsonNode sc = result.get("structuredContent");
        assertTrue("structuredContent must be a JSON object, never a bare array", sc.isObject());
        assertTrue(sc.has("items"));
        assertTrue(sc.get("items").isArray());
        assertEquals(1, sc.get("items").size());
    }

    @Test
    public void wrapToolResult_leavesObjectBodyUnchanged() {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("cube", "Sales");
        ObjectNode result = resource.wrapToolResult(body);
        JsonNode sc = result.get("structuredContent");
        assertTrue(sc.isObject());
        assertEquals("Sales", sc.get("cube").asText());
        assertFalse("object bodies are passed through untouched", sc.has("items"));
    }

    @Test
    public void wrapToolResult_nullBodyBecomesEmptyObject() {
        ObjectNode result = resource.wrapToolResult(null);
        assertTrue(result.get("structuredContent").isObject());
        assertEquals(0, result.get("structuredContent").size());
    }

    private static final class StubAiOssieResource extends org.saiku.web.rest.resources.AiOssieResource {
        Response ontologyResponse =
                Response.ok(java.util.Map.of("ontology", List.of())).build();
        final AtomicReference<String> ontologyConnection = new AtomicReference<>();
        final AtomicReference<String> ontologyModel = new AtomicReference<>();

        @Override
        public Response getOntology(String connection, String model) {
            ontologyConnection.set(connection);
            ontologyModel.set(model);
            return ontologyResponse;
        }
    }

    private static final class StubAiQueryResource extends AiQueryResource {
        Response cubesResponse = Response.ok(List.of()).build();
        RuntimeException listCubesFailure;
        Response executeResponse = Response.ok(new AiQueryResponse()).build();
        final AtomicReference<AiQueryRequest> executeRequest = new AtomicReference<>();
        final AtomicReference<String> executeFormat = new AtomicReference<>();

        final AtomicReference<Integer> searchLimit = new AtomicReference<>();

        Response drillthroughResponse;

        @Override
        public Response drillthrough(
                String queryId, int maxrows, Integer firstRowset, String position, String returns) {
            return drillthroughResponse == null
                    ? super.drillthrough(queryId, maxrows, firstRowset, position, returns)
                    : drillthroughResponse;
        }

        @Override
        public Response listCubes() {
            if (listCubesFailure != null) {
                throw listCubesFailure;
            }
            return cubesResponse;
        }

        @Override
        public Response searchMembers(
                String cubeId, String dimension, String hierarchy, String level, String q, int limit) {
            searchLimit.set(limit);
            return Response.ok(List.of()).build();
        }

        @Override
        public Response executeAi(AiQueryRequest req, String format) {
            executeRequest.set(req);
            executeFormat.set(format);
            // Defensive copy of the underlying entity to make sure the
            // resource isn't mutating its return value in flight — if it
            // does, the assertions above catch it. The stub must PRESERVE the
            // stubbed status: a non-2xx is the whole point of the error-mapping
            // tests (review of PR #2563 — Response.ok(entity) dropped the 400
            // and the failure never reached unwrap).
            Response orig = executeResponse;
            assertNotSame(orig, null);
            Object entity = orig.getEntity();
            if (entity instanceof AiQueryResponse) {
                AiQueryResponse src = (AiQueryResponse) entity;
                AiQueryResponse copy = new AiQueryResponse();
                copy.setQueryId(src.getQueryId());
                copy.setStatus(src.getStatus());
                return Response.status(orig.getStatus()).entity(copy).build();
            }
            return Response.status(orig.getStatus()).entity(entity).build();
        }
    }
}
