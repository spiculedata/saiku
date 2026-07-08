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

    public boolean isConfigured() {
        return config.isConfigured();
    }

    public String describe() {
        if (!config.isConfigured()) return "not configured";
        return config.provider + " (" + config.model + ")";
    }

    /**
     * Ask the LLM to translate {@code question} into an {@link OssieAiQueryRequest} against
     * {@code schema}. Returns null when the model rejects the request as off-topic or when the
     * provider is unconfigured — the resource layer maps null → 503 / 400 as appropriate.
     */
    public AskResult ask(String question, OssieAiSchema schema, String connectionName, String modelName) {
        if (!config.isConfigured()) {
            return new AskResult(null, null, "provider not configured", null);
        }
        String schemaJson;
        try {
            schemaJson = MAPPER.writeValueAsString(schema);
        } catch (Exception e) {
            return new AskResult(null, null, "failed to serialise schema: " + e.getMessage(), null);
        }

        String userMessage = "Connection: " + connectionName + "\nModel: " + modelName
                + "\n\nSchema (JSON):\n```json\n" + schemaJson
                + "\n```\n\nQuestion: " + question
                + "\n\nRespond with a single JSON object (no prose). The object must be a valid "
                + "OssieAiQueryRequest with fields: connection, model, rows[], columns[], values[], "
                + "filters[], sorts[], limit. Set connection='" + connectionName + "' and model='"
                + modelName + "'.";

        try {
            String rawJson;
            if ("anthropic".equalsIgnoreCase(config.provider)) {
                rawJson = callAnthropic(userMessage);
            } else if ("openai".equalsIgnoreCase(config.provider)) {
                rawJson = callOpenAiCompatible(userMessage);
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
     * Result of an ask call.
     *
     * @param request     the parsed OssieAiQueryRequest (null on off-topic / failure)
     * @param narration   optional prose explanation to show the user alongside the executed result
     * @param error       null on success; otherwise a short error message for the client
     * @param rawResponse the LLM's raw JSON response — useful for debugging
     */
    public record AskResult(OssieAiQueryRequest request, String narration, String error, String rawResponse) {}

    // ---------------- Provider transports ----------------

    private String callAnthropic(String userMessage) throws IOException, InterruptedException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", config.model);
        body.put("max_tokens", 4096);
        body.put("system", SYSTEM_PROMPT);
        ArrayNode messages = body.putArray("messages");
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
        JsonNode content = parsed.path("content");
        if (content.isArray() && !content.isEmpty()) {
            JsonNode first = content.get(0);
            String text = first.path("text").asText();
            return extractJson(text);
        }
        return null;
    }

    private String callOpenAiCompatible(String userMessage) throws IOException, InterruptedException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", config.model);
        body.put("temperature", 0);
        // JSON-mode: force JSON-formatted output.
        ObjectNode fmt = body.putObject("response_format");
        fmt.put("type", "json_object");
        ArrayNode messages = body.putArray("messages");
        ObjectNode sys = messages.addObject();
        sys.put("role", "system");
        sys.put("content", SYSTEM_PROMPT);
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
            return choices.get(0).path("message").path("content").asText();
        }
        return null;
    }

    /**
     * Extract the JSON object from a possibly-code-fenced response. LLMs sometimes wrap the
     * object in ```json ...``` even when told not to.
     */
    private String extractJson(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        // ``` -> ``` fence stripping
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) trimmed = trimmed.substring(firstNewline + 1);
            int lastFence = trimmed.lastIndexOf("```");
            if (lastFence >= 0) trimmed = trimmed.substring(0, lastFence);
        }
        return trimmed.trim();
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
