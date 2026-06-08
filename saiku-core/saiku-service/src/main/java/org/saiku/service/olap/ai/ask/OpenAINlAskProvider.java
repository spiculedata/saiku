/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
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
 * The shared request/response orchestration lives in {@link AbstractNlAskProvider}.
 */
public final class OpenAINlAskProvider extends AbstractNlAskProvider {

    /** Default model id. Bump when OpenAI publishes a newer GA structured-output model. */
    public static final String DEFAULT_MODEL = "gpt-4o-mini";

    /** Default endpoint. Override via {@link Config#endpoint()} for OpenAI-compatible servers. */
    public static final String DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions";

    private static final String SYSTEM_PROMPT = "You are a Mondrian OLAP query assistant scoped to a "
            + "single cube. Your job is to translate a user's natural-language question about the "
            + "cube's data into a single AiQueryRequest by calling the emit_query function. "
            + "SCOPE GUARDRAIL: If the user's question is NOT about querying this cube's data — e.g. "
            + "general knowledge, coding help, weather, math, prose composition, jokes, advice, "
            + "personal questions, or anything you couldn't answer with measures/dimensions from the "
            + "schema below — you MUST call the refuse_off_topic function with a one-sentence reason. "
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
            + "Always call exactly one function — never respond with prose.";

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

    public OpenAINlAskProvider(String apiKey) {
        this(Config.of(apiKey));
    }

    public OpenAINlAskProvider(Config config) {
        this(config, defaultHttpClient());
    }

    /** Package-visible ctor for tests that need to inject a fake client. */
    OpenAINlAskProvider(Config config, HttpClient http) {
        super(http);
        if (config == null) {
            throw new IllegalArgumentException("config");
        }
        this.config = config;
    }

    // ---------- provider hooks ----------

    @Override
    protected String endpoint() {
        return config.endpoint();
    }

    @Override
    protected String model() {
        return config.model();
    }

    @Override
    protected Duration requestTimeout() {
        return config.requestTimeout();
    }

    @Override
    protected void applyAuthHeaders(HttpRequest.Builder builder) {
        builder.header("authorization", "Bearer " + config.apiKey());
    }

    // ---------- request building ----------

    @Override
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

        // Refusal path — model picks this when the user's question isn't
        // about the cube. Stops the AI key becoming a free general LLM proxy.
        ObjectNode refusalTool = tools.addObject();
        refusalTool.put("type", "function");
        ObjectNode refusalFn = refusalTool.putObject("function");
        refusalFn.put("name", REFUSAL_TOOL_NAME);
        refusalFn.put(
                "description",
                "Refuse a question that isn't about querying this cube's data. Call with a one-sentence reason.");
        ObjectNode refusalParams = refusalFn.putObject("parameters");
        refusalParams.put("type", "object");
        ObjectNode refusalProps = refusalParams.putObject("properties");
        ObjectNode reasonProp = refusalProps.putObject("reason");
        reasonProp.put("type", "string");
        reasonProp.put("description", "One-sentence explanation of why the question is off-topic.");
        refusalParams.putArray("required").add("reason");

        // tool_choice "required" — model must call a function, picks emit_query
        // OR refuse_off_topic. Was forced-emit_query before; that would have
        // the model invent queries for off-topic questions.
        root.put("tool_choice", "required");

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

    // ---------- response parsing ----------

    @Override
    protected NlAskResponse doParseToolResponse(String body, String model) throws IOException {
        return parseToolResponse(body, model);
    }

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
            String fnName = function.path("name").asText();
            if (TOOL_NAME.equals(fnName)) {
                String arguments = function.path("arguments").asText(null);
                if (arguments == null || arguments.isBlank()) {
                    return NlAskResponse.degraded("empty tool_call arguments", model);
                }
                // arguments is itself JSON-encoded as a string per the OpenAI contract — return as-is.
                return NlAskResponse.ok(arguments, model, inputTokens, outputTokens);
            }
            if (REFUSAL_TOOL_NAME.equals(fnName)) {
                String reason = "Question is not about the cube.";
                String argsStr = function.path("arguments").asText("");
                if (!argsStr.isBlank()) {
                    JsonNode args = MAPPER.readTree(argsStr);
                    reason = args.path("reason").asText(reason);
                }
                return NlAskResponse.degraded(REFUSAL_REASON_PREFIX + reason, model);
            }
        }
        return NlAskResponse.degraded("tool_calls did not include emit_query", model);
    }
}
