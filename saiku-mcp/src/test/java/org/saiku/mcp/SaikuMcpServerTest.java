/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Glue-layer tests for {@link SaikuMcpServer}. Exercises the
 * {@code jsonResult} adapter end-to-end against an embedded saiku
 * stub — verifies the MCP {@link CallToolResult} envelope is
 * well-formed for both success and failure paths, including the
 * {@code structuredContent} field and the {@code errorKind} tag added
 * for transport failures.
 *
 * <p>Doesn't drive the MCP stdio loop in a subprocess — that surface
 * is exercised manually via the README smoke test. Here we want the
 * fastest signal on the saiku-mcp ↔ saiku-rest seam.
 */
public class SaikuMcpServerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private SaikuRestClient client;

    @Before
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/login", ex -> {
            ex.getResponseHeaders().add("Set-Cookie", "JSESSIONID=ok; Path=/");
            ex.sendResponseHeaders(302, -1);
        });
        server.createContext("/rest/saiku/api/ai/cubes", ex -> {
            byte[] body = "[{\"cubeName\":\"Sales\"}]".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        client = new SaikuRestClient("http://127.0.0.1:" + server.getAddress().getPort(), "admin", "secret");
    }

    @After
    public void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    public void jsonResultWrapsSuccessfulResponseAsStructuredContent() throws Exception {
        CallToolResult r = SaikuMcpServer.jsonResult(() -> client.listCubes());

        assertFalse("success path leaves isError unset/false", Boolean.TRUE.equals(r.isError()));
        assertNotNull("structuredContent present on success", r.structuredContent());
        // MCP spec: structuredContent MUST be a JSON object. Array
        // bodies (list_cubes, search_members) get wrapped under
        // "items" so strict hosts (mcp-proxy's pydantic, Claude
        // Desktop) don't reject the envelope with a dict_type error.
        JsonNode parsed = MAPPER.valueToTree(r.structuredContent());
        assertTrue("structuredContent is an object", parsed.isObject());
        assertTrue("array body is exposed under .items", parsed.path("items").isArray());
        assertEquals("Sales", parsed.path("items").get(0).get("cubeName").asText());

        // Text content keeps the original (unwrapped) shape so agents
        // that read content[0] still see the array directly.
        assertEquals(1, r.content().size());
        McpSchema.Content c = r.content().get(0);
        assertTrue("text content is the JSON string form", c instanceof TextContent);
        JsonNode reparsed = MAPPER.readTree(((TextContent) c).text());
        assertTrue("text content stays unwrapped", reparsed.isArray());
        assertEquals("Sales", reparsed.get(0).get("cubeName").asText());
    }

    @Test
    public void jsonResultPassesObjectBodiesThroughUnwrapped() throws Exception {
        // Cube-shaped (object) responses — describe_cube, run_query,
        // preview_query, drillthrough — must NOT get the items wrapper,
        // otherwise existing agents break.
        com.fasterxml.jackson.databind.node.ObjectNode obj = MAPPER.createObjectNode();
        obj.put("cubeName", "Sales");
        obj.putArray("measures").add("Store Sales");

        CallToolResult r = SaikuMcpServer.jsonResult(() -> obj);

        JsonNode parsed = MAPPER.valueToTree(r.structuredContent());
        assertTrue("object body stays object", parsed.isObject());
        assertEquals("Sales", parsed.get("cubeName").asText());
        assertFalse("no spurious items wrapper on object bodies", parsed.has("items"));
    }

    @Test
    public void jsonResultTagsTransportFailuresWithErrorKind() {
        SaikuRestClient unreachable = new SaikuRestClient("http://127.0.0.1:1", "admin", "secret"); // port 1 = nothing
        CallToolResult r = SaikuMcpServer.jsonResult(() -> unreachable.listCubes());

        assertTrue("transport failures set isError", Boolean.TRUE.equals(r.isError()));
        JsonNode body = MAPPER.valueToTree(r.structuredContent());
        assertEquals("transport", body.get("errorKind").asText());
        assertEquals("ERROR", body.get("status").asText());
        assertNotNull("error message populated", body.get("error"));
    }

    @Test
    public void jsonResultTagsInternalFailuresDistinctFromTransport() {
        CallToolResult r = SaikuMcpServer.jsonResult(() -> {
            throw new IllegalArgumentException("synthetic");
        });

        assertTrue("internal errors set isError", Boolean.TRUE.equals(r.isError()));
        JsonNode body = MAPPER.valueToTree(r.structuredContent());
        // Differentiate from transport so the agent knows it's not a
        // "retry when saiku comes back" situation.
        assertEquals("internal", body.get("errorKind").asText());
        assertTrue(
                "error names the cause — got: " + body.get("error").asText(),
                body.get("error").asText().contains("synthetic"));
    }

    @Test
    public void jsonResultEmptyContentListProducesNonNullEnvelope() {
        // Sanity: builder doesn't barf on a degenerate handler. The
        // production code never calls jsonResult with a null supplier,
        // but we ensure the helper's contract is robust.
        CallToolResult r = SaikuMcpServer.jsonResult(() -> MAPPER.createObjectNode());
        assertNotNull(r);
        assertNotNull(r.content());
        assertEquals(1, r.content().size());
    }
}
