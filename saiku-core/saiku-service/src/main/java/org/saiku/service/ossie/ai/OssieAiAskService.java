/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.ai;

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
import org.saiku.service.olap.ai.AiDataKind;
import org.saiku.service.olap.ai.AiPolicyGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Natural-language ask layer for Ossie models (R5 of #1394). Same wire contract as the MDX
 * {@code /ai/ask} — the user supplies a plain-English question + an Ossie connection/model, the
 * service composes a system prompt with the resolved schema, calls an LLM (Anthropic or an
 * OpenAI-compatible endpoint), and gets back a structured {@link OssieAiQueryRequest} it can
 * hand to the executor.
 *
 * <p>Standalone from the MDX {@code AiAskService} because that service is tightly bound to
 * MDX-specific data types ({@code AiCubeRef}, {@code AiSchema}). Reusing the LLM plumbing would
 * mean refactoring both sides; the transport (JDK HttpClient) + prompt shape are simple enough
 * that a purpose-built Ossie service is cleaner.
 *
 * <p>Configuration mirrors the MDX side:
 *
 * <ul>
 *   <li>{@code saiku.ai.ask.provider} = {@code anthropic} | {@code openai} (default: unset →
 *       returns not-configured on every call);
 *   <li>env {@code ANTHROPIC_API_KEY} or {@code OPENAI_API_KEY} (or explicit
 *       {@code saiku.ai.ask.apiKey} property);
 *   <li>{@code saiku.ai.ask.model} = model id override;
 *   <li>{@code saiku.ai.ask.endpoint} = custom base URL (for OpenAI-compatible proxies like
 *       vLLM, Ollama, Together).
 * </ul>
 */
public class OssieAiAskService {

    private static final Logger log = LoggerFactory.getLogger(OssieAiAskService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * JSON Schema for the tool the model is forced to call. Same shape as the request-body
     * schema published on {@code GET /ai/ossie/schema/{c}/{m}} but stripped of the model
     * validation (the server re-validates every name after execution). Kept as a raw string
     * because both Anthropic ({@code input_schema}) and OpenAI ({@code function.parameters})
     * accept the JSON-in-JSON as a nested object.
     */
    private static final String EMIT_QUERY_TOOL_SCHEMA = "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            + "\"connection\":{\"type\":\"string\"},"
            + "\"model\":{\"type\":\"string\"},"
            + "\"rows\":{\"type\":\"array\","
            + "\"items\":{\"type\":\"object\",\"required\":[\"dataset\",\"field\"],"
            + "\"properties\":{\"dataset\":{\"type\":\"string\"},\"field\":{\"type\":\"string\"}}}},"
            + "\"columns\":{\"type\":\"array\","
            + "\"items\":{\"type\":\"object\",\"required\":[\"dataset\",\"field\"],"
            + "\"properties\":{\"dataset\":{\"type\":\"string\"},\"field\":{\"type\":\"string\"}}}},"
            + "\"values\":{\"type\":\"array\","
            + "\"items\":{\"type\":\"object\",\"required\":[\"metric\"],"
            + "\"properties\":{\"metric\":{\"type\":\"string\"},\"aggregation\":{\"type\":\"string\"}}}},"
            + "\"filters\":{\"type\":\"array\"},"
            + "\"sorts\":{\"type\":\"array\"},"
            + "\"limit\":{\"type\":\"integer\"}"
            + "}}";

    private static final String SYSTEM_PROMPT = "You are a data analyst assistant scoped to a "
            + "single Ossie semantic model. When the user asks a question about the data, translate "
            + "it into a JSON OssieAiQueryRequest that the server will execute. Rules:\n"
            + "1. Every dataset / field / metric name MUST be present in the schema provided below. "
            + "Never invent names.\n"
            + "2. Echo the connection and model exactly as provided in the user context.\n"
            + "3. Prefer rows[] for the dimension the user wants to break down by; use columns[] "
            + "only when the user explicitly compares across two axes.\n"
            + "4. Put time / region restrictions in filters[].\n"
            + "5. For 'top N' or 'bottom N', set both sorts[] and limit.\n"
            + "6. Keep aggregation overrides off unless the user asks for one.\n"
            + "7. When the question is off-topic (general knowledge, coding help, weather, math), "
            + "reply with a JSON {\"error\":\"OFF_TOPIC\", \"message\":\"...\"} instead of a query.\n"
            + "Always return a single JSON object — never prose.";

    private final HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** Provider config resolved from env/props at boot. Kept immutable. */
    public record Config(String provider, String apiKey, String model, String endpoint, Duration requestTimeout) {

        public boolean isConfigured() {
            return provider != null && !provider.isBlank() && apiKey != null && !apiKey.isBlank();
        }
    }

    private final Config config;

    /**
     * saiku#1554. Dedicated LLM-egress guard, mirroring {@code AiAskService.egressGuard} on the MDX
     * side. Answers "may raw column sample VALUES leave the box to a third-party LLM vendor?" —
     * resolved from {@code SAIKU_AI_LLM_EGRESS} / {@code ai.llm.egress}, SEPARATE from the
     * data-return {@code AiPolicyGuard} on {@code SAIKU_AI_POLICY}. Injected via setter so existing
     * construction stays unchanged. FAIL-CLOSED: a null (unwired) guard denies egress, so an
     * unconfigured instance strips the schema's sample values rather than leaking them.
     */
    private AiPolicyGuard egressGuard;

    public OssieAiAskService() {
        this(resolveFromEnvAndProps());
    }

    public OssieAiAskService(Config config) {
        this.config = config;
        if (config.isConfigured()) {
            log.info("Ossie AI ask ENABLED (provider={}, model={})", config.provider, config.model);
        } else {
            log.info("Ossie AI ask DISABLED (set saiku.ai.ask.provider + API key to enable)");
        }
    }

    /** Spring setter — wired to {@code aiLlmEgressGuard} in {@code saiku-beans.xml}. */
    public void setEgressGuard(AiPolicyGuard egressGuard) {
        this.egressGuard = egressGuard;
    }

    /** The dedicated LLM-egress guard, or {@code null} if not wired (treated as egress-denied). */
    public AiPolicyGuard egressGuard() {
        return egressGuard;
    }

    /**
     * Whether the schema's raw column sample VALUES may egress to the LLM vendor under the active
     * egress posture. FAIL-CLOSED: a null (unwired) guard, or a guard whose posture doesn't permit
     * the classified tier, returns {@code false} — sample values are stripped and only schema
     * metadata reaches the vendor.
     *
     * <p><b>Classification ({@code AiDataKind}) — flagged for SEC review.</b> The datum here is
     * {@code OssieAiSchema.Field.sampleValues}: raw distinct column values read straight from the
     * warehouse. A strict parity argument would classify these as {@link AiDataKind#SAMPLE_MEMBERS}
     * (floor tier), since the projector already drops {@code pii=true} fields (saiku#902 parity) and
     * the MDX side sends its PII-filtered sample members at every posture. We deliberately chose the
     * MORE CONSERVATIVE tier {@link AiDataKind#AGGREGATED_RESULT_VALUES} instead, because: (1) these
     * are raw warehouse VALUES, not curated captions, and the Ossie layer carries only a coarse
     * field-level {@code pii} flag — it lacks the per-value PII guarantee the MDX
     * {@code SAMPLE_MEMBERS} tier documents ("already PII-filtered by saiku#902"); (2) at the higher
     * tier the safe default posture ({@code schema-only}) strips the raw values and sends
     * metadata-only, while an operator who trusts the vendor can opt in with
     * {@code SAIKU_AI_LLM_EGRESS=aggregated} — giving a real, testable gate rather than a no-op at
     * every wired posture. SEC: downgrade to {@code SAMPLE_MEMBERS} (one-line change) if strict
     * MDX-schema parity is preferred over this conservative posture.
     */
    private boolean egressPermitsSampleValues() {
        return egressGuard != null && egressGuard.canSend(AiDataKind.AGGREGATED_RESULT_VALUES);
    }

    public boolean isConfigured() {
        return config.isConfigured();
    }

    public String describe() {
        if (!config.isConfigured()) return "not configured";
        return config.provider + " (" + config.model + ")";
    }

    /**
     * Backward-compatible single-turn ask. Delegates to the history-aware overload with an empty
     * conversation.
     */
    public AskResult ask(String question, OssieAiSchema schema, String connectionName, String modelName) {
        return ask(question, schema, connectionName, modelName, java.util.List.of());
    }

    /**
     * Ask the LLM to translate {@code question} into an {@link OssieAiQueryRequest} against
     * {@code schema}. When {@code history} is non-empty it's prepended to the messages so the
     * LLM has context for follow-up questions like "what about by channel?" (#1398).
     *
     * <p>Each history entry is {@link ChatTurn} with a {@code role} (user|assistant) and text
     * {@code content}. Callers are responsible for turn ordering — the service passes them
     * through verbatim in the order supplied.
     */
    public AskResult ask(
            String question,
            OssieAiSchema schema,
            String connectionName,
            String modelName,
            java.util.List<ChatTurn> history) {
        if (!config.isConfigured()) {
            return new AskResult(null, null, "provider not configured", null);
        }
        String userMessage;
        try {
            userMessage = buildUserMessage(question, schema, connectionName, modelName);
        } catch (Exception e) {
            return new AskResult(null, null, "failed to serialise schema: " + e.getMessage(), null);
        }

        try {
            String rawJson;
            if ("anthropic".equalsIgnoreCase(config.provider)) {
                rawJson = callAnthropic(userMessage, history);
            } else if ("openai".equalsIgnoreCase(config.provider)) {
                rawJson = callOpenAiCompatible(userMessage, history);
            } else {
                return new AskResult(null, null, "unknown provider: " + config.provider, null);
            }
            if (rawJson == null || rawJson.isBlank()) {
                return new AskResult(null, null, "empty response from provider", null);
            }
            JsonNode parsed = MAPPER.readTree(rawJson);
            if (parsed.has("error")) {
                return new AskResult(null, null, parsed.path("message").asText("off-topic"), rawJson);
            }
            OssieAiQueryRequest req = MAPPER.treeToValue(parsed, OssieAiQueryRequest.class);
            // Enforce connection + model — the LLM might hallucinate different values.
            req.setConnection(connectionName);
            req.setModel(modelName);
            return new AskResult(req, null, null, rawJson);
        } catch (Exception e) {
            log.warn("Ossie ask call failed: {}", e.getMessage());
            return new AskResult(null, null, "provider call failed: " + e.getMessage(), null);
        }
    }

    /**
     * Build the user message sent to the LLM, applying the saiku#1554 egress projection to the
     * schema first. Package-visible so the egress strip can be unit-tested without a live provider /
     * network round-trip. When the egress guard doesn't permit sample-value egress the schema is
     * projected to metadata-only ({@link OssieAiSchema#toAgentView(boolean)}) before serialisation,
     * so raw column values never reach the vendor.
     */
    String buildUserMessage(String question, OssieAiSchema schema, String connectionName, String modelName)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        boolean includeSamples = egressPermitsSampleValues();
        if (!includeSamples) {
            // No data in the log — just the fact that sample values were withheld by policy.
            log.debug("Ossie schema sample values withheld from LLM by egress policy; ask proceeds metadata-only");
        }
        String schemaJson = MAPPER.writeValueAsString(schema.toAgentView(includeSamples));
        return "Connection: " + connectionName + "\nModel: " + modelName
                + "\n\nSchema (JSON):\n```json\n" + schemaJson
                + "\n```\n\nQuestion: " + question
                + "\n\nRespond with a single JSON object (no prose). The object must be a valid "
                + "OssieAiQueryRequest with fields: connection, model, rows[], columns[], values[], "
                + "filters[], sorts[], limit. Set connection='" + connectionName + "' and model='"
                + modelName + "'.";
    }

    /**
     * Result of an ask call.
     *
     * @param request     the parsed OssieAiQueryRequest (null on off-topic / failure)
     * @param narration   optional prose explanation to show the user alongside the executed result
     * @param error       null on success; otherwise a short error message for the client
     * @param rawResponse the LLM's raw JSON response — useful for debugging
     */
    public record AskResult(OssieAiQueryRequest request, String narration, String error, String rawResponse) {}

    /**
     * One turn in an ask conversation. {@code role} is {@code user} or {@code assistant};
     * {@code content} is the raw prose that turn produced. History is passed to the LLM in
     * order so it can resolve follow-up references ("what about by channel?").
     */
    public record ChatTurn(String role, String content) {}

    // ---------------- Provider transports ----------------

    private String callAnthropic(String userMessage, java.util.List<ChatTurn> history)
            throws IOException, InterruptedException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", config.model);
        body.put("max_tokens", 4096);
        body.put("system", SYSTEM_PROMPT);

        // #1397 — force structured output via tool_use. The tool's input_schema mirrors the
        // OssieAiQueryRequest wire shape; tool_choice locks the model into calling it. No
        // fence-stripping needed — the response arrives as a JSON object in tool_use.input.
        ArrayNode tools = body.putArray("tools");
        ObjectNode tool = tools.addObject();
        tool.put("name", "emit_ossie_query");
        tool.put("description", "Emit an OssieAiQueryRequest for the executor to run.");
        try {
            tool.set("input_schema", MAPPER.readTree(EMIT_QUERY_TOOL_SCHEMA));
        } catch (Exception e) {
            throw new IOException("emit_ossie_query schema unparseable", e);
        }
        ObjectNode toolChoice = body.putObject("tool_choice");
        toolChoice.put("type", "tool");
        toolChoice.put("name", "emit_ossie_query");

        ArrayNode messages = body.putArray("messages");
        for (ChatTurn t : history) {
            ObjectNode h = messages.addObject();
            h.put("role", t.role());
            h.put("content", t.content());
        }
        ObjectNode m = messages.addObject();
        m.put("role", "user");
        m.put("content", userMessage);

        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(config.endpoint != null ? config.endpoint : "https://api.anthropic.com/v1/messages"))
                .timeout(config.requestTimeout)
                .header("content-type", "application/json")
                .header("x-api-key", config.apiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        HttpResponse<String> resp = httpClient.send(req.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new IOException("anthropic HTTP " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode parsed = MAPPER.readTree(resp.body());
        // Walk content blocks for the tool_use block. tool_choice forces the model to emit it.
        JsonNode content = parsed.path("content");
        if (content.isArray()) {
            for (JsonNode block : content) {
                if ("tool_use".equals(block.path("type").asText())) {
                    JsonNode input = block.path("input");
                    if (input.isObject()) return MAPPER.writeValueAsString(input);
                }
            }
            // Refusal path: model emitted a text block instead of tool_use. That happens when
            // the question is genuinely off-topic — treat as OFF_TOPIC error.
            if (!content.isEmpty() && "text".equals(content.get(0).path("type").asText())) {
                ObjectNode refuse = MAPPER.createObjectNode();
                refuse.put("error", "OFF_TOPIC");
                refuse.put("message", content.get(0).path("text").asText("(no reason)"));
                return refuse.toString();
            }
        }
        return null;
    }

    private String callOpenAiCompatible(String userMessage, java.util.List<ChatTurn> history)
            throws IOException, InterruptedException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", config.model);
        body.put("temperature", 0);

        // #1397 — force structured output via tool_choice: required. The function tool's
        // parameters mirror the OssieAiQueryRequest wire shape; the model must emit them.
        ArrayNode tools = body.putArray("tools");
        ObjectNode toolWrap = tools.addObject();
        toolWrap.put("type", "function");
        ObjectNode fn = toolWrap.putObject("function");
        fn.put("name", "emit_ossie_query");
        fn.put("description", "Emit an OssieAiQueryRequest for the executor to run.");
        try {
            fn.set("parameters", MAPPER.readTree(EMIT_QUERY_TOOL_SCHEMA));
        } catch (Exception e) {
            throw new IOException("emit_ossie_query schema unparseable", e);
        }
        ObjectNode choiceObj = body.putObject("tool_choice");
        choiceObj.put("type", "function");
        ObjectNode choiceFn = choiceObj.putObject("function");
        choiceFn.put("name", "emit_ossie_query");

        ArrayNode messages = body.putArray("messages");
        ObjectNode sys = messages.addObject();
        sys.put("role", "system");
        sys.put("content", SYSTEM_PROMPT);
        for (ChatTurn t : history) {
            ObjectNode h = messages.addObject();
            h.put("role", t.role());
            h.put("content", t.content());
        }
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", userMessage);

        String url = config.endpoint != null ? config.endpoint : "https://api.openai.com/v1/chat/completions";
        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(config.requestTimeout)
                .header("content-type", "application/json")
                .header("authorization", "Bearer " + config.apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        HttpResponse<String> resp = httpClient.send(req.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new IOException("openai HTTP " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode parsed = MAPPER.readTree(resp.body());
        JsonNode choices = parsed.path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            JsonNode msg = choices.get(0).path("message");
            JsonNode toolCalls = msg.path("tool_calls");
            if (toolCalls.isArray() && !toolCalls.isEmpty()) {
                String argJson =
                        toolCalls.get(0).path("function").path("arguments").asText();
                if (argJson != null && !argJson.isBlank()) return argJson;
            }
            // Fallback: refusal via message.content — same OFF_TOPIC shape as Anthropic.
            String refuseText = msg.path("content").asText(null);
            if (refuseText != null && !refuseText.isBlank()) {
                ObjectNode refuse = MAPPER.createObjectNode();
                refuse.put("error", "OFF_TOPIC");
                refuse.put("message", refuseText);
                return refuse.toString();
            }
        }
        return null;
    }

    // ---------------- Config resolution ----------------

    private static Config resolveFromEnvAndProps() {
        String provider = System.getProperty("saiku.ai.ask.provider");
        if (provider == null || provider.isBlank()) provider = System.getenv("SAIKU_AI_ASK_PROVIDER");
        String apiKey = System.getProperty("saiku.ai.ask.apiKey");
        if (apiKey == null || apiKey.isBlank()) {
            if ("anthropic".equalsIgnoreCase(provider)) apiKey = System.getenv("ANTHROPIC_API_KEY");
            else if ("openai".equalsIgnoreCase(provider)) apiKey = System.getenv("OPENAI_API_KEY");
        }
        String model = System.getProperty("saiku.ai.ask.model");
        if (model == null || model.isBlank()) {
            if ("anthropic".equalsIgnoreCase(provider)) model = "claude-sonnet-4-6";
            else if ("openai".equalsIgnoreCase(provider)) model = "gpt-4o-mini";
        }
        String endpoint = System.getProperty("saiku.ai.ask.endpoint");
        return new Config(provider, apiKey, model, endpoint, Duration.ofSeconds(30));
    }
}
