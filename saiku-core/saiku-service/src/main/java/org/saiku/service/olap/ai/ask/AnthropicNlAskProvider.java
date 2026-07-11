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
 * The shared request/response orchestration lives in {@link AbstractNlAskProvider}.
 */
public final class AnthropicNlAskProvider extends AbstractNlAskProvider {

    /** Default model id. Bump when Anthropic publishes a newer stable Sonnet. */
    public static final String DEFAULT_MODEL = "claude-sonnet-4-6";

    private static final String ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";

    private static final String SYSTEM_PROMPT = "You are a Mondrian OLAP analyst assistant scoped to a "
            + "single cube. You have FOUR tools — pick exactly one based on the user's intent.\n\n"
            + "TOOL CHOICE:\n"
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
            + "  - refuse_off_topic: SCOPE GUARDRAIL. If the question is NOT about this cube's data — e.g. "
            + "general knowledge, coding help, weather, math, prose composition, jokes, advice, personal "
            + "questions — call refuse_off_topic with a one-sentence reason. Do not invent cube queries to "
            + "satisfy off-topic questions.\n\n"
            + "DISAMBIGUATION RULES:\n"
            + "  (a) If the cellset digest is absent or empty, you cannot do insight or sensibly route a "
            + "view change — fall back to emit_query if the question implies a fresh breakdown, otherwise "
            + "refuse_off_topic.\n"
            + "  (b) If the user explicitly names a different dimension/measure/filter that's NOT on the "
            + "current cellset, prefer emit_query even if the phrasing sounds analytical.\n"
            + "  (c) For ambiguous questions like 'what about by region?' on an existing cellset, prefer "
            + "emit_query (they want a new breakdown).\n\n"
            + "CRITICAL RULES for emit_query: (1) Every name field MUST refer to a member, measure, "
            + "dimension, hierarchy or level present in the provided cube schema — never invent names. "
            + "(2) Echo the connectionName, catalog, schema and cubeName from the provided cube ref "
            + "exactly. (3) Prefer rows for the dimension the user wants to break down by; use columns "
            + "only when the user explicitly compares across two axes. (4) Put time / region restrictions "
            + "in filters (the slicer). (5) When the user asks for 'top N' or 'bottom N', set both "
            + "`order` and `limit`. (6) Keep aggregator overrides off unless the user asks for one.\n\n"
            + "Always call exactly ONE tool — never respond with prose.";

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

    public AnthropicNlAskProvider(String apiKey) {
        this(Config.of(apiKey));
    }

    public AnthropicNlAskProvider(Config config) {
        this(config, defaultHttpClient());
    }

    /** Package-visible ctor for tests that need to inject a fake client. */
    AnthropicNlAskProvider(Config config, HttpClient http) {
        super(http);
        if (config == null) {
            throw new IllegalArgumentException("config");
        }
        this.config = config;
    }

    // ---------- provider hooks ----------

    @Override
    protected String endpoint() {
        return ENDPOINT;
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
        builder.header("x-api-key", config.apiKey()).header("anthropic-version", API_VERSION);
    }

    // ---------- request building ----------

    @Override
    String buildRequestBody(NlAskRequest request) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", config.model());
        root.put("max_tokens", config.maxTokens());
        root.put("temperature", config.temperature());

        NlAskRequest.ForceTool force = request.forceTool();
        boolean wantQuery = force == NlAskRequest.ForceTool.AUTO || force == NlAskRequest.ForceTool.QUERY;
        boolean wantInsight = force == NlAskRequest.ForceTool.AUTO || force == NlAskRequest.ForceTool.INSIGHT;
        boolean wantViewChange = force == NlAskRequest.ForceTool.AUTO || force == NlAskRequest.ForceTool.VIEW_CHANGE;

        StringBuilder system = new StringBuilder(SYSTEM_PROMPT);
        // Agent-space persona voice (saiku#1440). Prepended before the cube schema so the LLM
        // reads the "you are the FoodMart Sales Analyst" framing before it sees the raw dims and
        // measures — matches the way admins author it.
        if (request.spaceSystemPrompt() != null && !request.spaceSystemPrompt().isBlank()) {
            system.append("\n\nAgent space persona:\n").append(request.spaceSystemPrompt());
        }
        system.append("\n\nCube schema:\n").append(request.cubeSchemaJson());
        system.append("\n\nCube ref to echo: ").append(cubeRefJson(request));
        // Admin-authored skills (see AgentSkillRegistry). Rendered as a bulleted list of
        // `/<name>: <description>` — the model treats these as first-class routes.
        if (request.skillsFragment() != null && !request.skillsFragment().isBlank()) {
            system.append("\n\n").append(request.skillsFragment());
        }
        // Chart-type catalog only matters for view-change routing. Skipping it on query/insight-only
        // turns trims ~500 tokens off the prompt and shaves a noticeable chunk off response time.
        if (wantViewChange) {
            system.append("\n\nChart-type catalog (for emit_view_change):\n").append(chartTypeCatalogText());
        }
        if (request.cellsetDigest() != null && !request.cellsetDigest().isBlank()) {
            system.append("\n\nUser's current cellset (markdown digest — use for emit_insight / "
                            + "emit_view_change reasoning):\n")
                    .append(truncate(request.cellsetDigest(), 8000));
        } else if (wantInsight || wantViewChange) {
            system.append("\n\nNo current cellset on screen — emit_insight and emit_view_change are "
                    + "unavailable for this turn.");
        }
        // Current AiQueryRequest snapshot — gives the LLM full context on what's already on screen
        // so emit_query can EXTEND ('add therapeutic class to columns' should preserve existing
        // rows/measures/filters) rather than wipe-and-rewrite. Only included when query routing is
        // possible (forceTool=auto|query) — insight/view-change don't touch the query model.
        if (wantQuery
                && request.currentQueryJson() != null
                && !request.currentQueryJson().isBlank()) {
            system.append("\n\nUser's CURRENT AiQueryRequest (preserve fields when extending — when the "
                            + "user says 'add X' or 'also break down by Y', return a query that KEEPS "
                            + "existing measures/rows/columns/filters and adds the new one. Only drop a "
                            + "field when the user explicitly asks to remove it):\n")
                    .append(truncate(request.currentQueryJson(), 4000));
        }
        root.put("system", system.toString());

        ArrayNode tools = root.putArray("tools");

        // wantQuery / wantInsight / wantViewChange are computed at the top of the method (where the
        // system prompt's tool-specific sections are pruned by the same flags).
        if (wantQuery) {
            ObjectNode queryTool = tools.addObject();
            queryTool.put("name", TOOL_NAME);
            queryTool.put("description", "Emit a structured AiQueryRequest matching the cube schema.");
            queryTool.set("input_schema", MAPPER.readTree(request.requestJsonSchema()));
        }

        if (wantInsight) {
            ObjectNode insightTool = tools.addObject();
            insightTool.put("name", INSIGHT_TOOL_NAME);
            insightTool.put(
                    "description",
                    "Analyse the user's CURRENT cellset (provided as a digest in the system prompt) and "
                            + "return a markdown explanation. Use when the user asks about trends, "
                            + "comparisons, summaries, or 'what's interesting' about the data already on screen. "
                            + "Do not propose a new query.");
            insightTool.set("input_schema", insightInputSchema());
        }

        if (wantViewChange) {
            ObjectNode viewTool = tools.addObject();
            viewTool.put("name", VIEW_CHANGE_TOOL_NAME);
            viewTool.put(
                    "description",
                    "Change how the user's CURRENT cellset is displayed (grid vs chart, chart type). "
                            + "Use when the user asks to switch view or pick a chart that fits the data. Do "
                            + "not propose a new query.");
            viewTool.set("input_schema", viewChangeInputSchema());
        }

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

        // tool_choice "any" — model must call a tool, but picks among the four. Was "tool" (forced
        // emit_query) at the very start of the AI Ask feature; that path would have the model invent
        // queries for off-topic / analytical / view-change questions.
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

    // ---------- response parsing ----------

    @Override
    protected NlAskResponse doParseToolResponse(String body, String model) throws IOException {
        return parseToolResponse(body, model);
    }

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
                JsonNode input = block.path("input");
                if (TOOL_NAME.equals(toolName)) {
                    if (input.isMissingNode() || input.isNull()) {
                        return NlAskResponse.degraded("empty tool_use input", model);
                    }
                    return NlAskResponse.okQuery(MAPPER.writeValueAsString(input), model, inputTokens, outputTokens);
                }
                if (INSIGHT_TOOL_NAME.equals(toolName)) {
                    if (input.isMissingNode() || input.isNull()) {
                        return NlAskResponse.degraded("empty insight tool input", model);
                    }
                    return NlAskResponse.okInsight(MAPPER.writeValueAsString(input), model, inputTokens, outputTokens);
                }
                if (VIEW_CHANGE_TOOL_NAME.equals(toolName)) {
                    if (input.isMissingNode() || input.isNull()) {
                        return NlAskResponse.degraded("empty view_change tool input", model);
                    }
                    return NlAskResponse.okViewChange(
                            MAPPER.writeValueAsString(input), model, inputTokens, outputTokens);
                }
                if (REFUSAL_TOOL_NAME.equals(toolName)) {
                    String reason = input.path("reason").asText("Question is not about the cube.");
                    return NlAskResponse.degraded(REFUSAL_REASON_PREFIX + reason, model);
                }
            }
        }
        return NlAskResponse.degraded("no tool_use block", model);
    }
}
