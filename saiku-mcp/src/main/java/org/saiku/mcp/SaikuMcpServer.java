/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.BiFunction;

/**
 * Saiku MCP server — entry point.
 *
 * <p>Wraps Saiku's {@code /saiku/api/ai/*} REST surface as an MCP tool
 * surface for LLM agents. Per {@code docs/MCP-SERVER-SPEC.md}.
 * Stateless: every tool call is one MCP request → one REST call → one
 * response. No client-side schema cache, no query session memory.
 *
 * <p>Transport: stdio only. Reads MCP JSON-RPC frames from stdin, writes
 * responses to stdout. SLF4J logging is routed to stderr so it doesn't
 * collide with the JSON-RPC framing on stdout.
 *
 * <p>Config (env vars):
 * <ul>
 *   <li>{@code SAIKU_URL}  — default {@code http://localhost:8080}</li>
 *   <li>{@code SAIKU_USER} — default {@code admin}</li>
 *   <li>{@code SAIKU_PASS} — default {@code admin}</li>
 * </ul>
 */
public final class SaikuMcpServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SaikuMcpServer() {}

    public static void main(String[] args) throws InterruptedException {
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(MAPPER);
        StdioServerTransportProvider transport = new StdioServerTransportProvider(jsonMapper);

        SaikuRestClient client = SaikuRestClient.fromEnv(System.getenv());

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("saiku-mcp", "4.0.1")
                .capabilities(ServerCapabilities.builder().tools(true).build())
                .tools(
                        listCubesTool(jsonMapper, client),
                        describeCubeTool(jsonMapper, client),
                        searchMembersTool(jsonMapper, client),
                        runQueryTool(jsonMapper, client),
                        previewQueryTool(jsonMapper, client),
                        drillthroughTool(jsonMapper, client))
                .build();

        Runtime.getRuntime().addShutdownHook(new Thread(server::closeGracefully));
        // The MCP SDK runs its transport on internal NIO threads; main()
        // returning would otherwise let the JVM exit before any frame
        // gets handled. Park on an indefinite latch until SIGTERM /
        // stdin EOF closes the process.
        new CountDownLatch(1).await();
    }

    /* ----------------------- tool definitions ----------------------- */

    private static SyncToolSpecification listCubesTool(McpJsonMapper jm, SaikuRestClient client) {
        String schema = "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";
        Tool tool = Tool.builder()
                .name("list_cubes")
                .description("List every OLAP cube the current user can query. Use this first when you don't know "
                        + "what data is available — each entry has a one-line caption and the cube's default measure, "
                        + "which is usually enough to pick the right cube without describing each one. Returns at most "
                        + "a few dozen entries; not paginated.")
                .inputSchema(jm, schema)
                .build();
        return spec(tool, (exchange, request) -> jsonResult(() -> client.listCubes()));
    }

    private static SyncToolSpecification describeCubeTool(McpJsonMapper jm, SaikuRestClient client) {
        String schema = "{\"type\":\"object\",\"required\":[\"cube\"],"
                + "\"properties\":{\"cube\":{\"type\":\"string\","
                + "\"description\":\"The cube id from list_cubes: connection/catalog/schema/cubeName.\"}},"
                + "\"additionalProperties\":false}";
        Tool tool = Tool.builder()
                .name("describe_cube")
                .description("Get the complete queryable structure of one cube: measures, dimensions, hierarchies, "
                        + "levels, and sample members with their MDX unique names. Always call this before run_query "
                        + "if you haven't seen the cube structure yet — it tells you exactly which names are valid "
                        + "and includes ready-made example query bodies. Sample members include both the human "
                        + "caption and the MDX unique name, so you can copy the unique name directly into a filter.")
                .inputSchema(jm, schema)
                .build();
        return spec(tool, (exchange, request) -> {
            String cubeId = stringArg(request.arguments(), "cube");
            return jsonResult(() -> client.describeCube(cubeId));
        });
    }

    private static SyncToolSpecification searchMembersTool(McpJsonMapper jm, SaikuRestClient client) {
        String schema = "{\"type\":\"object\",\"required\":[\"cube\",\"dimension\",\"level\"],"
                + "\"properties\":{"
                + "\"cube\":{\"type\":\"string\"},"
                + "\"dimension\":{\"type\":\"string\"},"
                + "\"hierarchy\":{\"type\":\"string\",\"description\":\"Optional when the dimension has only one hierarchy.\"},"
                + "\"level\":{\"type\":\"string\"},"
                + "\"q\":{\"type\":\"string\",\"description\":\"Optional substring filter. Omit to list all members on the level up to limit.\"},"
                + "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":500,\"default\":20}"
                + "},\"additionalProperties\":false}";
        Tool tool = Tool.builder()
                .name("search_members")
                .description("Find the MDX unique names of members on a level by substring match. Use when the cube "
                        + "has more members at a level than describe_cube's sampleMembers covered (e.g. searching "
                        + "for a specific city, customer, or product brand), or when the user says \"filter by Italy\" "
                        + "and you need to confirm the spelling. Returns up to limit hits with caption + uniqueName.")
                .inputSchema(jm, schema)
                .build();
        return spec(tool, (exchange, request) -> {
            Map<String, Object> a = request.arguments();
            return jsonResult(() -> client.searchMembers(
                    stringArg(a, "cube"),
                    stringArg(a, "dimension"),
                    optString(a, "hierarchy"),
                    stringArg(a, "level"),
                    optString(a, "q"),
                    optInt(a, "limit")));
        });
    }

    private static SyncToolSpecification runQueryTool(McpJsonMapper jm, SaikuRestClient client) {
        // The full AiQueryRequest schema lives server-side and is too large
        // to embed inline; agents that need the precise shape should call
        // describe_cube and read the requestSchema field. This input
        // schema is permissive — required cube + measures, everything
        // else passes through.
        String schema = "{\"type\":\"object\",\"required\":[\"cube\",\"measures\"],"
                + "\"properties\":{"
                + "\"cube\":{\"description\":\"Cube id (string from list_cubes) or {connectionName,catalog,schema,cubeName} object.\"},"
                + "\"measures\":{\"type\":\"array\",\"minItems\":1,\"items\":{\"type\":\"object\",\"required\":[\"name\"],\"properties\":{\"name\":{\"type\":\"string\"}}}},"
                + "\"rows\":{\"type\":\"array\"},"
                + "\"columns\":{\"type\":\"array\"},"
                + "\"filters\":{\"type\":\"array\"},"
                + "\"order\":{\"type\":\"array\"},"
                + "\"limit\":{\"type\":\"integer\"},"
                + "\"visualTotals\":{\"type\":\"boolean\"},"
                + "\"nonEmpty\":{\"type\":\"boolean\"},"
                + "\"format\":{\"type\":\"string\",\"enum\":[\"records\",\"matrix\"],\"description\":\"Default records.\"}"
                + "},\"additionalProperties\":true}";
        Tool tool = Tool.builder()
                .name("run_query")
                .description("Run an OLAP analytical query and get the results as records. This is the primary tool — "
                        + "most user questions land here. Build the request against the cube structure from "
                        + "describe_cube; the server validates every name and returns a 400 with a list of valid "
                        + "alternatives if any name is wrong, so don't pre-validate yourself. Default output is records "
                        + "(one object per row, keyed by column captions); pass format:\"matrix\" only if you need "
                        + "the position-indexed shape.")
                .inputSchema(jm, schema)
                .build();
        return spec(tool, (exchange, request) -> {
            Map<String, Object> a = request.arguments();
            String format = optString(a, "format");
            ObjectNode body = MAPPER.valueToTree(a);
            // 'format' is a query-string param, not a body field; strip it
            // before forwarding to keep the saiku validator happy.
            body.remove("format");
            return jsonResult(() -> client.runQuery(body, format));
        });
    }

    private static SyncToolSpecification previewQueryTool(McpJsonMapper jm, SaikuRestClient client) {
        // Same input shape as run_query, minus the format switch — preview
        // doesn't render rows.
        String schema = "{\"type\":\"object\",\"required\":[\"cube\",\"measures\"],"
                + "\"properties\":{"
                + "\"cube\":{},\"measures\":{\"type\":\"array\"},\"rows\":{\"type\":\"array\"},"
                + "\"columns\":{\"type\":\"array\"},\"filters\":{\"type\":\"array\"},"
                + "\"order\":{\"type\":\"array\"},\"limit\":{\"type\":\"integer\"},"
                + "\"visualTotals\":{\"type\":\"boolean\"},\"nonEmpty\":{\"type\":\"boolean\"}"
                + "},\"additionalProperties\":true}";
        Tool tool = Tool.builder()
                .name("preview_query")
                .description("Compile a query to MDX without executing it. Use when you want to show the user what "
                        + "the query will do, audit a generated query, or estimate cost before running an expensive "
                        + "aggregation. Validation runs the same as run_query — preview will return the same "
                        + "VALIDATION_ERROR shape if names don't resolve.")
                .inputSchema(jm, schema)
                .build();
        return spec(tool, (exchange, request) -> {
            ObjectNode body = MAPPER.valueToTree(request.arguments());
            return jsonResult(() -> client.previewQuery(body));
        });
    }

    private static SyncToolSpecification drillthroughTool(McpJsonMapper jm, SaikuRestClient client) {
        String schema = "{\"type\":\"object\",\"required\":[\"queryId\"],"
                + "\"properties\":{"
                + "\"queryId\":{\"type\":\"string\",\"description\":\"queryId returned by a prior run_query call.\"},"
                + "\"maxrows\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":10000,\"default\":100},"
                + "\"returns\":{\"type\":\"string\",\"description\":\"Optional comma-separated column list to project a subset.\"}"
                + "},\"additionalProperties\":false}";
        Tool tool = Tool.builder()
                .name("drillthrough")
                .description("Fetch the raw fact-table rows behind a specific query. Use when the user asks "
                        + "\"show me the underlying transactions\" or wants to inspect detail for a single cell. "
                        + "Pass the queryId returned by an earlier run_query call. Cells in the response are the "
                        + "same typed envelope as run_query — numeric warehouse columns get a parsed value.")
                .inputSchema(jm, schema)
                .build();
        return spec(tool, (exchange, request) -> {
            Map<String, Object> a = request.arguments();
            return jsonResult(() -> client.drillthrough(
                    stringArg(a, "queryId"), optInt(a, "maxrows"), optString(a, "returns")));
        });
    }

    /* ----------------------- helpers ----------------------- */

    private interface JsonSupplier {
        JsonNode get() throws Exception;
    }

    /** Wrap a REST call so any exception becomes a structured tool-error
     *  body (the agent prefers a JSON error to an MCP-protocol error —
     *  it can self-correct from a parsed envelope). */
    private static CallToolResult jsonResult(JsonSupplier supplier) {
        try {
            JsonNode body = supplier.get();
            return CallToolResult.builder()
                    .content(List.of(new TextContent(MAPPER.writeValueAsString(body))))
                    .build();
        } catch (Exception e) {
            ObjectNode err = MAPPER.createObjectNode();
            err.put("status", "ERROR");
            err.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            try {
                return CallToolResult.builder()
                        .content(List.of(new TextContent(MAPPER.writeValueAsString(err))))
                        .isError(true)
                        .build();
            } catch (Exception inner) {
                return CallToolResult.builder()
                        .content(List.of(new TextContent("error: " + e.getMessage())))
                        .isError(true)
                        .build();
            }
        }
    }

    private static SyncToolSpecification spec(
            Tool tool,
            BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, CallToolResult> handler) {
        return SyncToolSpecification.builder().tool(tool).callHandler(handler).build();
    }

    private static String stringArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) throw new IllegalArgumentException("Missing required argument: " + key);
        return v.toString();
    }

    private static String optString(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v == null ? null : v.toString();
    }

    private static Integer optInt(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        return Integer.parseInt(v.toString());
    }
}
