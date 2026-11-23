/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.generate.enrich.provider;

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
import java.util.LinkedHashMap;
import java.util.Map;
import org.saiku.service.schema.generate.enrich.SuggestionSet;

/**
 * Opt-in {@link LlmProvider} that delegates to any OpenAI-compatible Chat Completions endpoint —
 * OpenAI itself, or a local/self-hosted inference server such as Ollama, vLLM, or llama.cpp's HTTP
 * shim (saiku#904). Off by default: construction requires the caller to have already resolved a
 * (possibly placeholder) API key; {@link #enrich} performs an outbound HTTP POST.
 *
 * <p>Schema-constrained output is driven by OpenAI's function-calling: we declare a single function
 * {@code return_suggestions} whose {@code parameters} schema mirrors {@link SuggestionSet} and force
 * the model to call it via {@code tool_choice}. The call's {@code arguments} string is deserialised
 * back into a {@code SuggestionSet} — no prose is parsed, only structured JSON. This mirrors {@link
 * AnthropicProvider}'s tool-use contract, translated to OpenAI's wire format; {@link
 * AnthropicProvider#serializeDraftForPrompt} is reused verbatim since the draft-to-prompt projection
 * doesn't depend on which provider reads it.
 *
 * <p>On parse failure or a missing tool call, the provider returns a degraded empty {@link
 * SuggestionSet} rather than throwing, per the {@link LlmProvider} contract.
 *
 * <p>This class talks to the HTTP API directly using JDK 17 {@link HttpClient} — no OpenAI SDK
 * dependency. Model id, endpoint, temperature, max_tokens and request timeout are configurable so
 * the same class serves {@code ai.provider=openai} and {@code ai.provider=ollama} alike.
 */
public final class OpenAiCompatProvider implements LlmProvider {

    /** Default model id for the hosted OpenAI endpoint. */
    public static final String DEFAULT_MODEL = "gpt-4o-mini";

    /** Default endpoint — hosted OpenAI. Override via {@link Config#endpoint()} for Ollama etc. */
    public static final String DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions";

    private static final String TOOL_NAME = "return_suggestions";

    private static final String SYSTEM_PROMPT = "You are a Mondrian OLAP schema assistant. Review the "
            + "provided draft schema and emit refinement suggestions as renames, hierarchies, "
            + "aggregator changes, degenerate-dimension promotions, or ignore ops using the "
            + "return_suggestions function. Do not propose structural changes such as new cubes or "
            + "new joins — only refinements of what is already in the draft. Keep rationales "
            + "short. Use the STABLE-ID targetPath convention (physical table/column names, NOT "
            + "user-visible captions): cubes/{factTable}, "
            + "cubes/{factTable}/dimensions/{dimTable}, "
            + "cubes/{factTable}/dimensions/{dimTable}/hierarchies/{pkColumn}, "
            + "cubes/{factTable}/dimensions/{dimTable}/hierarchies/{pkColumn}/levels/{levelColumn}, "
            + "cubes/{factTable}/measures/{measureColumn} "
            + "(use the literal 'count_star' for COUNT_STAR measures), "
            + "sharedDimensions/{dimTable}. "
            + "Always call the return_suggestions function; never answer in prose.";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Hand-written JSON Schema describing a {@link SuggestionSet} — same shape as {@link AnthropicProvider}'s. */
    private static final String PARAMETERS_SCHEMA_JSON = "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            + "  \"ops\":{"
            + "    \"type\":\"array\","
            + "    \"items\":{"
            + "      \"type\":\"object\","
            + "      \"properties\":{"
            + "        \"op\":{\"type\":\"string\",\"enum\":["
            + "            \"rename\",\"hierarchy\",\"aggregator\",\"degenerateDim\",\"ignore\"]},"
            + "        \"targetPath\":{\"type\":\"string\"},"
            + "        \"confidence\":{\"type\":\"number\"},"
            + "        \"rationale\":{\"type\":\"string\"},"
            + "        \"oldCaption\":{\"type\":[\"string\",\"null\"]},"
            + "        \"newCaption\":{\"type\":[\"string\",\"null\"]},"
            + "        \"description\":{\"type\":[\"string\",\"null\"]},"
            + "        \"hierarchyName\":{\"type\":[\"string\",\"null\"]},"
            + "        \"levelColumns\":{\"type\":[\"array\",\"null\"],\"items\":{\"type\":\"string\"}},"
            + "        \"oldAggregator\":{\"type\":[\"string\",\"null\"],\"enum\":["
            + "            \"SUM\",\"COUNT\",\"AVG\",\"DISTINCT_COUNT\",\"MIN\",\"MAX\",\"COUNT_STAR\",null]},"
            + "        \"newAggregator\":{\"type\":[\"string\",\"null\"],\"enum\":["
            + "            \"SUM\",\"COUNT\",\"AVG\",\"DISTINCT_COUNT\",\"MIN\",\"MAX\",\"COUNT_STAR\",null]},"
            + "        \"factColumn\":{\"type\":[\"string\",\"null\"]},"
            + "        \"dimName\":{\"type\":[\"string\",\"null\"]}"
            + "      },"
            + "      \"required\":[\"op\",\"targetPath\",\"confidence\",\"rationale\"]"
            + "    }"
            + "  },"
            + "  \"degraded\":{\"type\":\"boolean\"}"
            + "},"
            + "\"required\":[\"ops\",\"degraded\"]"
            + "}";

    /**
     * Sentinel for {@link Config#temperature()}: a negative value omits the {@code temperature}
     * field entirely, for models (gpt-5 / o-series) that reject a custom value.
     */
    public static final double OMIT_TEMPERATURE = -1.0;

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

    public OpenAiCompatProvider(Config config) {
        this(
                config,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());
    }

    /** Package-private ctor for tests that want to inject a fake client. */
    OpenAiCompatProvider(Config config, HttpClient http) {
        if (config == null) {
            throw new IllegalArgumentException("config");
        }
        this.config = config;
        this.http = http;
    }

    @Override
    public EnrichResponse enrich(EnrichRequest request) {
        try {
            String requestBody = buildRequestBody(request);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(config.endpoint()))
                    .timeout(config.requestTimeout())
                    .header("authorization", "Bearer " + config.apiKey())
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return degraded("HTTP " + response.statusCode() + ": " + truncate(response.body(), 200));
            }
            return parseToolResponse(response.body());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return degraded("Transport error: " + e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            return degraded("Unexpected error: " + e.getClass().getSimpleName());
        }
    }

    // ---------- request building ----------

    private String buildRequestBody(EnrichRequest request) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", config.model());
        root.put("max_tokens", config.maxTokens());
        // Only send temperature when explicitly set (>= 0) — see OMIT_TEMPERATURE.
        if (config.temperature() >= 0) {
            root.put("temperature", config.temperature());
        }

        ArrayNode tools = root.putArray("tools");
        ObjectNode tool = tools.addObject();
        tool.put("type", "function");
        ObjectNode fn = tool.putObject("function");
        fn.put("name", TOOL_NAME);
        fn.put("description", "Emit Mondrian schema refinement suggestions as a structured SuggestionSet.");
        fn.set("parameters", MAPPER.readTree(PARAMETERS_SCHEMA_JSON));

        ObjectNode toolChoice = root.putObject("tool_choice");
        toolChoice.put("type", "function");
        ObjectNode toolChoiceFn = toolChoice.putObject("function");
        toolChoiceFn.put("name", TOOL_NAME);

        ArrayNode messages = root.putArray("messages");
        ObjectNode system = messages.addObject();
        system.put("role", "system");
        system.put("content", SYSTEM_PROMPT);

        ObjectNode user = messages.addObject();
        user.put("role", "user");
        // Reuses AnthropicProvider's draft-to-prompt projection: package-visible, provider-agnostic.
        String draftJson = AnthropicProvider.serializeDraftForPrompt(request.draft());
        String samplesJson =
                MAPPER.writeValueAsString(request.columnSamples() == null ? Map.of() : request.columnSamples());
        user.put(
                "content",
                "Draft schema:\n"
                        + draftJson
                        + "\n\nColumn samples (up to 5 per column):\n"
                        + samplesJson
                        + "\n\nMax suggestions: "
                        + request.maxSuggestions());

        return MAPPER.writeValueAsString(root);
    }

    // ---------- response parsing ----------

    /**
     * Parse an OpenAI-compatible Chat Completions response body into an {@link EnrichResponse}.
     * Visible for testing — this is the core deserialisation contract.
     *
     * <p>Looks at {@code choices[0].message.tool_calls[]} for a call to {@link #TOOL_NAME}; its
     * {@code arguments} string (JSON-encoded per the OpenAI contract) is deserialised as a {@link
     * SuggestionSet}. If no such call exists, returns a degraded empty set.
     */
    static EnrichResponse parseToolResponse(String body) throws IOException {
        JsonNode root = MAPPER.readTree(body);

        Map<String, Object> metadata = new LinkedHashMap<>();
        String model = root.path("model").asText(null);
        if (model != null) {
            metadata.put("model", model);
        }
        JsonNode usage = root.path("usage");
        if (usage.isObject()) {
            if (usage.has("prompt_tokens")) {
                metadata.put("usage_input_tokens", usage.get("prompt_tokens").asInt());
            }
            if (usage.has("completion_tokens")) {
                metadata.put(
                        "usage_output_tokens", usage.get("completion_tokens").asInt());
            }
        }

        JsonNode choices = root.path("choices");
        if (choices.isArray()) {
            for (JsonNode choice : choices) {
                JsonNode toolCalls = choice.path("message").path("tool_calls");
                if (!toolCalls.isArray()) {
                    continue;
                }
                for (JsonNode call : toolCalls) {
                    JsonNode function = call.path("function");
                    if (!TOOL_NAME.equals(function.path("name").asText())) {
                        continue;
                    }
                    String arguments = function.path("arguments").asText(null);
                    if (arguments == null || arguments.isBlank()) {
                        continue;
                    }
                    SuggestionSet set = MAPPER.readValue(arguments, SuggestionSet.class);
                    if (set == null) {
                        set = new SuggestionSet();
                        set.setDegraded(true);
                    }
                    return new EnrichResponse(set, Map.copyOf(metadata));
                }
            }
        }

        SuggestionSet empty = new SuggestionSet();
        empty.setDegraded(true);
        metadata.put("reason", "no_tool_call");
        return new EnrichResponse(empty, Map.copyOf(metadata));
    }

    private static EnrichResponse degraded(String reason) {
        SuggestionSet set = new SuggestionSet();
        set.setDegraded(true);
        return new EnrichResponse(set, Map.of("reason", reason));
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
