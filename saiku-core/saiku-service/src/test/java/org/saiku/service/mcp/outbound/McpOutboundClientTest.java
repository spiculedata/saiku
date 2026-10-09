/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.mcp.outbound;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import org.junit.Test;
import org.saiku.service.mcp.outbound.ScriptedHttpClient.Resp;

/**
 * {@link McpOutboundClient} exercised entirely against {@link ScriptedHttpClient} — no network.
 * Every public method (discover, call) performs exactly two round-trips in sequence ({@code
 * initialize} then the target JSON-RPC method); the fake returns queued responses in that order.
 *
 * <p>Covers the saiku#1425 acceptance criteria directly: a malformed remote response, a timeout, and
 * proof the configured auth header can't be influenced by anything the model or the remote server
 * sends.
 */
public class McpOutboundClientTest {

    private static final McpOutboundServer PLAIN_SERVER =
            new McpOutboundServer("srv", "Server", "https://example.com/mcp", null, null, Set.of("read_file"), "x");

    private static final McpOutboundServer AUTH_SERVER = new McpOutboundServer(
            "srv",
            "Server",
            "https://example.com/mcp",
            "Authorization",
            "Bearer secret-token",
            Set.of("read_file"),
            "x");

    private static final String INIT_OK = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{"
            + "\"protocolVersion\":\"2025-03-26\",\"capabilities\":{\"tools\":{}},"
            + "\"serverInfo\":{\"name\":\"remote\",\"version\":\"1.0\"}}}";

    /* -------------------------------- discoverTools -------------------------------- */

    @Test
    public void discoverToolsHappyPath() {
        String toolsList = "{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"result\":{\"tools\":["
                + "{\"name\":\"read_file\",\"description\":\"Read a file\","
                + "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}},"
                + "{\"name\":\"list_directory\",\"description\":\"List a dir\"}"
                + "]}}";
        ScriptedHttpClient http =
                ScriptedHttpClient.of(Resp.ok(INIT_OK).withHeader("Mcp-Session-Id", "sess-123"), Resp.ok(toolsList));
        McpOutboundClient client = new McpOutboundClient(http, Duration.ofSeconds(5));

        McpOutboundClient.DiscoveryResult result = client.discoverTools(PLAIN_SERVER);

        assertTrue(result.ok());
        assertNull(result.error());
        assertEquals(2, result.tools().size());
        assertEquals("read_file", result.tools().get(0).name());
        assertEquals("Read a file", result.tools().get(0).description());
        assertTrue(result.tools().get(0).inputSchemaJson().contains("path"));
        // Missing inputSchema on the second entry falls back to a bare object schema.
        assertEquals("{\"type\":\"object\"}", result.tools().get(1).inputSchemaJson());

        // The session id from initialize's response header rides on the follow-up request.
        assertEquals(2, http.requests.size());
        assertEquals(
                "sess-123",
                http.requests.get(1).headers().firstValue("Mcp-Session-Id").orElse(null));
    }

    @Test
    public void discoverToolsSkipsEntriesMissingName() {
        String toolsList = "{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"result\":{\"tools\":["
                + "{\"description\":\"no name field\"},"
                + "{\"name\":\"ok_tool\",\"description\":\"fine\"}"
                + "]}}";
        ScriptedHttpClient http = ScriptedHttpClient.of(Resp.ok(INIT_OK), Resp.ok(toolsList));
        McpOutboundClient client = new McpOutboundClient(http, Duration.ofSeconds(5));

        McpOutboundClient.DiscoveryResult result = client.discoverTools(PLAIN_SERVER);

        assertTrue(result.ok());
        assertEquals(1, result.tools().size());
        assertEquals("ok_tool", result.tools().get(0).name());
    }

    @Test
    public void discoverToolsFailsOnNon2xxInitialize() {
        ScriptedHttpClient http = ScriptedHttpClient.of(Resp.status(500, "internal error"));
        McpOutboundClient client = new McpOutboundClient(http, Duration.ofSeconds(5));

        McpOutboundClient.DiscoveryResult result = client.discoverTools(PLAIN_SERVER);

        assertFalse(result.ok());
        assertTrue(result.tools().isEmpty());
        assertTrue(result.error().contains("500"));
    }

    @Test
    public void discoverToolsFailsOnMalformedJsonResponse() {
        ScriptedHttpClient http = ScriptedHttpClient.of(Resp.ok(INIT_OK), Resp.ok("this is not json"));
        McpOutboundClient client = new McpOutboundClient(http, Duration.ofSeconds(5));

        McpOutboundClient.DiscoveryResult result = client.discoverTools(PLAIN_SERVER);

        assertFalse(result.ok());
        assertTrue(result.tools().isEmpty());
    }

    @Test
    public void discoverToolsFailsOnMissingToolsArray() {
        ScriptedHttpClient http =
                ScriptedHttpClient.of(Resp.ok(INIT_OK), Resp.ok("{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"result\":{}}"));
        McpOutboundClient client = new McpOutboundClient(http, Duration.ofSeconds(5));

        McpOutboundClient.DiscoveryResult result = client.discoverTools(PLAIN_SERVER);

        assertFalse(result.ok());
    }

    @Test
    public void discoverToolsFailsOnJsonRpcErrorEnvelope() {
        String rpcError =
                "{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"error\":{\"code\":-32601,\"message\":\"method not found\"}}";
        ScriptedHttpClient http = ScriptedHttpClient.of(Resp.ok(INIT_OK), Resp.ok(rpcError));
        McpOutboundClient client = new McpOutboundClient(http, Duration.ofSeconds(5));

        McpOutboundClient.DiscoveryResult result = client.discoverTools(PLAIN_SERVER);

        assertFalse(result.ok());
        assertEquals("method not found", result.error());
    }

    @Test
    public void discoverToolsFailsOnTimeoutWithoutThrowing() {
        ScriptedHttpClient http = ScriptedHttpClient.throwing(new HttpTimeoutException("request timed out"));
        McpOutboundClient client = new McpOutboundClient(http, Duration.ofSeconds(1));

        // Must never propagate — the whole point is "unreachable → tool absent", not an exception
        // reaching the ask flow.
        McpOutboundClient.DiscoveryResult result = client.discoverTools(PLAIN_SERVER);

        assertFalse(result.ok());
        assertTrue(result.error().toLowerCase(Locale.ROOT).contains("timed out"));
    }

    /* ----------------------------------- callTool ----------------------------------- */

    @Test
    public void callToolHappyPath() {
        String callResult =
                "{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"hello world\"}],"
                        + "\"isError\":false}}";
        ScriptedHttpClient http = ScriptedHttpClient.of(Resp.ok(INIT_OK), Resp.ok(callResult));
        McpOutboundClient client = new McpOutboundClient(http, Duration.ofSeconds(5));

        McpOutboundClient.CallResult result = client.callTool(PLAIN_SERVER, "read_file", "{\"path\":\"/tmp/x\"}");

        assertTrue(result.ok());
        assertEquals("hello world", result.resultText());
        assertNull(result.error());
    }

    @Test
    public void callToolSurfacesRemoteToolError() {
        String callResult = "{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"result\":{\"isError\":true,"
                + "\"content\":[{\"type\":\"text\",\"text\":\"file not found\"}]}}";
        ScriptedHttpClient http = ScriptedHttpClient.of(Resp.ok(INIT_OK), Resp.ok(callResult));
        McpOutboundClient client = new McpOutboundClient(http, Duration.ofSeconds(5));

        McpOutboundClient.CallResult result = client.callTool(PLAIN_SERVER, "read_file", "{}");

        assertFalse(result.ok());
        assertEquals("file not found", result.error());
        assertNull(result.resultText());
    }

    @Test
    public void callToolRejectsInvalidArgumentsJson() {
        // initialize still happens (arguments are only validated after the handshake); the
        // tools/call response is never consumed since argument parsing fails first.
        ScriptedHttpClient http = ScriptedHttpClient.of(Resp.ok(INIT_OK));
        McpOutboundClient client = new McpOutboundClient(http, Duration.ofSeconds(5));

        McpOutboundClient.CallResult result = client.callTool(PLAIN_SERVER, "read_file", "not json");

        assertFalse(result.ok());
        assertTrue(result.error().toLowerCase(Locale.ROOT).contains("invalid"));
    }

    @Test
    public void callToolTruncatesOversizedResult() {
        String huge = "x".repeat(McpOutboundClient.MAX_RESULT_CHARS + 5_000);
        String callResult = "{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\""
                + huge + "\"}]}}";
        ScriptedHttpClient http = ScriptedHttpClient.of(Resp.ok(INIT_OK), Resp.ok(callResult));
        McpOutboundClient client = new McpOutboundClient(http, Duration.ofSeconds(5));

        McpOutboundClient.CallResult result = client.callTool(PLAIN_SERVER, "read_file", "{}");

        assertTrue(result.ok());
        assertTrue(result.resultText().length() <= McpOutboundClient.MAX_RESULT_CHARS + 20);
        assertTrue(result.resultText().endsWith("(truncated)"));
    }

    @Test
    public void callToolFailsCleanlyWhenServerUnreachable() {
        ScriptedHttpClient http = ScriptedHttpClient.throwing(new IOException("connection refused"));
        McpOutboundClient client = new McpOutboundClient(http, Duration.ofSeconds(5));

        McpOutboundClient.CallResult result = client.callTool(PLAIN_SERVER, "read_file", "{}");

        assertFalse(result.ok());
        assertNull(result.resultText());
    }

    /* --------------------------- auth-header injection safety --------------------------- */

    @Test
    public void configuredAuthHeaderIsSentOnEveryRequest() {
        String callResult =
                "{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}}";
        ScriptedHttpClient http = ScriptedHttpClient.of(Resp.ok(INIT_OK), Resp.ok(callResult));
        McpOutboundClient client = new McpOutboundClient(http, Duration.ofSeconds(5));

        client.callTool(AUTH_SERVER, "read_file", "{}");

        assertEquals(2, http.requests.size());
        for (HttpRequest req : http.requests) {
            assertEquals(
                    "Bearer secret-token",
                    req.headers().firstValue("Authorization").orElse(null));
        }
    }

    @Test
    public void toolArgumentsCannotInjectOrOverrideTheAuthHeader() {
        String callResult =
                "{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}}";
        ScriptedHttpClient http = ScriptedHttpClient.of(Resp.ok(INIT_OK), Resp.ok(callResult));
        McpOutboundClient client = new McpOutboundClient(http, Duration.ofSeconds(5));

        // A model-controlled (or attacker-controlled) argument payload that TRIES to look like it
        // carries header material. It must land only inside the JSON-RPC params body, never as an
        // actual HTTP header, and must never override the server's configured credential.
        String maliciousArgs = "{\"Authorization\":\"Bearer attacker-supplied\","
                + "\"authHeaderValue\":\"Bearer attacker-supplied\",\"path\":\"/etc/passwd\"}";
        client.callTool(AUTH_SERVER, "read_file", maliciousArgs);

        HttpRequest toolsCallReq = http.requests.get(1);
        assertEquals(
                "the server's configured credential must win, unaffected by argument content",
                "Bearer secret-token",
                toolsCallReq.headers().firstValue("Authorization").orElse(null));
        // Exactly one Authorization header value — no duplicate/second header sneaked in.
        assertEquals(1, toolsCallReq.headers().allValues("Authorization").size());
    }

    @Test
    public void noAuthHeaderSentWhenServerHasNoCredential() {
        ScriptedHttpClient http =
                ScriptedHttpClient.of(Resp.ok(INIT_OK), Resp.ok("{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"result\":{}}"));
        McpOutboundClient client = new McpOutboundClient(http, Duration.ofSeconds(5));

        client.discoverTools(PLAIN_SERVER);

        for (HttpRequest req : http.requests) {
            assertTrue(req.headers().allValues("Authorization").isEmpty());
        }
    }
}
