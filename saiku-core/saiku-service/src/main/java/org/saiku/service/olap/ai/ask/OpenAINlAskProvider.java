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

    private static final String SYSTEM_PROMPT = "You are a Mondrian OLAP analyst assistant scoped to a "
            + "single cube. You have FOUR functions — pick exactly one based on the user's intent.\n\n"
            + "FUNCTION CHOICE:\n"
            + "  - emit_query: the user wants new data or a different breakdown of the cube. "
            + "Translate their question into a single AiQueryRequest matching the schema.\n"
            + "  - emit_insight: the user wants ANALYSIS of the data currently on screen ('spot trends', "
            + "'what's interesting', 'summarise this', 'why is X high'). The user's current cellset is "
            + "provided as a digest below; reason from it. Do NOT build a new query. Return a markdown "
            + "analysis that references specific row/column captions and figures from the digest.\n"
            + "  - emit_view_change: the user wants to change HOW the existing data is displayed "
            + "('switch to chart', 'show this as a bar chart', 'back to grid', 'best chart for this'). "
            + "Pick viewMode + chartType from the catalog. Do NOT re-query. If the user just says 'chart' "
            + "or 'best chart', pick the chartType that best fits the cellset shape (time-series → line, "
            + "few categories → bar/pie, geo → map, two-dim categorical → heatmap, etc).\n"
            + "  - refuse_off_topic: SCOPE GUARDRAIL. If the question is NOT about this cube's data, call "
            + "refuse_off_topic with a one-sentence reason. Do not invent cube queries to satisfy "
            + "off-topic questions.\n\n"
            + "DISAMBIGUATION RULES:\n"
            + "  (a) If the cellset digest is absent or empty, you cannot do insight or sensibly route a "
            + "view change — fall back to emit_query if the question implies a fresh breakdown, otherwise "
            + "refuse_off_topic.\n"
            + "  (b) If the user explicitly names a different dimension/measure/filter that's NOT on the "
            + "current cellset, prefer emit_query.\n"
            + "  (c) For ambiguous 'what about by region?' on an existing cellset, prefer emit_query.\n\n"
            + "CRITICAL RULES for emit_query: (1) Every name field MUST refer to a member, measure, "
            + "dimension, hierarchy or level present in the provided cube schema — never invent names. "
            + "(2) Echo the connectionName, catalog, schema and cubeName from the provided cube ref "
            + "exactly. (3) Prefer rows for the dimension the user wants to break down by; use columns "
            + "only when the user explicitly compares across two axes. (4) Put time / region restrictions "
            + "in filters (the slicer). (5) When the user asks for 'top N' or 'bottom N', set both "
            + "`order` and `limit`. (6) Keep aggregator overrides off unless the user asks for one.\n\n"
            + "Always call exactly ONE function — never respond with prose.";

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

        // emit_query — structured AiQueryRequest.
        ObjectNode tool = tools.addObject();
        tool.put("type", "function");
        ObjectNode fn = tool.putObject("function");
        fn.put("name", TOOL_NAME);
        fn.put("description", "Emit a structured AiQueryRequest matching the cube schema.");
        fn.set("parameters", MAPPER.readTree(request.requestJsonSchema()));

        // emit_insight — markdown analysis of the user's current cellset.
        ObjectNode insightTool = tools.addObject();
        insightTool.put("type", "function");
        ObjectNode insightFn = insightTool.putObject("function");
        insightFn.put("name", INSIGHT_TOOL_NAME);
        insightFn.put(
                "description",
                "Analyse the user's CURRENT cellset (provided as a digest in the system prompt) and "
                        + "return a markdown explanation. Use when the user asks about trends, "
                        + "comparisons, summaries, or 'what's interesting' about the data already on screen. "
                        + "Do not propose a new query.");
        insightFn.set("parameters", insightInputSchema());

        // emit_view_change — viewMode + chartType.
        ObjectNode viewTool = tools.addObject();
        viewTool.put("type", "function");
        ObjectNode viewFn = viewTool.putObject("function");
        viewFn.put("name", VIEW_CHANGE_TOOL_NAME);
        viewFn.put(
                "description",
                "Change how the user's CURRENT cellset is displayed (grid vs chart, chart type). "
                        + "Use when the user asks to switch view or pick a chart that fits the data. Do "
                        + "not propose a new query.");
        viewFn.set("parameters", viewChangeInputSchema());

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

        // tool_choice "required" — model must call exactly one function, but is free to choose
        // among the four. Forced-emit_query (the very first iteration of this feature) would have
        // the model invent queries for off-topic / analytical / view-change questions.
        root.put("tool_choice", "required");

        ArrayNode messages = root.putArray("messages");
        ObjectNode system = messages.addObject();
        system.put("role", "system");
        StringBuilder sys = new StringBuilder(SYSTEM_PROMPT);
        sys.append("\n\nCube schema:\n").append(request.cubeSchemaJson());
        sys.append("\n\nCube ref to echo: ").append(cubeRefJson(request));
        sys.append("\n\nChart-type catalog (for emit_view_change):\n").append(chartTypeCatalogText());
        if (request.cellsetDigest() != null && !request.cellsetDigest().isBlank()) {
            sys.append("\n\nUser's current cellset (markdown digest — use for emit_insight / "
                            + "emit_view_change reasoning):\n")
                    .append(truncate(request.cellsetDigest(), 8000));
        } else {
            sys.append("\n\nNo current cellset on screen — emit_insight and emit_view_change are "
                    + "unavailable for this turn.");
        }
        system.put("content", sys.toString());

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
            String arguments = function.path("arguments").asText(null);
            // OpenAI returns the function arguments as a JSON-encoded STRING (not a nested object)
            // — so the value here is itself JSON ready for re-parse by the routing layer.
            if (TOOL_NAME.equals(fnName)) {
                if (arguments == null || arguments.isBlank()) {
                    return NlAskResponse.degraded("empty tool_call arguments", model);
                }
                return NlAskResponse.okQuery(arguments, model, inputTokens, outputTokens);
            }
            if (INSIGHT_TOOL_NAME.equals(fnName)) {
                if (arguments == null || arguments.isBlank()) {
                    return NlAskResponse.degraded("empty insight tool arguments", model);
                }
                return NlAskResponse.okInsight(arguments, model, inputTokens, outputTokens);
            }
            if (VIEW_CHANGE_TOOL_NAME.equals(fnName)) {
                if (arguments == null || arguments.isBlank()) {
                    return NlAskResponse.degraded("empty view_change tool arguments", model);
                }
                return NlAskResponse.okViewChange(arguments, model, inputTokens, outputTokens);
            }
            if (REFUSAL_TOOL_NAME.equals(fnName)) {
                String reason = "Question is not about the cube.";
                if (arguments != null && !arguments.isBlank()) {
                    JsonNode args = MAPPER.readTree(arguments);
                    reason = args.path("reason").asText(reason);
                }
                return NlAskResponse.degraded(REFUSAL_REASON_PREFIX + reason, model);
            }
        }
        return NlAskResponse.degraded("tool_calls did not include a recognised tool", model);
    }
}
