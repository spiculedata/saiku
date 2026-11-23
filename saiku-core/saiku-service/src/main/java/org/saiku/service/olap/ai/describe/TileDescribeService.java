/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.describe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * saiku#909 — Tier-1 (schema-only) tile-naming assistant. Given a compact,
 * PII-redacted summary of a query's *structure* (measures, rows, columns,
 * slicer — no data values), asks an LLM for a short title + one-line
 * description for the dashboard tile the query will render as.
 *
 * <p>Saiku's built-in {@code /ai/ask} stack ({@code NlAskProvider}) is a
 * closed, hard-coded multi-tool query/insight/view-change flow whose response
 * shape doesn't fit a plain "give me two strings" call — see
 * {@code CubeDesignerAiService} for the same observation applied to the cube
 * designer. This class follows the same shape: it replicates just the
 * transport (JDK {@link HttpClient} → Anthropic Messages API, tool-forced
 * JSON, no SDK dependency) with its own single-purpose tool and system
 * prompt. Config reuses the {@code saiku.ai.ask.*} placeholders / {@code
 * ANTHROPIC_API_KEY} env — the same upstream credentials the ask feature
 * already uses.
 *
 * <p>Never throws: transport, non-2xx, and parse failures all return a
 * {@link DescribeQueryResult#degraded(String)} — callers map that to a clean
 * HTTP error rather than propagating an upstream stack trace.
 */
public class TileDescribeService implements TileDescriber {

    private static final Logger LOG = LoggerFactory.getLogger(TileDescribeService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DEFAULT_ENDPOINT = "https://api.anthropic.com/v1/messages";
    public static final String DEFAULT_MODEL = "claude-sonnet-4-6";
    private static final String API_VERSION = "2023-06-01";
    private static final String TOOL_NAME = "emit_tile_description";
    private static final int MAX_TOKENS = 512;

    private static final String SYSTEM_PROMPT = "You are naming a dashboard tile for an OLAP query. You are "
            + "given the query's STRUCTURE ONLY — the selected measures, row/column axes, and slicer "
            + "filters — never any data values or aggregated results. Some names may already be replaced "
            + "with [REDACTED] by a data-governance filter; never guess or invent what a redacted name "
            + "might be. Using only what is present in the structure, call emit_tile_description exactly "
            + "once with a concise title (5-8 words) and a one-sentence description of what the tile "
            + "compares or shows.";

    private final String apiKey;
    private final String model;
    private final String endpoint;
    private final Duration timeout;
    private final HttpClient http;

    public TileDescribeService(String apiKey, String model, String endpoint, int timeoutSeconds) {
        this(apiKey, model, endpoint, timeoutSeconds, HttpClient.newHttpClient());
    }

    /** Package-visible ctor for tests that need to inject a fake client. */
    TileDescribeService(String apiKey, String model, String endpoint, int timeoutSeconds, HttpClient http) {
        this.apiKey = notBlank(apiKey) ? apiKey.trim() : System.getenv("ANTHROPIC_API_KEY");
        this.model = notBlank(model) ? model.trim() : DEFAULT_MODEL;
        this.endpoint = notBlank(endpoint) ? endpoint.trim() : DEFAULT_ENDPOINT;
        int t = timeoutSeconds <= 0 ? 60 : Math.min(Math.max(timeoutSeconds, 5), 900);
        this.timeout = Duration.ofSeconds(t);
        this.http = http;
    }

    /** True only when an API key is configured — callers should 503 when this is false. */
    @Override
    public boolean isConfigured() {
        return notBlank(apiKey);
    }

    /**
     * Ask the model to name the tile described by {@code querySummaryJson} (the
     * output of {@link QueryStructureSummarizer#summarize}). Returns a degraded
     * result rather than throwing when not configured or on any transport /
     * upstream / parse failure.
     */
    @Override
    public DescribeQueryResult describe(String querySummaryJson) {
        if (!isConfigured()) {
            return DescribeQueryResult.degraded("AI describe-query is not configured (no API key)");
        }
        try {
            String requestBody = buildRequestBody(querySummaryJson);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(timeout)
                    .header("content-type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", API_VERSION)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return DescribeQueryResult.degraded(
                        "HTTP " + response.statusCode() + ": " + truncate(response.body(), 200));
            }
            return parseToolResponse(response.body(), model);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.warn("describe-query upstream call failed: {}", e.toString());
            return DescribeQueryResult.degraded(
                    "Transport error: " + e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            LOG.warn("describe-query upstream call failed unexpectedly: {}", e.toString());
            return DescribeQueryResult.degraded(
                    "Unexpected error: " + e.getClass().getSimpleName());
        }
    }

    // ---------- request building ----------

    String buildRequestBody(String querySummaryJson) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", MAX_TOKENS);
        root.put("system", SYSTEM_PROMPT);

        ArrayNode tools = root.putArray("tools");
        ObjectNode tool = tools.addObject();
        tool.put("name", TOOL_NAME);
        tool.put("description", "Emit a suggested title and one-line description for the dashboard tile.");
        ObjectNode inputSchema = tool.putObject("input_schema");
        inputSchema.put("type", "object");
        ObjectNode props = inputSchema.putObject("properties");
        ObjectNode title = props.putObject("title");
        title.put("type", "string");
        title.put("description", "A concise 5-8 word title for the tile.");
        ObjectNode description = props.putObject("description");
        description.put("type", "string");
        description.put("description", "A one-sentence description of what the tile compares or shows.");
        inputSchema.putArray("required").add("title").add("description");

        ObjectNode toolChoice = root.putObject("tool_choice");
        toolChoice.put("type", "tool");
        toolChoice.put("name", TOOL_NAME);

        ArrayNode messages = root.putArray("messages");
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", "Query structure:\n" + querySummaryJson);

        return MAPPER.writeValueAsString(root);
    }

    // ---------- response parsing ----------

    /**
     * Parse an Anthropic Messages API response body. Visible for testing — this
     * is the core deserialisation contract.
     */
    static DescribeQueryResult parseToolResponse(String body, String model) throws IOException {
        JsonNode root = MAPPER.readTree(body);
        int inputTokens = root.path("usage").path("input_tokens").asInt(-1);
        int outputTokens = root.path("usage").path("output_tokens").asInt(-1);

        JsonNode content = root.path("content");
        if (content.isArray()) {
            for (JsonNode block : content) {
                if (!"tool_use".equals(block.path("type").asText())
                        || !TOOL_NAME.equals(block.path("name").asText())) {
                    continue;
                }
                JsonNode input = block.path("input");
                String title = input.path("title").asText(null);
                String description = input.path("description").asText(null);
                if (title == null || title.isBlank() || description == null || description.isBlank()) {
                    return DescribeQueryResult.degraded("empty tool_use input");
                }
                return DescribeQueryResult.ok(title.trim(), description.trim(), model, inputTokens, outputTokens);
            }
        }
        return DescribeQueryResult.degraded("no tool_use block");
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
