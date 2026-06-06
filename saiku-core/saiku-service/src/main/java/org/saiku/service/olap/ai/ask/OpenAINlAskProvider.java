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
 * {@link NlAskProvider} backed by OpenAI's Chat Completions API.
 *
 * <p>Structured output is driven by OpenAI's function-calling: the {@code emit_query} function's
 * {@code parameters} field is populated at request time from {@link NlAskRequest#requestJsonSchema()}.
 * The model is forced to call the function via {@code tool_choice}, so the response always carries
 * structured JSON ready to deserialise into an {@link org.saiku.service.olap.ai.AiQueryRequest}.
 *
 * <p>The endpoint is overridable via {@link Config#endpoint()} so OpenAI-compatible deployments
 * (Azure OpenAI, vLLM, Ollama with the openai-compatible adapter, Together, …) reuse this provider.
 *
 * <p>JDK {@link HttpClient}, no OpenAI SDK dependency. Failure modes mirror
 * {@link AnthropicNlAskProvider}: transport errors, non-2xx upstream responses, missing tool_calls,
 * and parse failures all return {@link NlAskResponse#degraded(String, String)} rather than throwing.
 */
public final class OpenAINlAskProvider implements NlAskProvider {

    /** Default model id. Bump when OpenAI publishes a newer GA structured-output model. */
    public static final String DEFAULT_MODEL = "gpt-4o-mini";

    /** Default endpoint. Override via {@link Config#endpoint()} for OpenAI-compatible servers. */
    public static final String DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions";

    private static final String TOOL_NAME = "emit_query";

    private static final String SYSTEM_PROMPT = "You are a Mondrian OLAP query assistant. Your job is to "
            + "translate a user's natural-language question into a single AiQueryRequest by calling the "
            + "emit_query function. CRITICAL RULES: (1) Every name field MUST refer to a member, measure, "
            + "dimension, hierarchy or level present in the provided cube schema — never invent names. "
            + "(2) Echo the connectionName, catalog, schema and cubeName from the provided cube ref "
            + "exactly. (3) Prefer rows for the dimension the user wants to break down by; use columns "
            + "only when the user explicitly compares across two axes. (4) Put time / region restrictions "
            + "in filters (the slicer). (5) When the user asks for 'top N' or 'bottom N', set both "
            + "`order` and `limit`. (6) Keep aggregator overrides off unless the user asks for one. "
            + "Always call emit_query — never respond with prose.";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Provider configuration. */
    public record Config(
            String apiKey, String model, String endpoint, double temperature, int maxTokens, Duration requestTimeout) {
        public Config {
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalArgumentException("apiKey must be non-blank");
            }
            if (model == null || model.isBlank()) {
                model = DEFAULT_MODEL;
            }
            if (endpoint == null || endpoint.isBlank()) {
                endpoint = DEFAULT_ENDPOINT;
            }
            if (maxTokens <= 0) {
                maxTokens = 4096;
            }
            if (requestTimeout == null) {
                requestTimeout = Duration.ofSeconds(60);
            }
        }

        public static Config of(String apiKey) {
            return new Config(apiKey, DEFAULT_MODEL, DEFAULT_ENDPOINT, 0.0, 4096, Duration.ofSeconds(60));
        }
    }

    private final Config config;
    private final HttpClient http;

    public OpenAINlAskProvider(String apiKey) {
        this(Config.of(apiKey));
    }

    public OpenAINlAskProvider(Config config) {
        this(
                config,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());
    }

    /** Package-visible ctor for tests that need to inject a fake client. */
    OpenAINlAskProvider(Config config, HttpClient http) {
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
                    .uri(URI.create(config.endpoint()))
                    .timeout(config.requestTimeout())
                    .header("authorization", "Bearer " + config.apiKey())
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

        ArrayNode tools = root.putArray("tools");
        ObjectNode tool = tools.addObject();
        tool.put("type", "function");
        ObjectNode fn = tool.putObject("function");
        fn.put("name", TOOL_NAME);
        fn.put("description", "Emit a structured AiQueryRequest matching the cube schema.");
        fn.set("parameters", MAPPER.readTree(request.requestJsonSchema()));

        // Force the model to call our tool — OpenAI uses tool_choice as an object with function name.
        ObjectNode toolChoice = root.putObject("tool_choice");
        toolChoice.put("type", "function");
        ObjectNode toolChoiceFn = toolChoice.putObject("function");
        toolChoiceFn.put("name", TOOL_NAME);

        ArrayNode messages = root.putArray("messages");
        ObjectNode system = messages.addObject();
        system.put("role", "system");
        system.put(
                "content",
                SYSTEM_PROMPT + "\n\nCube schema:\n" + request.cubeSchemaJson() + "\n\nCube ref to echo: "
                        + cubeRefJson(request));

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
     * Parse an OpenAI Chat Completions response body into an {@link NlAskResponse}. Visible for
     * testing — this is the deserialisation contract.
     *
     * <p>Looks at {@code choices[0].message.tool_calls[]} for a function call to
     * {@link #TOOL_NAME}; the {@code arguments} string is the structured AiQueryRequest JSON.
     */
    static NlAskResponse parseToolResponse(String body, String model) throws IOException {
        JsonNode root = MAPPER.readTree(body);
        int inputTokens = root.path("usage").path("prompt_tokens").asInt(-1);
        int outputTokens = root.path("usage").path("completion_tokens").asInt(-1);

        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return NlAskResponse.degraded("no choices", model);
        }
        JsonNode toolCalls = choices.get(0).path("message").path("tool_calls");
        if (!toolCalls.isArray() || toolCalls.isEmpty()) {
            return NlAskResponse.degraded("no tool_calls", model);
        }
        for (JsonNode call : toolCalls) {
            JsonNode function = call.path("function");
            if (TOOL_NAME.equals(function.path("name").asText())) {
                String arguments = function.path("arguments").asText(null);
                if (arguments == null || arguments.isBlank()) {
                    return NlAskResponse.degraded("empty tool_call arguments", model);
                }
                // arguments is itself JSON-encoded as a string per the OpenAI contract — return as-is.
                return NlAskResponse.ok(arguments, model, inputTokens, outputTokens);
            }
        }
        return NlAskResponse.degraded("tool_calls did not include emit_query", model);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
