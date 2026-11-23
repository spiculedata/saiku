/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.mcp.outbound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Speaks JSON-RPC 2.0 streamable-http to a single outbound {@link McpOutboundServer} — the client
 * half of saiku#1425, mirroring the wire protocol {@code McpResource} already serves inbound
 * ({@code initialize} → {@code tools/list} / {@code tools/call}, {@code Mcp-Session-Id} header).
 *
 * <p><strong>Never throws.</strong> Every failure mode — connection refused, TLS error, timeout,
 * non-2xx, malformed JSON-RPC envelope, a tool result carrying {@code isError:true} — comes back as
 * a typed {@code ok=false} result with a short {@code error} string. This is deliberate: an
 * unreachable outbound server must make its tools silently ABSENT from the LLM's catalogue (per the
 * saiku#1425 acceptance criteria), never take down the ask flow with an exception.
 *
 * <p><strong>Auth-header injection is structurally impossible</strong>: the header name/value come
 * only from the {@link McpOutboundServer} record (admin-configured, decrypted server-side by {@link
 * McpOutboundServerParser}); nothing derived from a tool-call argument, a user question, or a JSON-RPC
 * response ever reaches {@link HttpRequest.Builder#header}.
 *
 * <p>Stateless / no session reuse across calls: every {@link #discoverTools} and {@link #callTool}
 * performs its own {@code initialize} handshake first to obtain a session id, then the target method,
 * then discards the session. Simpler and safer than caching sessions across a rescan interval at the
 * cost of one extra round-trip per call — acceptable given outbound calls are already bounded by the
 * chained-ask loop's step cap (default 4, see {@code AiAskService#maxSteps}).
 */
public final class McpOutboundClient {

    private static final Logger log = LoggerFactory.getLogger(McpOutboundClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MCP_PROTOCOL_VERSION = "2025-03-26";
    private static final String SESSION_HEADER = "Mcp-Session-Id";
    private static final String CLIENT_NAME = "saiku-dimsum";

    /** Hard cap on a discovered/echoed tool result body — a misbehaving server can't blow the prompt budget. */
    static final int MAX_RESULT_CHARS = 20_000;

    private final HttpClient http;
    private final Duration requestTimeout;

    public McpOutboundClient() {
        this(defaultHttpClient(), Duration.ofSeconds(10));
    }

    public McpOutboundClient(Duration requestTimeout) {
        this(defaultHttpClient(), requestTimeout);
    }

    /** Package-visible ctor for tests that need to inject a fake client. */
    McpOutboundClient(HttpClient http, Duration requestTimeout) {
        this.http = http;
        this.requestTimeout = requestTimeout == null ? Duration.ofSeconds(10) : requestTimeout;
    }

    private static HttpClient defaultHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** One tool advertised by {@code tools/list} on the remote server. */
    public record RemoteTool(String name, String description, String inputSchemaJson) {}

    /** Outcome of a discovery round-trip. Never throws — check {@link #ok()} first. */
    public record DiscoveryResult(boolean ok, List<RemoteTool> tools, String error) {
        static DiscoveryResult success(List<RemoteTool> tools) {
            return new DiscoveryResult(true, List.copyOf(tools), null);
        }

        static DiscoveryResult failure(String error) {
            return new DiscoveryResult(false, List.of(), error);
        }
    }

    /** Outcome of a {@code tools/call} round-trip. Never throws — check {@link #ok()} first. */
    public record CallResult(boolean ok, String resultText, String error) {
        static CallResult success(String resultText) {
            return new CallResult(true, truncate(resultText), null);
        }

        static CallResult failure(String error) {
            return new CallResult(false, null, error);
        }
    }

    /**
     * Discover the tools a server currently advertises. Called by {@link McpOutboundToolCatalog} on
     * refresh; a failure here means the server's tools are simply absent from the aggregated
     * catalogue this cycle — not a hard error surfaced to the ask flow.
     */
    public DiscoveryResult discoverTools(McpOutboundServer server) {
        String sessionId;
        try {
            sessionId = initialize(server);
        } catch (McpTransportException e) {
            log.info("outbound MCP discovery: {} unreachable during initialize: {}", server.id(), e.getMessage());
            return DiscoveryResult.failure(e.getMessage());
        }

        ObjectNode req = rpcRequest("tools/list", MAPPER.createObjectNode());
        JsonNode result;
        try {
            result = send(server, req, sessionId);
        } catch (McpTransportException e) {
            log.info("outbound MCP discovery: {} tools/list failed: {}", server.id(), e.getMessage());
            return DiscoveryResult.failure(e.getMessage());
        }
        JsonNode toolsNode = result.path("tools");
        if (!toolsNode.isArray()) {
            return DiscoveryResult.failure("tools/list response missing 'tools' array");
        }
        List<RemoteTool> tools = new ArrayList<>();
        for (JsonNode t : toolsNode) {
            String name = t.path("name").asText(null);
            if (name == null || name.isBlank()) {
                continue; // malformed entry — skip rather than fail the whole discovery
            }
            String description = t.path("description").asText("");
            JsonNode schema = t.path("inputSchema");
            String schemaJson;
            try {
                schemaJson = schema.isMissingNode() || schema.isNull()
                        ? "{\"type\":\"object\"}"
                        : MAPPER.writeValueAsString(schema);
            } catch (IOException e) {
                schemaJson = "{\"type\":\"object\"}";
            }
            tools.add(new RemoteTool(name, description, schemaJson));
        }
        return DiscoveryResult.success(tools);
    }

    /**
     * Invoke {@code toolName} with {@code argumentsJson} (a JSON object string — the model's emitted
     * tool-call arguments, forwarded verbatim as {@code params.arguments}). Text content blocks in
     * the {@code tools/call} result are concatenated; a result carrying {@code isError:true} is
     * surfaced as a failure so the ask loop can feed the model an honest "the tool failed" digest
     * rather than silently treating an error body as data.
     */
    public CallResult callTool(McpOutboundServer server, String toolName, String argumentsJson) {
        String sessionId;
        try {
            sessionId = initialize(server);
        } catch (McpTransportException e) {
            return CallResult.failure("server unreachable: " + e.getMessage());
        }

        JsonNode arguments;
        try {
            arguments = (argumentsJson == null || argumentsJson.isBlank())
                    ? MAPPER.createObjectNode()
                    : MAPPER.readTree(argumentsJson);
        } catch (IOException e) {
            return CallResult.failure("invalid tool arguments JSON");
        }

        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", arguments);
        ObjectNode req = rpcRequest("tools/call", params);

        JsonNode result;
        try {
            result = send(server, req, sessionId);
        } catch (McpTransportException e) {
            return CallResult.failure(e.getMessage());
        }

        boolean isError = result.path("isError").asBoolean(false);
        String text = extractText(result);
        if (isError) {
            return CallResult.failure(text.isBlank() ? "tool reported an error" : text);
        }
        return CallResult.success(text.isBlank() ? result.toString() : text);
    }

    /* ------------------------------ JSON-RPC plumbing ------------------------------ */

    private String initialize(McpOutboundServer server) throws McpTransportException {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("protocolVersion", MCP_PROTOCOL_VERSION);
        ObjectNode capabilities = params.putObject("capabilities");
        capabilities.putObject("tools");
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", CLIENT_NAME);
        clientInfo.put("version", "1.0");
        ObjectNode req = rpcRequest("initialize", params);

        HttpResponse<String> resp = post(server, req, null);
        JsonNode body = parseEnvelope(resp);
        // The remote's own session id, echoed back on the initialize response header — required by
        // the streamable-http transport for every subsequent call in this short-lived session.
        String sessionId = resp.headers().firstValue(SESSION_HEADER).orElse(null);
        checkRpcError(body);
        return sessionId;
    }

    private JsonNode send(McpOutboundServer server, ObjectNode request, String sessionId) throws McpTransportException {
        HttpResponse<String> resp = post(server, request, sessionId);
        JsonNode body = parseEnvelope(resp);
        checkRpcError(body);
        return body.path("result");
    }

    private HttpResponse<String> post(McpOutboundServer server, ObjectNode request, String sessionId)
            throws McpTransportException {
        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder()
                    .uri(URI.create(server.url()))
                    .timeout(requestTimeout)
                    .header("content-type", "application/json")
                    .header("accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(request)));
        } catch (IOException | IllegalArgumentException e) {
            throw new McpTransportException("could not build request: " + e.getMessage());
        }
        // The ONLY place server-configured credentials touch the outbound request. Never derived
        // from request/arguments/response data — see the class javadoc.
        if (server.hasAuthHeader()) {
            builder.header(server.authHeaderName(), server.authHeaderValue());
        }
        if (sessionId != null && !sessionId.isBlank()) {
            builder.header(SESSION_HEADER, sessionId);
        }
        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new McpTransportException("transport error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpTransportException("interrupted");
        }
    }

    private JsonNode parseEnvelope(HttpResponse<String> resp) throws McpTransportException {
        if (resp.statusCode() / 100 != 2) {
            throw new McpTransportException("HTTP " + resp.statusCode());
        }
        try {
            JsonNode root = MAPPER.readTree(resp.body());
            if (root == null || !root.isObject()) {
                throw new McpTransportException("response was not a JSON object");
            }
            return root;
        } catch (IOException e) {
            throw new McpTransportException("malformed JSON-RPC response");
        }
    }

    private void checkRpcError(JsonNode envelope) throws McpTransportException {
        JsonNode error = envelope.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            String message = error.path("message").asText("JSON-RPC error");
            throw new McpTransportException(message);
        }
    }

    private ObjectNode rpcRequest(String method, JsonNode params) {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", UUID.randomUUID().toString());
        req.put("method", method);
        req.set("params", params);
        return req;
    }

    /** Concatenate every {@code type:"text"} content block, MCP's {@code CallToolResult} shape. */
    private String extractText(JsonNode result) {
        JsonNode content = result.path("content");
        if (!content.isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : content) {
            if ("text".equals(block.path("type").asText())) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(block.path("text").asText(""));
            }
        }
        return sb.toString();
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > MAX_RESULT_CHARS ? s.substring(0, MAX_RESULT_CHARS) + "…(truncated)" : s;
    }

    /** Internal-only transport failure — never propagated past this class's public methods. */
    private static final class McpTransportException extends Exception {
        McpTransportException(String message) {
            super(message);
        }
    }
}
