/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.saiku.service.olap.ai.AiQueryRequest;
import org.saiku.web.rest.resources.AiQueryResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Native streamable-http MCP endpoint. Replaces the standalone saiku-mcp
 * stdio binary by speaking JSON-RPC directly inside saiku-webapp, so every
 * call traverses the same Spring Security chain as the AI Query REST API —
 * one auth model, one role-propagation path, no separate process. Issue #878.
 *
 * <p>Six tools are exposed today, all delegating to {@link AiQueryResource}:
 * <ul>
 *   <li>{@code list_cubes}      → {@link AiQueryResource#listCubes()}</li>
 *   <li>{@code describe_cube}   → {@link AiQueryResource#getSchema(String)}</li>
 *   <li>{@code search_members}  → {@link AiQueryResource#searchMembers}</li>
 *   <li>{@code run_query}       → {@link AiQueryResource#executeAi}</li>
 *   <li>{@code preview_query}   → {@link AiQueryResource#previewAi}</li>
 *   <li>{@code drillthrough}    → {@link AiQueryResource#drillthrough}</li>
 * </ul>
 *
 * <p>JSON-RPC framing only — no SSE in the first cut. Server-initiated
 * notifications would need {@code text/event-stream}; everything here is
 * synchronous request/response. Sessions live in {@link McpSessionStore}.
 *
 * <p>The auth chain configured in {@code applicationContext-saiku.xml}
 * rejects unauthenticated requests with 401 before they reach this method,
 * so {@code initialize} requires credentials too — no anonymous handshake.
 */
@Path("/saiku/api/mcp")
public class McpResource {

    private static final Logger log = LoggerFactory.getLogger(McpResource.class);
    private static final ObjectMapper MAPPER =
            new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private static final String SERVER_NAME = "saiku";
    private static final String MCP_PROTOCOL_VERSION = "2025-03-26";
    private static final String SESSION_HEADER = "Mcp-Session-Id";
    /** JSON-RPC 2.0 error codes (spec §5.1). */
    private static final int RPC_INVALID_REQUEST = -32600;

    private static final int RPC_METHOD_NOT_FOUND = -32601;
    private static final int RPC_INVALID_PARAMS = -32602;
    private static final int RPC_INTERNAL_ERROR = -32603;

    private AiQueryResource aiQueryResource;
    private org.saiku.web.rest.resources.AiOssieResource aiOssieResource;
    private McpSessionStore sessionStore;

    public void setAiQueryResource(AiQueryResource r) {
        this.aiQueryResource = r;
    }

    public void setAiOssieResource(org.saiku.web.rest.resources.AiOssieResource r) {
        this.aiOssieResource = r;
    }

    public void setSessionStore(McpSessionStore s) {
        this.sessionStore = s;
    }

    /**
     * Streamable-http MCP entry point. Accepts a single JSON-RPC frame and
     * returns the matching response (or 202 for notification frames, per
     * the JSON-RPC 2.0 spec — notifications carry no {@code id} and must
     * not produce a response body).
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response handle(JsonNode request, @HeaderParam(SESSION_HEADER) String sessionId) {
        if (request == null || !request.isObject()) {
            return rpcError(null, RPC_INVALID_REQUEST, "Request body must be a JSON-RPC object", null);
        }
        JsonNode idNode = request.get("id");
        String method = textOrNull(request.get("method"));
        if (method == null) {
            return rpcError(idNode, RPC_INVALID_REQUEST, "Missing required field: method", null);
        }
        JsonNode params = request.get("params");

        // Notifications (no id) carry side-effects only — accept and ack
        // with 202 per the streamable-http spec. We don't currently act on
        // any (initialized is the only one Claude Desktop sends), but
        // returning a JSON-RPC response for a notification is a protocol
        // violation that some hosts reject.
        boolean isNotification = idNode == null || idNode.isNull();
        if (isNotification) {
            return Response.status(Response.Status.ACCEPTED).build();
        }

        try {
            switch (method) {
                case "initialize":
                    return handleInitialize(idNode, params);
                case "ping":
                    return rpcResult(idNode, MAPPER.createObjectNode(), sessionId);
                case "tools/list":
                    return rpcResult(idNode, buildToolsList(), requireSession(sessionId));
                case "tools/call":
                    return handleToolsCall(idNode, params, requireSession(sessionId));
                default:
                    return rpcError(idNode, RPC_METHOD_NOT_FOUND, "Method not found: " + method, sessionId);
            }
        } catch (SessionRequiredException e) {
            return rpcError(idNode, RPC_INVALID_REQUEST, e.getMessage(), null);
        } catch (RuntimeException e) {
            log.error("MCP request handling failed for method={}", method, e);
            return rpcError(idNode, RPC_INTERNAL_ERROR, "Internal error: " + e.getMessage(), sessionId);
        }
    }

    /**
     * Optional explicit session teardown. Streamable-http hosts MAY send a
     * DELETE with the {@code Mcp-Session-Id} header to drop server state
     * when the client process exits cleanly. No-op if the id is unknown.
     */
    @DELETE
    public Response deleteSession(@HeaderParam(SESSION_HEADER) String sessionId) {
        if (sessionId != null && sessionStore != null) sessionStore.drop(sessionId);
        return Response.noContent().build();
    }

    /* --------------------------- method handlers --------------------------- */

    private Response handleInitialize(JsonNode idNode, JsonNode params) {
        // We ignore the client's protocolVersion negotiation for now — both
        // sides settle on what the server advertises. The wider MCP spec
        // permits this; Claude Desktop / mcp-inspector tolerate it.
        String clientProtocol = params != null ? textOrNull(params.get("protocolVersion")) : null;
        String negotiated = clientProtocol != null ? clientProtocol : MCP_PROTOCOL_VERSION;

        String newSessionId = sessionStore != null ? sessionStore.create(negotiated) : "";
        ObjectNode result = MAPPER.createObjectNode();
        result.put("protocolVersion", negotiated);
        ObjectNode capabilities = result.putObject("capabilities");
        ObjectNode toolsCap = capabilities.putObject("tools");
        toolsCap.put("listChanged", false);
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("version", saikuVersion());
        return rpcResult(idNode, result, newSessionId);
    }

    private Response handleToolsCall(JsonNode idNode, JsonNode params, String sessionId) {
        if (params == null || !params.isObject()) {
            return rpcError(
                    idNode, RPC_INVALID_PARAMS, "tools/call requires params.name + params.arguments", sessionId);
        }
        String name = textOrNull(params.get("name"));
        if (name == null) {
            return rpcError(idNode, RPC_INVALID_PARAMS, "tools/call requires params.name", sessionId);
        }
        JsonNode args = params.get("arguments");
        if (args == null) args = MAPPER.createObjectNode();
        try {
            JsonNode body = dispatchTool(name, args);
            return rpcResult(idNode, wrapToolResult(body), sessionId);
        } catch (UnknownToolException e) {
            return rpcError(idNode, RPC_METHOD_NOT_FOUND, e.getMessage(), sessionId);
        } catch (InvalidArgsException e) {
            return rpcError(idNode, RPC_INVALID_PARAMS, e.getMessage(), sessionId);
        }
    }

    /* ----------------------------- tool dispatch ---------------------------- */

    /** Route a tool name to the appropriate AiQueryResource call and return
     *  the raw body as a JsonNode. Validation errors from the underlying
     *  resource come back as a normal tool result (the body carries the
     *  {@code VALIDATION_ERROR} envelope agents use to self-correct);
     *  argument-shape failures here raise {@link InvalidArgsException}
     *  which surfaces as JSON-RPC {@link #RPC_INVALID_PARAMS}. */
    private JsonNode dispatchTool(String name, JsonNode args) {
        if (aiQueryResource == null) {
            throw new IllegalStateException("AiQueryResource not wired");
        }
        switch (name) {
            case "list_cubes": {
                // saiku#878 follow-up: the MCP spec requires structuredContent
                // to be a JSON object, not an array. AiQueryResource returns
                // List<AiCubeSummary>; wrap under "cubes" so claude.ai's
                // ClaudeAiToolResultRequest validator stops rejecting the
                // result shape.
                return wrapList("cubes", unwrap(aiQueryResource.listCubes()));
            }
            case "describe_cube": {
                String cube = requiredString(args, "cube");
                return unwrap(aiQueryResource.getSchema(cube));
            }
            case "search_members": {
                String cube = requiredString(args, "cube");
                String dimension = requiredString(args, "dimension");
                String hierarchy = optionalString(args, "hierarchy");
                String level = requiredString(args, "level");
                String q = optionalString(args, "q");
                int limit = optionalInt(args, "limit", 20);
                // Same array-wrap as list_cubes — AiQueryResource returns a
                // List<SimpleCubeElement>; MCP structuredContent must be an
                // object so the host's validator accepts it.
                return wrapList(
                        "members", unwrap(aiQueryResource.searchMembers(cube, dimension, hierarchy, level, q, limit)));
            }
            case "run_query": {
                String format = optionalString(args, "format");
                AiQueryRequest req = toAiQueryRequest(args);
                return unwrap(aiQueryResource.executeAi(req, format != null ? format : "records"));
            }
            case "preview_query": {
                AiQueryRequest req = toAiQueryRequest(args);
                return unwrap(aiQueryResource.previewAi(req));
            }
            case "drillthrough": {
                String queryId = requiredString(args, "queryId");
                int maxrows = optionalInt(args, "maxrows", 100);
                Integer firstRowset = optionalIntOrNull(args, "firstRowset");
                String returns = optionalString(args, "returns");
                // position=null → whole-result drillthrough (MCP keeps the existing
                // behaviour; per-cell position is the dashboard UI path, saiku#930).
                return unwrap(aiQueryResource.drillthrough(queryId, maxrows, firstRowset, null, returns));
            }
                // -------- Ossie tools --------
            case "list_ossie_models": {
                requireOssie();
                // AiOssieResource.listModels returns a List<Map>; wrap for MCP structuredContent.
                return wrapList("models", unwrap(aiOssieResource.listModels()));
            }
            case "describe_ossie_model": {
                requireOssie();
                String connection = requiredString(args, "connection");
                String model = requiredString(args, "model");
                return unwrap(aiOssieResource.getSchema(connection, model, null));
            }
            case "describe_ossie_ontology": {
                requireOssie();
                String connection = requiredString(args, "connection");
                String model = requiredString(args, "model");
                return unwrap(aiOssieResource.getOntology(connection, model));
            }
            case "search_field_values": {
                requireOssie();
                String connection = requiredString(args, "connection");
                String model = optionalString(args, "model");
                String dataset = requiredString(args, "dataset");
                String field = requiredString(args, "field");
                String q = optionalString(args, "q");
                Integer limit = optionalIntOrNull(args, "limit");
                return unwrap(aiOssieResource.searchValues(connection, model, dataset, field, q, limit));
            }
            case "run_ossie_query": {
                requireOssie();
                String format = optionalString(args, "format");
                org.saiku.service.ossie.ai.OssieAiQueryRequest req = toOssieAiQueryRequest(args);
                return unwrap(aiOssieResource.executeAi(req, format != null ? format : "records"));
            }
            case "preview_ossie_query": {
                requireOssie();
                org.saiku.service.ossie.ai.OssieAiQueryRequest req = toOssieAiQueryRequest(args);
                return unwrap(aiOssieResource.previewAi(req));
            }
            default:
                throw new UnknownToolException("Unknown tool: " + name);
        }
    }

    /**
     * Fail loudly when a caller invokes an Ossie tool but the resource isn't wired. Should
     * never fire in production because {@link #buildToolsList} gates the tool advertisement
     * on the same field — but a malicious client could send a raw tools/call with a name it
     * knows exists; treat that as a NOT_FOUND-shaped error rather than an NPE.
     */
    private void requireOssie() {
        if (aiOssieResource == null) {
            throw new IllegalStateException("Ossie AI resource not wired");
        }
    }

    /** Bind a JSON args object to {@link org.saiku.service.ossie.ai.OssieAiQueryRequest}. */
    private org.saiku.service.ossie.ai.OssieAiQueryRequest toOssieAiQueryRequest(JsonNode args) {
        ObjectNode copy = args.isObject() ? ((ObjectNode) args).deepCopy() : MAPPER.createObjectNode();
        copy.remove("format");
        try {
            return MAPPER.treeToValue(copy, org.saiku.service.ossie.ai.OssieAiQueryRequest.class);
        } catch (JsonProcessingException e) {
            throw new InvalidArgsException("invalid ossie query arguments: " + e.getMessage());
        }
    }

    /** Wrap a top-level array in a single-property object so the response
     *  satisfies MCP's structuredContent contract (must be a JSON object).
     *  No-op when the node is already an object — defensive in case the
     *  underlying resource ever changes shape. */
    private JsonNode wrapList(String key, JsonNode body) {
        if (body == null || body.isObject()) return body == null ? MAPPER.createObjectNode() : body;
        ObjectNode wrapped = MAPPER.createObjectNode();
        wrapped.set(key, body);
        return wrapped;
    }

    /** Read the JAX-RS Response entity as a JsonNode. AiQueryResource
     *  returns either POJOs ({@code AiQueryResponse}, {@code AiSchema}),
     *  collections (cube list), or {@code Map<String,Object>} bodies —
     *  Jackson handles all three. */
    private JsonNode unwrap(Response r) {
        Object entity = r.getEntity();
        if (entity == null) return MAPPER.createObjectNode();
        if (entity instanceof JsonNode jn) return jn;
        return MAPPER.valueToTree(entity);
    }

    private AiQueryRequest toAiQueryRequest(JsonNode args) {
        // Strip the MCP-specific 'format' key before binding; AiQueryRequest
        // doesn't know about it (it's a query-string param on the REST API).
        ObjectNode copy = args.isObject() ? ((ObjectNode) args).deepCopy() : MAPPER.createObjectNode();
        copy.remove("format");
        try {
            return MAPPER.treeToValue(copy, AiQueryRequest.class);
        } catch (JsonProcessingException e) {
            throw new InvalidArgsException("invalid query arguments: " + e.getMessage());
        }
    }

    /* ------------------------------ tools/list ----------------------------- */

    private static final String LIST_CUBES_SCHEMA =
            "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";

    private static final String DESCRIBE_CUBE_SCHEMA = "{\"type\":\"object\",\"required\":[\"cube\"],"
            + "\"properties\":{\"cube\":{\"type\":\"string\","
            + "\"description\":\"The cube id from list_cubes: connection/catalog/schema/cubeName.\"}},"
            + "\"additionalProperties\":false}";

    private static final String SEARCH_MEMBERS_SCHEMA = "{\"type\":\"object\","
            + "\"required\":[\"cube\",\"dimension\",\"level\"],"
            + "\"properties\":{"
            + "\"cube\":{\"type\":\"string\"},"
            + "\"dimension\":{\"type\":\"string\"},"
            + "\"hierarchy\":{\"type\":\"string\","
            + "\"description\":\"Optional when the dimension has only one hierarchy.\"},"
            + "\"level\":{\"type\":\"string\"},"
            + "\"q\":{\"type\":\"string\","
            + "\"description\":\"Optional substring filter. Omit to list all members on the level up to limit.\"},"
            + "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":500,\"default\":20}"
            + "},\"additionalProperties\":false}";

    private static final String RUN_QUERY_SCHEMA = "{\"type\":\"object\",\"required\":[\"cube\",\"measures\"],"
            + "\"properties\":{"
            + "\"cube\":{\"description\":\"Cube id (string from list_cubes) or "
            + "{connectionName,catalog,schema,cubeName} object.\"},"
            + "\"measures\":{\"type\":\"array\",\"minItems\":1,\"items\":"
            + "{\"type\":\"object\",\"required\":[\"name\"],"
            + "\"properties\":{\"name\":{\"type\":\"string\"}}}},"
            + "\"rows\":{\"type\":\"array\"},"
            + "\"columns\":{\"type\":\"array\"},"
            + "\"filters\":{\"type\":\"array\"},"
            + "\"order\":{\"type\":\"array\"},"
            + "\"limit\":{\"type\":\"integer\"},"
            + "\"visualTotals\":{\"type\":\"boolean\"},"
            + "\"nonEmpty\":{\"type\":\"boolean\"},"
            + "\"format\":{\"type\":\"string\",\"enum\":[\"records\",\"matrix\"],"
            + "\"description\":\"Default records.\"}"
            + "},\"additionalProperties\":true}";

    private static final String PREVIEW_QUERY_SCHEMA = "{\"type\":\"object\",\"required\":[\"cube\",\"measures\"],"
            + "\"properties\":{"
            + "\"cube\":{},\"measures\":{\"type\":\"array\"},\"rows\":{\"type\":\"array\"},"
            + "\"columns\":{\"type\":\"array\"},\"filters\":{\"type\":\"array\"},"
            + "\"order\":{\"type\":\"array\"},\"limit\":{\"type\":\"integer\"},"
            + "\"visualTotals\":{\"type\":\"boolean\"},\"nonEmpty\":{\"type\":\"boolean\"}"
            + "},\"additionalProperties\":true}";

    // ---- Ossie tool schemas (parallel to the MDX ones above) ----

    private static final String LIST_OSSIE_MODELS_SCHEMA =
            "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";

    private static final String DESCRIBE_OSSIE_MODEL_SCHEMA = "{\"type\":\"object\","
            + "\"required\":[\"connection\",\"model\"],"
            + "\"properties\":{"
            + "\"connection\":{\"type\":\"string\","
            + "\"description\":\"Ossie connection name from list_ossie_models.\"},"
            + "\"model\":{\"type\":\"string\","
            + "\"description\":\"Semantic-model name from list_ossie_models.\"}"
            + "},\"additionalProperties\":false}";

    private static final String DESCRIBE_OSSIE_ONTOLOGY_SCHEMA = DESCRIBE_OSSIE_MODEL_SCHEMA;

    private static final String SEARCH_FIELD_VALUES_SCHEMA = "{\"type\":\"object\","
            + "\"required\":[\"connection\",\"dataset\",\"field\"],"
            + "\"properties\":{"
            + "\"connection\":{\"type\":\"string\"},"
            + "\"model\":{\"type\":\"string\","
            + "\"description\":\"Optional; defaults to the connection's model.\"},"
            + "\"dataset\":{\"type\":\"string\"},"
            + "\"field\":{\"type\":\"string\"},"
            + "\"q\":{\"type\":\"string\","
            + "\"description\":\"Optional substring filter (case-insensitive). Omit to list first N values.\"},"
            + "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":200,\"default\":20}"
            + "},\"additionalProperties\":false}";

    private static final String RUN_OSSIE_QUERY_SCHEMA = "{\"type\":\"object\","
            + "\"required\":[\"connection\"],"
            + "\"properties\":{"
            + "\"connection\":{\"type\":\"string\"},"
            + "\"model\":{\"type\":\"string\"},"
            + "\"rows\":{\"type\":\"array\","
            + "\"items\":{\"type\":\"object\","
            + "\"required\":[\"dataset\",\"field\"],"
            + "\"properties\":{\"dataset\":{\"type\":\"string\"},\"field\":{\"type\":\"string\"}}}},"
            + "\"columns\":{\"type\":\"array\","
            + "\"items\":{\"type\":\"object\","
            + "\"required\":[\"dataset\",\"field\"],"
            + "\"properties\":{\"dataset\":{\"type\":\"string\"},\"field\":{\"type\":\"string\"}}}},"
            + "\"values\":{\"type\":\"array\","
            + "\"items\":{\"type\":\"object\","
            + "\"required\":[\"metric\"],"
            + "\"properties\":{\"metric\":{\"type\":\"string\"},\"aggregation\":{\"type\":\"string\"}}}},"
            + "\"filters\":{\"type\":\"array\"},"
            + "\"sorts\":{\"type\":\"array\"},"
            + "\"limit\":{\"type\":\"integer\"},"
            + "\"format\":{\"type\":\"string\",\"enum\":[\"records\",\"matrix\"],\"default\":\"records\"}"
            + "},\"additionalProperties\":false}";

    private static final String PREVIEW_OSSIE_QUERY_SCHEMA = "{\"type\":\"object\","
            + "\"required\":[\"connection\"],"
            + "\"properties\":{"
            + "\"connection\":{\"type\":\"string\"},"
            + "\"model\":{\"type\":\"string\"},"
            + "\"rows\":{\"type\":\"array\"},"
            + "\"columns\":{\"type\":\"array\"},"
            + "\"values\":{\"type\":\"array\"},"
            + "\"filters\":{\"type\":\"array\"},"
            + "\"sorts\":{\"type\":\"array\"},"
            + "\"limit\":{\"type\":\"integer\"}"
            + "},\"additionalProperties\":false}";

    private static final String DRILLTHROUGH_SCHEMA = "{\"type\":\"object\",\"required\":[\"queryId\"],"
            + "\"properties\":{"
            + "\"queryId\":{\"type\":\"string\","
            + "\"description\":\"queryId returned by a prior run_query call.\"},"
            + "\"maxrows\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":10000,\"default\":100,"
            + "\"description\":\"Mondrian-side row cap. Use this for the FoodMart sample cube.\"},"
            + "\"firstRowset\":{\"type\":\"integer\",\"minimum\":1,"
            + "\"description\":\"Warehouse-side rowset cap (Snowflake/BigQuery/XMLA). Safe to supply on Mondrian — "
            + "server falls back to MAXROWS automatically.\"},"
            + "\"returns\":{\"type\":\"string\","
            + "\"description\":\"Optional comma-separated column list to project a subset.\"}"
            + "},\"additionalProperties\":false}";

    private ObjectNode buildToolsList() {
        ArrayNode tools = MAPPER.createArrayNode();
        tools.add(tool(
                "list_cubes",
                "List every OLAP cube the current user can query. Use this first when you don't know "
                        + "what data is available — each entry has a one-line caption and the cube's default measure, "
                        + "which is usually enough to pick the right cube without describing each one. Returns at most "
                        + "a few dozen entries; not paginated.",
                LIST_CUBES_SCHEMA));
        tools.add(tool(
                "describe_cube",
                "Get the complete queryable structure of one cube: measures, dimensions, hierarchies, "
                        + "levels, and sample members with their MDX unique names. Always call this before run_query "
                        + "if you haven't seen the cube structure yet — it tells you exactly which names are valid "
                        + "and includes ready-made example query bodies.",
                DESCRIBE_CUBE_SCHEMA));
        tools.add(tool(
                "search_members",
                "Find the MDX unique names of members on a level by substring match. Use when the cube "
                        + "has more members at a level than describe_cube's sampleMembers covered (e.g. searching "
                        + "for a specific city, customer, or product brand), or when the user says \"filter by Italy\" "
                        + "and you need to confirm the spelling. Returns up to limit hits with caption + uniqueName.",
                SEARCH_MEMBERS_SCHEMA));
        tools.add(tool(
                "run_query",
                "Run an OLAP analytical query and get the results as records. This is the primary tool — "
                        + "most user questions land here. Build the request against the cube structure from "
                        + "describe_cube; the server validates every name and returns a 400 with a list of valid "
                        + "alternatives if any name is wrong, so don't pre-validate yourself. Default output is records "
                        + "(one object per row, keyed by column captions); pass format:\"matrix\" only if you need "
                        + "the position-indexed shape.",
                RUN_QUERY_SCHEMA));
        tools.add(tool(
                "preview_query",
                "Compile a query to MDX without executing it. Use when you want to show the user what "
                        + "the query will do, audit a generated query, or estimate cost before running an expensive "
                        + "aggregation. Validation runs the same as run_query — preview will return the same "
                        + "VALIDATION_ERROR shape if names don't resolve.",
                PREVIEW_QUERY_SCHEMA));
        tools.add(tool(
                "drillthrough",
                "Fetch the raw fact-table rows behind a specific query. Use when the user asks "
                        + "\"show me the underlying transactions\" or wants to inspect detail for a single cell. "
                        + "Pass the queryId returned by an earlier run_query call. Cells in the response are the "
                        + "same typed envelope as run_query — numeric warehouse columns get a parsed value.",
                DRILLTHROUGH_SCHEMA));
        // Ossie tools — only advertise when the resource is wired (deployments that
        // don't ship any Ossie datasources shouldn't see them in tools/list).
        if (aiOssieResource != null) {
            tools.add(tool(
                    "list_ossie_models",
                    "List every Ossie semantic model the current user can query. Use this first when the user "
                            + "asks about semantic-YAML models rather than OLAP cubes. Each entry has the connection "
                            + "name, model name, description, fact dataset, and dataset/metric counts.",
                    LIST_OSSIE_MODELS_SCHEMA));
            tools.add(tool(
                    "describe_ossie_model",
                    "Get the complete queryable structure of one Ossie model: datasets with fields (each carrying "
                            + "sample values, cardinality, and SQL type), metrics with their expressions + supported "
                            + "aggregation overrides, and relationships. Always call before run_ossie_query so you "
                            + "know which names resolve. Includes ready-made example bodies (simpleGroupBy, crosstab, "
                            + "topN) you can copy directly.",
                    DESCRIBE_OSSIE_MODEL_SCHEMA));
            tools.add(tool(
                    "describe_ossie_ontology",
                    "Get the OSI ontology block for an Ossie model — a knowledge-graph view of the entities "
                            + "(concepts) and the relationships between them (attributes, associations, derived). Use "
                            + "when the user asks about the model at the entity level (\"what concepts exist?\", "
                            + "\"what's connected to Airport?\") rather than the SQL-adjacent dataset level. Empty "
                            + "{ontology:[]} when the document doesn't declare one.",
                    DESCRIBE_OSSIE_ONTOLOGY_SCHEMA));
            tools.add(tool(
                    "search_field_values",
                    "Find distinct values of an Ossie field by substring match. Use when describe_ossie_model's "
                            + "sampleValues covered fewer than the user needs (searching for a specific state, brand, "
                            + "customer), or when the user says \"filter by Medicare\" and you want to confirm the "
                            + "exact spelling. Returns up to limit matches.",
                    SEARCH_FIELD_VALUES_SCHEMA));
            tools.add(tool(
                    "run_ossie_query",
                    "Run an Ossie shelf-state query. Primary tool for semantic-model queries. Build the request "
                            + "against describe_ossie_model's schema; the server validates every name and returns a "
                            + "VALIDATION_ERROR with an alternative list if any name is wrong. Records-format is "
                            + "default; pass format:\"matrix\" for the cellSetHeaders/cellSetBody envelope.",
                    RUN_OSSIE_QUERY_SCHEMA));
            tools.add(tool(
                    "preview_ossie_query",
                    "Compile a shelf-state query to SQL without executing. Use to show the user what the query "
                            + "will do, audit a generated query, or estimate cost. Validation runs the same as "
                            + "run_ossie_query — preview returns the same VALIDATION_ERROR shape if names don't "
                            + "resolve.",
                    PREVIEW_OSSIE_QUERY_SCHEMA));
        }
        ObjectNode result = MAPPER.createObjectNode();
        result.set("tools", tools);
        return result;
    }

    private ObjectNode tool(String name, String description, String schemaJson) {
        ObjectNode t = MAPPER.createObjectNode();
        t.put("name", name);
        t.put("description", description);
        try {
            t.set("inputSchema", MAPPER.readTree(schemaJson));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("inline tool schema unparseable: " + name, e);
        }
        return t;
    }

    /** Wrap a tool body in the MCP {@code CallToolResult} envelope:
     *  the body lands in {@code structuredContent} for agents that read
     *  structured JSON directly, and is also serialised into a single
     *  {@code text} content block for hosts that only support text content. */
    private ObjectNode wrapToolResult(JsonNode body) {
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode textBlock = content.addObject();
        textBlock.put("type", "text");
        try {
            textBlock.put("text", MAPPER.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            textBlock.put("text", String.valueOf(body));
        }
        result.set("structuredContent", body);
        result.put("isError", false);
        return result;
    }

    /* ------------------------------- helpers -------------------------------- */

    private String requireSession(String sessionId) {
        // initialize is allowed without a session id (it MINTS the id);
        // every other method MUST present one. The handshake order means
        // a client that skips initialize gets a structured 400 here rather
        // than a confusing tool-side error.
        if (sessionStore == null) return null;
        if (sessionId == null || sessionId.isBlank()) {
            throw new SessionRequiredException(
                    "Missing " + SESSION_HEADER + " header. Call initialize first to obtain a session id.");
        }
        McpSessionStore.McpSession s = sessionStore.touch(sessionId);
        if (s == null) {
            throw new SessionRequiredException(
                    "Unknown or expired session id. Call initialize to start a new session.");
        }
        return sessionId;
    }

    private static String textOrNull(JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (n.isTextual()) return n.asText();
        return n.asText();
    }

    private static String requiredString(JsonNode args, String key) {
        JsonNode v = args.get(key);
        if (v == null || v.isNull() || (v.isTextual() && v.asText().isEmpty())) {
            throw new InvalidArgsException("Missing required argument: " + key);
        }
        return v.asText();
    }

    private static String optionalString(JsonNode args, String key) {
        JsonNode v = args.get(key);
        if (v == null || v.isNull()) return null;
        String s = v.asText();
        return s.isEmpty() ? null : s;
    }

    private static int optionalInt(JsonNode args, String key, int defaultValue) {
        JsonNode v = args.get(key);
        if (v == null || v.isNull()) return defaultValue;
        if (v.isNumber()) return v.intValue();
        try {
            return Integer.parseInt(v.asText());
        } catch (NumberFormatException e) {
            throw new InvalidArgsException("Argument '" + key + "' must be an integer");
        }
    }

    private static Integer optionalIntOrNull(JsonNode args, String key) {
        JsonNode v = args.get(key);
        if (v == null || v.isNull()) return null;
        if (v.isNumber()) return v.intValue();
        try {
            return Integer.parseInt(v.asText());
        } catch (NumberFormatException e) {
            throw new InvalidArgsException("Argument '" + key + "' must be an integer");
        }
    }

    private static String saikuVersion() {
        String v = System.getProperty("saiku.version", "");
        return (v == null || v.isBlank()) ? "0.0.0" : v;
    }

    /* ----------------------------- JSON-RPC plumbing ------------------------ */

    private Response rpcResult(JsonNode idNode, JsonNode result, String sessionHeader) {
        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("jsonrpc", "2.0");
        envelope.set("id", idNode == null ? MAPPER.nullNode() : idNode);
        envelope.set("result", result == null ? MAPPER.createObjectNode() : result);
        Response.ResponseBuilder rb = Response.ok(envelope);
        if (sessionHeader != null && !sessionHeader.isEmpty()) {
            rb.header(SESSION_HEADER, sessionHeader);
        }
        return rb.build();
    }

    private Response rpcError(JsonNode idNode, int code, String message, String sessionHeader) {
        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("jsonrpc", "2.0");
        envelope.set("id", idNode == null ? MAPPER.nullNode() : idNode);
        ObjectNode err = envelope.putObject("error");
        err.put("code", code);
        err.put("message", message);
        Response.ResponseBuilder rb = Response.ok(envelope);
        if (sessionHeader != null && !sessionHeader.isEmpty()) {
            rb.header(SESSION_HEADER, sessionHeader);
        }
        return rb.build();
    }

    /* ---------------------------- typed exceptions -------------------------- */

    private static final class UnknownToolException extends RuntimeException {
        UnknownToolException(String message) {
            super(message);
        }
    }

    private static final class InvalidArgsException extends RuntimeException {
        InvalidArgsException(String message) {
            super(message);
        }
    }

    private static final class SessionRequiredException extends RuntimeException {
        SessionRequiredException(String message) {
            super(message);
        }
    }
}
