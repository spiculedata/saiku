/*
 * Copyright 2026 Spicule Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.saiku.service.olap.ai.cubedesigner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The DimSum schema-authoring agent turn for the cube designer.
 *
 * <p>Saiku's built-in {@code /ai/ask} stack is a closed, hard-coded 6-tool query
 * flow whose {@code NlAskResponse} collapses the model output to one payload — it
 * cannot return raw content blocks or take an arbitrary tool set. So this
 * replicates just the transport (JDK {@link HttpClient} → Anthropic Messages
 * API, same as {@code AnthropicNlAskProvider}) but supplies the designer's OWN
 * tool set + system prompt and returns the raw {@code content[]} untouched — the
 * client (dimsum-tools.ts) executes the returned tool calls locally.
 *
 * <p>The tool schemas ({@code cube-designer/tools.json}) and system prompt
 * ({@code cube-designer/system-prompt.md}) are the same contract the Cloud
 * gateway uses, so the client's executors work unchanged. Config reuses the
 * {@code saiku.ai.ask.*} placeholders — the <strong>same setting NL-ask uses</strong>
 * (saiku#2489): {@code anthropic} (the default; key from {@code
 * saiku.ai.ask.apiKey} / {@code ANTHROPIC_API_KEY}), {@code openai} (key from
 * {@code OPENAI_API_KEY}), {@code ollama} (keyless local/self-hosted, defaults to
 * {@code http://localhost:11434/v1/chat/completions}) or {@code noop} (off).
 *
 * <p>For {@code openai} / {@code ollama} the designer speaks OpenAI-compatible
 * Chat Completions: the Anthropic tool schemas are re-shaped into OpenAI
 * {@code tools}, the Anthropic conversation (including {@code tool_result}
 * blocks) is translated on every turn, and the model's {@code tool_calls} are
 * mapped back to the {@code text} + {@code tool_use} content-block shape the UI
 * already renders. The tool execution itself stays client-side either way.
 */
public class CubeDesignerAiService {

    private static final Logger LOG = LoggerFactory.getLogger(CubeDesignerAiService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final String PROVIDER_NOOP = "noop";
    public static final String PROVIDER_ANTHROPIC = "anthropic";
    public static final String PROVIDER_OPENAI = "openai";
    /** Local/self-hosted Ollama via its OpenAI-compatible endpoint (saiku#2489, mirroring saiku#904). */
    public static final String PROVIDER_OLLAMA = "ollama";

    public static final String ENV_ANTHROPIC_API_KEY = "ANTHROPIC_API_KEY";
    public static final String ENV_OPENAI_API_KEY = "OPENAI_API_KEY";
    /** Optional — Ollama itself never checks a key; only an authenticating proxy would. */
    public static final String ENV_OLLAMA_API_KEY = "OLLAMA_API_KEY";

    public static final String DEFAULT_ANTHROPIC_ENDPOINT = "https://api.anthropic.com/v1/messages";
    public static final String DEFAULT_ANTHROPIC_MODEL = "claude-sonnet-4-6";
    public static final String DEFAULT_OPENAI_ENDPOINT = "https://api.openai.com/v1/chat/completions";
    public static final String DEFAULT_OPENAI_MODEL = "gpt-4o-mini";
    public static final String DEFAULT_OLLAMA_ENDPOINT = "http://localhost:11434/v1/chat/completions";
    public static final String DEFAULT_OLLAMA_MODEL = "llama3.1";
    /** Inert bearer token sent when Ollama is selected with no key configured. */
    public static final String OLLAMA_KEYLESS_PLACEHOLDER = "ollama";

    private static final String API_VERSION = "2023-06-01";
    private static final int MAX_TOKENS = 4096;

    /** The provider selection {@link #turn} actually uses — never null. */
    private final ResolvedProvider resolved;

    private final Duration timeout;
    private final HttpClient http;

    private final String systemPrompt;
    private final JsonNode tools;

    /** Config summary for {@code /saiku/info/diagnostics} (saiku#2489). */
    public record Descriptor(String provider, String model, String endpoint, boolean configured) {}

    private record ResolvedProvider(
            String provider, String apiKey, String model, String endpoint, boolean configured) {}

    /** Legacy constructor — {@code anthropic} with the pre-#2489 semantics (env fallback {@code ANTHROPIC_API_KEY}). */
    public CubeDesignerAiService(String apiKey, String model, String endpoint, int timeoutSeconds) {
        this(PROVIDER_ANTHROPIC, apiKey, model, endpoint, timeoutSeconds);
    }

    /** Production constructor — provider picked with the same setting NL-ask uses. */
    public CubeDesignerAiService(
            String providerName, String apiKey, String model, String endpoint, int timeoutSeconds) {
        this(providerName, apiKey, model, endpoint, timeoutSeconds, System::getenv);
    }

    /** Package-visible constructor for tests — env lookup is injectable. */
    CubeDesignerAiService(
            String providerName,
            String apiKey,
            String model,
            String endpoint,
            int timeoutSeconds,
            Function<String, String> envLookup) {
        this(providerName, apiKey, model, endpoint, timeoutSeconds, envLookup, HttpClient.newHttpClient());
    }

    /** Package-visible ctor for tests that need to inject a fake client. */
    CubeDesignerAiService(
            String providerName,
            String apiKey,
            String model,
            String endpoint,
            int timeoutSeconds,
            Function<String, String> envLookup,
            HttpClient http) {
        this.resolved = resolve(providerName, apiKey, model, endpoint, envLookup);
        int t = timeoutSeconds <= 0 ? 60 : Math.min(Math.max(timeoutSeconds, 5), 900);
        this.timeout = Duration.ofSeconds(t);
        this.http = http;
        this.systemPrompt = loadResource("/cube-designer/system-prompt.md");
        this.tools = loadJsonResource("/cube-designer/tools.json");
        if (resolved.configured()) {
            LOG.info("cube-designer AI provider: {} (model={})", resolved.provider(), resolved.model());
        } else {
            LOG.warn(
                    "cube-designer AI provider: {} — not usable (missing API key); /turn will 503.",
                    resolved.provider());
        }
    }

    // ── provider selection (mirrors NlAskProviderFactory) ─────────────────────

    private static ResolvedProvider resolve(
            String providerName, String apiKey, String model, String endpoint, Function<String, String> envLookup) {
        Function<String, String> env = envLookup == null ? name -> null : envLookup;
        String name = normalise(providerName);
        String resolvedModel = normalise(model);
        String resolvedEndpoint = normalise(endpoint);
        if (name == null || name.isEmpty()) {
            // No provider named: anthropic, the pre-#2489 behaviour (key via apiKey / env).
            name = PROVIDER_ANTHROPIC;
        }
        switch (name.toLowerCase()) {
            case PROVIDER_ANTHROPIC -> {
                String key = resolveApiKey(apiKey, ENV_ANTHROPIC_API_KEY, env);
                return new ResolvedProvider(
                        PROVIDER_ANTHROPIC,
                        key,
                        resolvedModel == null ? DEFAULT_ANTHROPIC_MODEL : resolvedModel,
                        resolvedEndpoint == null ? DEFAULT_ANTHROPIC_ENDPOINT : resolvedEndpoint,
                        key != null);
            }
            case PROVIDER_OPENAI -> {
                String key = resolveApiKey(apiKey, ENV_OPENAI_API_KEY, env);
                return new ResolvedProvider(
                        PROVIDER_OPENAI,
                        key,
                        resolvedModel == null ? DEFAULT_OPENAI_MODEL : resolvedModel,
                        resolvedEndpoint == null ? DEFAULT_OPENAI_ENDPOINT : resolvedEndpoint,
                        key != null);
            }
            case PROVIDER_OLLAMA -> {
                // Ollama itself never validates a key — an inert placeholder keeps the
                // OpenAI-compat transport happy with zero credentials configured.
                String key = resolveApiKey(apiKey, ENV_OLLAMA_API_KEY, env);
                return new ResolvedProvider(
                        PROVIDER_OLLAMA,
                        key == null ? OLLAMA_KEYLESS_PLACEHOLDER : key,
                        resolvedModel == null ? DEFAULT_OLLAMA_MODEL : resolvedModel,
                        resolvedEndpoint == null ? DEFAULT_OLLAMA_ENDPOINT : resolvedEndpoint,
                        true);
            }
            case PROVIDER_NOOP -> {
                return new ResolvedProvider(PROVIDER_NOOP, null, null, null, false);
            }
            default -> {
                LOG.warn("Unknown cube-designer AI provider '{}'; treating as not configured.", providerName);
                return new ResolvedProvider(PROVIDER_NOOP, null, null, null, false);
            }
        }
    }

    private static String resolveApiKey(String explicit, String envKey, Function<String, String> envLookup) {
        String e = normalise(explicit);
        if (e != null) {
            return e;
        }
        return normalise(envLookup.apply(envKey));
    }

    private static String normalise(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty() || (t.startsWith("${") && t.endsWith("}"))) {
            return null;
        }
        return t;
    }

    // ── public surface ─────────────────────────────────────────────────────────

    /** True when a usable provider is configured — callers should 503 when this is false. */
    public boolean isConfigured() {
        return resolved.configured();
    }

    /** Diagnostics summary for {@code /saiku/info/diagnostics}. Never returns null. */
    public Descriptor describe() {
        // Like NlAskProviderFactory.describe(): anthropic reports no endpoint (the hosted API
        // host is implied); OpenAI-compatible providers report their full endpoint URL.
        String endpoint = PROVIDER_ANTHROPIC.equals(resolved.provider()) ? null : resolved.endpoint();
        return new Descriptor(resolved.provider(), resolved.model(), endpoint, resolved.configured());
    }

    /**
     * Run one agent turn. Returns the response's {@code content} array (text +
     * tool_use blocks) in the Anthropic shape, for the client to render + execute —
     * regardless of which provider the turn went to.
     *
     * @throws IllegalStateException if the AI is not configured
     * @throws IOException on a transport / upstream error
     */
    public JsonNode turn(JsonNode messages, String canvasSummary) throws IOException {
        if (!isConfigured()) {
            throw new IllegalStateException(
                    "cube-designer AI is not configured (AI upstream not configured; no usable provider)");
        }
        if (PROVIDER_ANTHROPIC.equals(resolved.provider())) {
            return anthropicTurn(messages, canvasSummary);
        }
        return openAiTurn(messages, canvasSummary);
    }

    // ── anthropic path (unchanged, saiku#2489) ────────────────────────────────

    private JsonNode anthropicTurn(JsonNode messages, String canvasSummary) throws IOException {
        String body = buildRequestBody(messages, canvasSummary);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(resolved.endpoint()))
                .timeout(timeout)
                .header("content-type", "application/json")
                .header("x-api-key", resolved.apiKey())
                .header("anthropic-version", API_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = send(req);
        if (resp.statusCode() / 100 != 2) {
            LOG.warn("cube-designer AI turn upstream {} ", resp.statusCode());
            throw new IOException("AI provider returned HTTP " + resp.statusCode());
        }
        return parseContent(resp.body());
    }

    /**
     * Build the Anthropic Messages request body: the designer's tool set + system
     * prompt (with the per-turn canvas state appended), the caller's messages
     * verbatim, and {@code tool_choice:auto} (DimSum both calls tools and emits
     * closing prose). Package-visible for unit testing without a network call.
     */
    String buildRequestBody(JsonNode messages, String canvasSummary) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", resolved.model());
        root.put("max_tokens", MAX_TOKENS);
        root.put("system", systemWithCanvas(canvasSummary));
        root.set("tools", tools);
        ObjectNode toolChoice = MAPPER.createObjectNode();
        toolChoice.put("type", "auto");
        root.set("tool_choice", toolChoice);
        root.set("messages", messages == null ? MAPPER.createArrayNode() : messages);
        try {
            return MAPPER.writeValueAsString(root);
        } catch (IOException e) {
            throw new IllegalStateException("could not serialise AI request", e);
        }
    }

    /** Extract the {@code content} block array from an Anthropic response body. */
    JsonNode parseContent(String responseBody) throws IOException {
        JsonNode root = MAPPER.readTree(responseBody);
        JsonNode content = root.path("content");
        return content.isArray() ? content : MAPPER.createArrayNode();
    }

    // ── openai / ollama path (saiku#2489) ─────────────────────────────────────

    private JsonNode openAiTurn(JsonNode messages, String canvasSummary) throws IOException {
        String body = buildOpenAiRequestBody(messages, canvasSummary);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(resolved.endpoint()))
                .timeout(timeout)
                .header("content-type", "application/json")
                .header("authorization", "Bearer " + resolved.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = send(req);
        if (resp.statusCode() / 100 != 2) {
            LOG.warn("cube-designer AI turn upstream {} ", resp.statusCode());
            throw new IOException("AI provider returned HTTP " + resp.statusCode());
        }
        return parseOpenAiContent(resp.body());
    }

    /**
     * Build the OpenAI-compatible Chat Completions request body: the designer's
     * tool set re-shaped into OpenAI {@code tools}, the system prompt (with the
     * per-turn canvas state appended) as a leading {@code system} message, the
     * caller's Anthropic-shaped conversation translated to OpenAI messages, and
     * {@code tool_choice:"auto"} (DimSum both calls tools and emits closing prose).
     * Package-visible for unit testing without a network call.
     */
    String buildOpenAiRequestBody(JsonNode messages, String canvasSummary) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", resolved.model());
        root.put("max_tokens", MAX_TOKENS);
        root.set("messages", convertMessages(systemWithCanvas(canvasSummary), messages));
        ArrayNode openAiTools = root.putArray("tools");
        for (JsonNode tool : tools) {
            ObjectNode t = openAiTools.addObject();
            t.put("type", "function");
            ObjectNode fn = t.putObject("function");
            fn.put("name", tool.path("name").asText());
            fn.put("description", tool.path("description").asText());
            fn.set("parameters", tool.path("input_schema"));
        }
        root.put("tool_choice", "auto");
        try {
            return MAPPER.writeValueAsString(root);
        } catch (IOException e) {
            throw new IllegalStateException("could not serialise AI request", e);
        }
    }

    /**
     * Translate the client's Anthropic-shaped conversation into OpenAI messages.
     *
     * <p>Mapping (saiku#2489): the system prompt becomes a leading {@code system}
     * message; a {@code tool_use} block on an assistant turn becomes that message's
     * {@code tool_calls} entry; a {@code tool_result} block on a user turn becomes
     * a {@code role:"tool"} message carrying the same {@code tool_call_id}; text
     * blocks are concatenated into plain {@code content} strings; {@code thinking}
     * blocks are dropped (OpenAI has no equivalent). Tool-result messages are
     * emitted before any trailing user text in the same turn, which is the order
     * OpenAI expects after an assistant {@code tool_calls} message.
     */
    ArrayNode convertMessages(String system, JsonNode messages) {
        ArrayNode out = MAPPER.createArrayNode();
        out.addObject().put("role", "system").put("content", system);
        if (messages == null || !messages.isArray()) {
            return out;
        }
        for (JsonNode message : messages) {
            String role = message.path("role").asText("user");
            JsonNode content = message.path("content");
            if (content.isTextual()) {
                out.addObject().put("role", role).put("content", content.asText());
                continue;
            }
            if (!content.isArray()) {
                continue;
            }
            List<JsonNode> toolResults = new ArrayList<>();
            List<JsonNode> toolUses = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            for (JsonNode block : content) {
                String type = block.path("type").asText();
                switch (type) {
                    case "tool_result" -> toolResults.add(block);
                    case "tool_use" -> toolUses.add(block);
                    case "text" -> {
                        if (text.length() > 0) {
                            text.append("\n\n");
                        }
                        text.append(block.path("text").asText());
                    }
                    default -> {
                        // thinking + anything else has no OpenAI equivalent — drop it.
                    }
                }
            }
            if ("assistant".equals(role)) {
                ObjectNode m = out.addObject();
                m.put("role", "assistant");
                if (text.length() > 0) {
                    m.put("content", text.toString());
                }
                if (!toolUses.isEmpty()) {
                    ArrayNode calls = m.putArray("tool_calls");
                    for (JsonNode use : toolUses) {
                        ObjectNode call = calls.addObject();
                        call.put("id", use.path("id").asText());
                        call.put("type", "function");
                        ObjectNode fn = call.putObject("function");
                        fn.put("name", use.path("name").asText());
                        JsonNode input = use.path("input");
                        fn.put(
                                "arguments",
                                input.isMissingNode() || input.isNull()
                                        ? "{}"
                                        : MAPPER.valueToTree(input).toString());
                    }
                }
                continue;
            }
            // user turn — tool results first (OpenAI expects them directly after the
            // assistant tool_calls message), then any fresh user prose.
            for (JsonNode result : toolResults) {
                ObjectNode m = out.addObject();
                m.put("role", "tool");
                m.put("tool_call_id", result.path("tool_use_id").asText());
                m.put("content", textOfToolResult(result));
            }
            if (text.length() > 0) {
                out.addObject().put("role", role).put("content", text.toString());
            }
        }
        return out;
    }

    /** Flatten a {@code tool_result} block's content to the string OpenAI's {@code tool} message wants. */
    private static String textOfToolResult(JsonNode result) {
        JsonNode content = result.path("content");
        if (content.isMissingNode() || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                String text = part.path("text").asText("");
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(text);
            }
            return sb.toString();
        }
        return content.asText("");
    }

    /**
     * Map an OpenAI Chat Completions response back to the Anthropic content-block
     * shape the UI renders: the message's {@code content} becomes a {@code text}
     * block and each entry of {@code tool_calls} becomes a {@code tool_use} block
     * with {@code id}/{@code name}/{@code input}. Package-visible for unit tests.
     *
     * @throws IOException with a clear message on a malformed response — never a 500
     */
    ArrayNode parseOpenAiContent(String responseBody) throws IOException {
        JsonNode root;
        try {
            root = MAPPER.readTree(responseBody);
        } catch (IOException e) {
            throw new IOException("AI provider returned a malformed response body", e);
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IOException("AI provider returned no choices");
        }
        JsonNode message = choices.get(0).path("message");
        if (message.isMissingNode() || message.isNull()) {
            throw new IOException("AI provider response is missing the message object");
        }
        ArrayNode content = MAPPER.createArrayNode();
        if (message.path("content").isTextual()
                && !message.path("content").asText().isBlank()) {
            ObjectNode text = content.addObject();
            text.put("type", "text");
            text.put("text", message.path("content").asText());
        }
        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isArray()) {
            for (JsonNode call : toolCalls) {
                String id = call.path("id").asText(null);
                JsonNode fn = call.path("function");
                String name = fn.path("name").asText(null);
                if (id == null || id.isBlank() || name == null || name.isBlank()) {
                    throw new IOException("AI provider returned a tool call without an id or name");
                }
                ObjectNode use = content.addObject();
                use.put("type", "tool_use");
                use.put("id", id);
                use.put("name", name);
                use.set("input", parseToolArguments(fn.path("arguments"), name));
            }
        }
        if (content.isEmpty()) {
            throw new IOException("AI provider returned no content or tool calls");
        }
        return content;
    }

    /** Parse a {@code tool_call} argument string into the {@code input} object. Blank → empty object. */
    private static JsonNode parseToolArguments(JsonNode arguments, String toolName) throws IOException {
        if (arguments.isMissingNode() || arguments.isNull()) {
            return MAPPER.createObjectNode();
        }
        String raw = arguments.isTextual() ? arguments.asText() : arguments.toString();
        if (raw.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            JsonNode parsed = MAPPER.readTree(raw);
            if (!parsed.isObject()) {
                throw new IOException("AI provider returned non-object arguments for tool " + toolName);
            }
            return parsed;
        } catch (IOException e) {
            throw new IOException("AI provider returned malformed arguments for tool " + toolName, e);
        }
    }

    // ── shared ─────────────────────────────────────────────────────────────────

    private String systemWithCanvas(String canvasSummary) {
        if (normalise(canvasSummary) == null) {
            return systemPrompt;
        }
        return systemPrompt + "\n\n# CURRENT CANVAS STATE\n" + canvasSummary;
    }

    private HttpResponse<String> send(HttpRequest req) throws IOException {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new IOException("AI provider request timed out", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("cube-designer AI call interrupted", e);
        }
    }

    private static String loadResource(String path) {
        try (InputStream in = CubeDesignerAiService.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + path, e);
        }
    }

    private static JsonNode loadJsonResource(String path) {
        try {
            return MAPPER.readTree(loadResource(path));
        } catch (IOException e) {
            throw new IllegalStateException("could not parse " + path, e);
        }
    }
}
