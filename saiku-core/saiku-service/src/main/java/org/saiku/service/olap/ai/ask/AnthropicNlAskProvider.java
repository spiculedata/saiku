/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * {@link NlAskProvider} backed by Anthropic's Messages API.
 *
 * <p>Schema-constrained output is driven by Anthropic's tool-use feature: the {@code emit_query}
 * tool's {@code input_schema} is populated at request time from
 * {@link NlAskRequest#requestJsonSchema()} (the canonical AiQueryRequest JSON Schema). The model
 * is forced to call the tool via {@code tool_choice}, so the response always contains structured
 * JSON ready to deserialise into an {@link org.saiku.service.olap.ai.AiQueryRequest}.
 *
 * <p>Talks to the HTTPS endpoint directly via JDK {@link HttpClient} — no Anthropic SDK
 * dependency. Failures (transport, non-2xx, missing tool block, parse error) return a
 * {@link NlAskResponse#degraded(String, String)} rather than throwing, per the provider contract.
 */
public final class AnthropicNlAskProvider implements NlAskProvider {

    /** Default model id. Bump when Anthropic publishes a newer stable Sonnet. */
    public static final String DEFAULT_MODEL = "claude-sonnet-4-6";

    private static final String ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";
    private static final String TOOL_NAME = "emit_query";
    private static final String REFUSAL_TOOL_NAME = "refuse_off_topic";

    private static final String SYSTEM_PROMPT = "You are a Mondrian OLAP query assistant scoped to a "
            + "single cube. Your job is to translate a user's natural-language question about the "
            + "cube's data into a single AiQueryRequest by calling the emit_query tool. "
            + "SCOPE GUARDRAIL: If the user's question is NOT about querying this cube's data — e.g. "
            + "general knowledge, coding help, weather, math, prose composition, jokes, advice, "
            + "personal questions, or anything you couldn't answer with measures/dimensions from the "
            + "schema below — you MUST call the refuse_off_topic tool with a one-sentence reason. "
            + "Do not attempt to answer off-topic questions with prose, and do not invent a cube "
            + "query just to satisfy the user. Borderline cases (analytical questions phrased in "
            + "domain terms that map to the schema) should still call emit_query. "
            + "CRITICAL RULES for emit_query: (1) Every name field MUST refer to a member, measure, "
            + "dimension, hierarchy or level present in the provided cube schema — never invent names. "
            + "(2) Echo the connectionName, catalog, schema and cubeName from the provided cube ref "
            + "exactly. (3) Prefer rows for the dimension the user wants to break down by; use columns "
            + "only when the user explicitly compares across two axes. (4) Put time / region restrictions "
            + "in filters (the slicer). (5) When the user asks for 'top N' or 'bottom N', set both "
            + "`order` and `limit`. (6) Keep aggregator overrides off unless the user asks for one. "
            + "Always call exactly one tool — never respond with prose.";

    private static final String REFUSAL_REASON_PREFIX = "OFF_TOPIC: ";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Provider configuration. */
    public record Config(String apiKey, String model, double temperature, int maxTokens, Duration requestTimeout) {
        public Config {
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalArgumentException("apiKey must be non-blank");
            }
            if (model == null || model.isBlank()) {
                model = DEFAULT_MODEL;
            }
            if (maxTokens <= 0) {
                maxTokens = 4096;
            }
            if (requestTimeout == null) {
                requestTimeout = Duration.ofSeconds(60);
            }
        }

        public static Config of(String apiKey) {
            return new Config(apiKey, DEFAULT_MODEL, 0.0, 4096, Duration.ofSeconds(60));
        }
    }

    private final Config config;
    private final HttpClient http;

    public AnthropicNlAskProvider(String apiKey) {
        this(Config.of(apiKey));
    }

    public AnthropicNlAskProvider(Config config) {
        this(
                config,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());
    }

    /** Package-visible ctor for tests that need to inject a fake client. */
    AnthropicNlAskProvider(Config config, HttpClient http) {
        if (config == null) {
            throw new IllegalArgumentException("config");
        }
        this.config = config;
        this.http = http;
    }

    @Override
    public NlAskResponse ask(NlAskRequest request) {
        try {
            String body = buildRequestBody(request);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT))
                    .timeout(config.requestTimeout())
                    .header("x-api-key", config.apiKey())
                    .header("anthropic-version", API_VERSION)
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return NlAskResponse.degraded(
                        "HTTP " + response.statusCode() + ": " + truncate(response.body(), 200), config.model());
            }
            return parseToolResponse(response.body(), config.model());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return NlAskResponse.degraded("Transport error: " + e.getClass().getSimpleName(), config.model());
        } catch (RuntimeException e) {
            return NlAskResponse.degraded("Unexpected error: " + e.getClass().getSimpleName(), config.model());
        }
    }

    // ---------- request building ----------

    String buildRequestBody(NlAskRequest request) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", config.model());
        root.put("max_tokens", config.maxTokens());
        root.put("temperature", config.temperature());
        root.put(
                "system",
                SYSTEM_PROMPT + "\n\nCube schema:\n" + request.cubeSchemaJson() + "\n\nCube ref to echo: "
                        + cubeRefJson(request));

        ArrayNode tools = root.putArray("tools");
        ObjectNode tool = tools.addObject();
        tool.put("name", TOOL_NAME);
        tool.put("description", "Emit a structured AiQueryRequest matching the cube schema.");
        tool.set("input_schema", MAPPER.readTree(request.requestJsonSchema()));

        // Refusal path — the model picks this when the user's question isn't
        // about the cube. Stops Saiku's AI key from becoming a free general
        // LLM proxy.
        ObjectNode refusalTool = tools.addObject();
        refusalTool.put("name", REFUSAL_TOOL_NAME);
        refusalTool.put(
                "description",
                "Refuse a question that isn't about querying this cube's data. Call with a one-sentence reason.");
        ObjectNode refusalSchema = refusalTool.putObject("input_schema");
        refusalSchema.put("type", "object");
        ObjectNode refusalProps = refusalSchema.putObject("properties");
        ObjectNode reasonProp = refusalProps.putObject("reason");
        reasonProp.put("type", "string");
        reasonProp.put("description", "One-sentence explanation of why the question is off-topic.");
        refusalSchema.putArray("required").add("reason");

        // tool_choice "any" — model must call a tool, but picks emit_query OR
        // refuse_off_topic. Was "tool" (forced emit_query) before; that path
        // would have the model invent queries for off-topic questions.
        ObjectNode toolChoice = root.putObject("tool_choice");
        toolChoice.put("type", "any");

        ArrayNode messages = root.putArray("messages");
        for (NlAskMessage m : request.history()) {
            ObjectNode mNode = messages.addObject();
            mNode.put("role", m.role().wireName());
            mNode.put("content", m.content());
        }
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", request.question());

        return MAPPER.writeValueAsString(root);
    }

    private static String cubeRefJson(NlAskRequest request) {
        ObjectNode r = MAPPER.createObjectNode();
        r.put("connectionName", request.cubeRef().getConnectionName());
        r.put("catalog", request.cubeRef().getCatalog());
        r.put("schema", request.cubeRef().getSchema());
        r.put("cubeName", request.cubeRef().getCubeName());
        return r.toString();
    }

    // ---------- response parsing ----------

    /**
     * Parse an Anthropic Messages API response body into an {@link NlAskResponse}. Visible for
     * testing — this is the deserialisation contract.
     *
     * <p>Looks for a {@code tool_use} content block named {@link #TOOL_NAME} and returns its
     * {@code input} JSON verbatim. If no such block exists, returns a degraded response.
     */
    static NlAskResponse parseToolResponse(String body, String model) throws IOException {
        JsonNode root = MAPPER.readTree(body);
        int inputTokens = root.path("usage").path("input_tokens").asInt(-1);
        int outputTokens = root.path("usage").path("output_tokens").asInt(-1);

        JsonNode content = root.path("content");
        if (content.isArray()) {
            for (JsonNode block : content) {
                if (!"tool_use".equals(block.path("type").asText())) {
                    continue;
                }
                String toolName = block.path("name").asText();
                if (TOOL_NAME.equals(toolName)) {
                    JsonNode input = block.path("input");
                    if (input.isMissingNode() || input.isNull()) {
                        return NlAskResponse.degraded("empty tool_use input", model);
                    }
                    return NlAskResponse.ok(MAPPER.writeValueAsString(input), model, inputTokens, outputTokens);
                }
                if (REFUSAL_TOOL_NAME.equals(toolName)) {
                    String reason = block.path("input").path("reason").asText("Question is not about the cube.");
                    return NlAskResponse.degraded(REFUSAL_REASON_PREFIX + reason, model);
                }
            }
        }
        return NlAskResponse.degraded("no tool_use block", model);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
