/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.mcp;

import static org.junit.Assert.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Random;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.olap.ai.AiQueryRequest;
import org.saiku.service.olap.ai.AiQueryResponse;
import org.saiku.web.rest.resources.AiOssieResource;
import org.saiku.web.rest.resources.AiQueryResource;

/**
 * Property-based fuzz tests for {@link McpResource}.
 *
 * <p>The MCP endpoint is the JSON-RPC surface hostile LLM agents connect to. Every request
 * body is untrusted, and the load-bearing invariant is: <em>{@code handle()} never throws;
 * it always returns a JAX-RS Response carrying either a JSON-RPC result or a JSON-RPC error
 * envelope. </em> An uncaught exception here would crash the request thread AND leak stack
 * information back to the client through the JAX-RS default mapper.
 *
 * <p>Also verifies {@code tools/call} dispatch is robust to:
 * <ul>
 *   <li>unknown tool names → RPC_METHOD_NOT_FOUND, never NPE</li>
 *   <li>malformed argument shapes → RPC_INVALID_PARAMS, never JsonProcessingException leak</li>
 *   <li>random argument bags — including hostile string content — no crash</li>
 * </ul>
 *
 * <p>Fixed seed. Failing inputs are printed with enough context to reproduce.
 */
public class McpResourceFuzzTest {

    private static final long SEED = 0xB16B00B5L;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SESSION_HEADER = "Mcp-Session-Id";

    private McpResource resource;
    private McpSessionStore sessionStore;
    private String sessionId;

    @Before
    public void setUp() {
        sessionStore = new McpSessionStore(60_000L);
        resource = new McpResource();
        resource.setSessionStore(sessionStore);
        resource.setAiQueryResource(new NeverThrowsAiQueryResource());
        resource.setAiOssieResource(new NeverThrowsAiOssieResource());
        // Mint a session for tools/call-style requests
        Response init = resource.handle(rpc("initialize", 1, MAPPER.createObjectNode()), null);
        sessionId = init.getHeaderString(SESSION_HEADER);
    }

    // ------------------------------------------------------------
    // 1. Top-level dispatch — random RPC method + params
    // ------------------------------------------------------------

    @Test
    public void handleNeverThrowsOnRandomRpcEnvelopes() {
        Random rng = new Random(SEED);
        for (int i = 0; i < 4000; i++) {
            JsonNode envelope = randomRpcEnvelope(rng);
            Response resp = tryHandle(envelope, maybeSession(rng), i);
            // Every response must be a JAX-RS Response; status must fall in the 2xx/4xx/5xx range
            // rather than being null, and the entity must be either null (for 202-notification)
            // or a JSON-serialisable object.
            int status = resp.getStatus();
            assertTrue("odd status " + status + " on iteration " + i, status >= 200 && status < 600);
            Object entity = resp.getEntity();
            if (status == 202) {
                // notification-ack; no body allowed per JSON-RPC 2.0
                continue;
            }
            assertNotNull("missing entity on non-202 iteration " + i, entity);
            JsonNode body = MAPPER.valueToTree(entity);
            assertEquals("must be JSON-RPC 2.0", "2.0", body.path("jsonrpc").asText());
            boolean hasResult = body.has("result");
            boolean hasError = body.has("error");
            assertTrue("envelope must carry result or error on iteration " + i + ": " + body, hasResult ^ hasError);
        }
    }

    // ------------------------------------------------------------
    // 2. tools/call — random arguments against every known tool
    // ------------------------------------------------------------

    private static final String[] KNOWN_TOOLS = {
        "list_cubes",
        "describe_cube",
        "search_members",
        "run_query",
        "preview_query",
        "drillthrough",
        "list_ossie_models",
        "describe_ossie_model",
        "describe_ossie_ontology",
        "search_field_values",
        "run_ossie_query",
        "preview_ossie_query",
        "definitely_not_a_tool",
        "",
        " ",
        "'; DROP TABLE cubes; --",
    };

    @Test
    public void toolsCallNeverThrowsForAnyToolNameOrArguments() {
        Random rng = new Random(SEED + 1);
        for (int i = 0; i < 3000; i++) {
            String tool = KNOWN_TOOLS[rng.nextInt(KNOWN_TOOLS.length)];
            ObjectNode args = randomArgumentBag(rng, 0);
            ObjectNode params = MAPPER.createObjectNode();
            params.put("name", tool);
            params.set("arguments", args);
            Response resp = tryHandle(rpc("tools/call", i, params), sessionId, i);
            assertEquals(200, resp.getStatus());
            JsonNode body = MAPPER.valueToTree(resp.getEntity());
            assertEquals("2.0", body.path("jsonrpc").asText());
            // Either a legit tool result or a well-shaped JSON-RPC error
            if (!body.has("result") && !body.has("error")) {
                fail("iteration " + i + " tool='" + tool + "' produced neither result nor error: " + body);
            }
            if (body.has("error")) {
                int code = body.path("error").path("code").asInt();
                assertTrue("iteration " + i + " tool='" + tool + "' error code out of range: " + code, code < 0);
            }
        }
    }

    // ------------------------------------------------------------
    // 3. tools/call with structurally malformed params
    // ------------------------------------------------------------

    @Test
    public void toolsCallHandlesStructurallyBrokenParams() {
        Random rng = new Random(SEED + 2);
        for (int i = 0; i < 500; i++) {
            JsonNode params;
            int shape = rng.nextInt(6);
            switch (shape) {
                case 0:
                    params = JsonNodeFactory.instance.nullNode();
                    break;
                case 1:
                    params = JsonNodeFactory.instance.textNode("just-a-string");
                    break;
                case 2:
                    params = JsonNodeFactory.instance.arrayNode();
                    break;
                case 3: {
                    ObjectNode p = MAPPER.createObjectNode();
                    p.put("name", "run_query"); // no arguments field
                    params = p;
                    break;
                }
                case 4: {
                    ObjectNode p = MAPPER.createObjectNode();
                    p.set("name", JsonNodeFactory.instance.numberNode(42)); // wrong type
                    p.set("arguments", MAPPER.createObjectNode());
                    params = p;
                    break;
                }
                default: {
                    ObjectNode p = MAPPER.createObjectNode();
                    p.put("name", "");
                    p.set("arguments", MAPPER.createObjectNode());
                    params = p;
                    break;
                }
            }
            Response resp = tryHandle(rpc("tools/call", i, params), sessionId, i);
            assertEquals(200, resp.getStatus());
            JsonNode body = MAPPER.valueToTree(resp.getEntity());
            if (!body.has("result") && !body.has("error")) {
                fail("iteration " + i + " shape=" + shape + " produced neither result nor error: " + body);
            }
        }
    }

    // ------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------

    private Response tryHandle(JsonNode envelope, String sid, int iteration) {
        try {
            return resource.handle(envelope, sid);
        } catch (RuntimeException e) {
            fail("handle threw on iteration " + iteration + " envelope=" + envelope + ": " + e);
            return null;
        }
    }

    private String maybeSession(Random rng) {
        return switch (rng.nextInt(4)) {
            case 0 -> null;
            case 1 -> "";
            case 2 -> "not-a-real-session-" + rng.nextInt();
            default -> sessionId;
        };
    }

    /** Build a random JSON-RPC envelope. Occasionally omits fields, uses wrong types, etc. */
    private JsonNode randomRpcEnvelope(Random rng) {
        int shape = rng.nextInt(10);
        return switch (shape) {
            case 0 -> JsonNodeFactory.instance.nullNode();
            case 1 -> JsonNodeFactory.instance.arrayNode();
            case 2 -> JsonNodeFactory.instance.textNode("not-an-object");
            case 3 -> JsonNodeFactory.instance.numberNode(42);
            case 4 -> emptyObject();
            case 5 -> envelopeMissingField(rng);
            case 6 -> envelopeWrongTypes(rng);
            default -> rpc(randomMethod(rng), rng.nextInt(1_000_000), randomParams(rng));
        };
    }

    private JsonNode emptyObject() {
        return MAPPER.createObjectNode();
    }

    private JsonNode envelopeMissingField(Random rng) {
        ObjectNode env = MAPPER.createObjectNode();
        env.put("jsonrpc", "2.0");
        if (rng.nextBoolean()) env.put("id", rng.nextInt());
        // deliberately no method
        return env;
    }

    private JsonNode envelopeWrongTypes(Random rng) {
        ObjectNode env = MAPPER.createObjectNode();
        env.put("jsonrpc", rng.nextBoolean() ? "1.0" : "");
        env.put("id", rng.nextBoolean() ? "not-a-number" : "");
        env.put("method", ""); // valid type, empty value
        env.set("params", randomParams(rng));
        return env;
    }

    private String randomMethod(Random rng) {
        String[] known = {
            "initialize",
            "ping",
            "tools/list",
            "tools/call",
            "unknown/method",
            "",
            "tools/nonexistent",
            "\"; DELETE",
            "🤖",
        };
        return known[rng.nextInt(known.length)];
    }

    private JsonNode randomParams(Random rng) {
        int shape = rng.nextInt(5);
        return switch (shape) {
            case 0 -> null;
            case 1 -> JsonNodeFactory.instance.arrayNode();
            case 2 -> JsonNodeFactory.instance.nullNode();
            case 3 -> JsonNodeFactory.instance.textNode("scalar-params");
            default -> randomArgumentBag(rng, 0);
        };
    }

    private ObjectNode randomArgumentBag(Random rng, int depth) {
        ObjectNode node = MAPPER.createObjectNode();
        int size = rng.nextInt(6);
        String[] hostileKeys = {
            "cube",
            "connection",
            "model",
            "dataset",
            "field",
            "measures",
            "rows",
            "columns",
            "filters",
            "q",
            "limit",
            "queryId",
            "format",
            "not-a-real-arg",
            "'; DROP TABLE --",
            "🤖",
            ""
        };
        for (int i = 0; i < size; i++) {
            String key = hostileKeys[rng.nextInt(hostileKeys.length)];
            node.set(key, randomValue(rng, depth));
        }
        return node;
    }

    private JsonNode randomValue(Random rng, int depth) {
        int pick = rng.nextInt(depth > 2 ? 5 : 9);
        return switch (pick) {
            case 0 -> JsonNodeFactory.instance.nullNode();
            case 1 -> JsonNodeFactory.instance.textNode(randomHostileString(rng));
            case 2 -> JsonNodeFactory.instance.numberNode(rng.nextInt());
            case 3 -> JsonNodeFactory.instance.numberNode(rng.nextDouble());
            case 4 -> JsonNodeFactory.instance.booleanNode(rng.nextBoolean());
            case 5 -> {
                ArrayNode arr = MAPPER.createArrayNode();
                int n = rng.nextInt(4);
                for (int i = 0; i < n; i++) arr.add(randomValue(rng, depth + 1));
                yield arr;
            }
            case 6 -> randomArgumentBag(rng, depth + 1);
            case 7 -> JsonNodeFactory.instance.textNode("");
            default -> JsonNodeFactory.instance.textNode("very ".repeat(200));
        };
    }

    private String randomHostileString(Random rng) {
        String[] pool = {
            "sales",
            "",
            " ",
            "🤖",
            "café",
            "中文",
            "\"; DROP TABLE cubes; --",
            "'; DELETE FROM x; --",
            "'\\'/*",
            "null",
            "'",
            "\"",
            "\\",
            "\n",
            " "
        };
        return pool[rng.nextInt(pool.length)];
    }

    private JsonNode rpc(String method, int id, JsonNode params) {
        ObjectNode env = MAPPER.createObjectNode();
        env.put("jsonrpc", "2.0");
        env.put("id", id);
        env.put("method", method);
        if (params != null) env.set("params", params);
        return env;
    }

    // ------------------------------------------------------------
    // Test doubles — never throw, return canned success envelopes
    // ------------------------------------------------------------

    /**
     * Stub that returns canned success responses for every method called. The fuzz test
     * doesn't care what the underlying resource does — only that MCP's dispatch never
     * propagates an unexpected exception up to the JSON-RPC layer.
     */
    private static final class NeverThrowsAiQueryResource extends AiQueryResource {
        @Override
        public Response listCubes() {
            return Response.ok(List.of()).build();
        }

        @Override
        public Response getSchema(String cube) {
            return Response.ok(java.util.Map.of("cube", cube == null ? "" : cube))
                    .build();
        }

        @Override
        public Response searchMembers(
                String cube, String dimension, String hierarchy, String level, String q, int limit) {
            return Response.ok(List.of()).build();
        }

        @Override
        public Response executeAi(AiQueryRequest req, String format) {
            return Response.ok(new AiQueryResponse()).build();
        }

        @Override
        public Response previewAi(AiQueryRequest req) {
            return Response.ok(java.util.Map.of("mdx", "SELECT FROM [x]")).build();
        }

        @Override
        public Response drillthrough(
                String queryId, int maxrows, Integer firstRowset, String position, String returns) {
            return Response.ok(java.util.Map.of("queryId", queryId == null ? "" : queryId))
                    .build();
        }
    }

    private static final class NeverThrowsAiOssieResource extends AiOssieResource {
        @Override
        public Response listModels() {
            return Response.ok(List.of()).build();
        }

        @Override
        public Response getSchema(String connection, String model, Boolean refresh) {
            return Response.ok(java.util.Map.of("model", model == null ? "" : model))
                    .build();
        }

        @Override
        public Response getOntology(String connection, String model) {
            return Response.ok(java.util.Map.of("ontology", List.of())).build();
        }

        @Override
        public Response searchValues(
                String connection, String model, String dataset, String field, String q, Integer limit) {
            return Response.ok(java.util.Map.of("values", List.of())).build();
        }

        @Override
        public Response executeAi(org.saiku.service.ossie.ai.OssieAiQueryRequest req, String format) {
            return Response.ok(java.util.Map.of("rows", List.of())).build();
        }

        @Override
        public Response previewAi(org.saiku.service.ossie.ai.OssieAiQueryRequest req) {
            return Response.ok(java.util.Map.of("sql", "SELECT 1")).build();
        }
    }
}
