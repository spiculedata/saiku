/*
 * Copyright 2026 Spicule Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.saiku.service.olap.ai.cubedesigner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
 * {@code saiku.ai.ask.*} placeholders / {@code ANTHROPIC_API_KEY} env.
 */
public class CubeDesignerAiService {

    private static final Logger LOG = LoggerFactory.getLogger(CubeDesignerAiService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DEFAULT_ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String DEFAULT_MODEL = "claude-sonnet-4-6";
    private static final String API_VERSION = "2023-06-01";
    private static final int MAX_TOKENS = 4096;

    private final String apiKey;
    private final String model;
    private final String endpoint;
    private final Duration timeout;
    private final HttpClient http;

    private final String systemPrompt;
    private final JsonNode tools;

    public CubeDesignerAiService(String apiKey, String model, String endpoint, int timeoutSeconds) {
        this.apiKey = notBlank(apiKey) ? apiKey.trim() : System.getenv("ANTHROPIC_API_KEY");
        this.model = notBlank(model) ? model.trim() : DEFAULT_MODEL;
        this.endpoint = notBlank(endpoint) ? endpoint.trim() : DEFAULT_ENDPOINT;
        int t = timeoutSeconds <= 0 ? 60 : Math.min(Math.max(timeoutSeconds, 5), 900);
        this.timeout = Duration.ofSeconds(t);
        this.http = HttpClient.newHttpClient();
        this.systemPrompt = loadResource("/cube-designer/system-prompt.md");
        this.tools = loadJsonResource("/cube-designer/tools.json");
    }

    /** True when an API key is configured — callers should 503 when this is false. */
    public boolean isConfigured() {
        return notBlank(apiKey);
    }

    /**
     * Run one agent turn. Returns the Anthropic response's {@code content} array
     * (text + tool_use blocks) verbatim, for the client to render + execute.
     *
     * @throws IllegalStateException if the AI is not configured (no API key)
     * @throws IOException on a transport / upstream error
     */
    public JsonNode turn(JsonNode messages, String canvasSummary) throws IOException {
        if (!isConfigured()) {
            throw new IllegalStateException("cube-designer AI is not configured (no API key)");
        }
        String body = buildRequestBody(messages, canvasSummary);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(timeout)
                .header("content-type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("cube-designer AI call interrupted", e);
        }
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
        root.put("model", model);
        root.put("max_tokens", MAX_TOKENS);
        String system = systemPrompt;
        if (notBlank(canvasSummary)) {
            system = systemPrompt + "\n\n# CURRENT CANVAS STATE\n" + canvasSummary;
        }
        root.put("system", system);
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

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
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
