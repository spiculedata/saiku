/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Shared machinery for the HTTP-backed {@link NlAskProvider} implementations (Anthropic, OpenAI and
 * any future OpenAI-compatible provider).
 *
 * <p>The {@link #ask(NlAskRequest)} orchestration — build body, POST, classify the response, and
 * convert every failure mode into a {@link NlAskResponse#degraded(String, String)} instead of
 * throwing — is identical across providers and lives here as a {@code final} method. Everything that
 * genuinely differs between providers (endpoint, auth headers, request-body JSON shape, response
 * parsing, system-prompt wording) is delegated to small abstract hooks the subclasses override.
 *
 * <p>Security: the only place the API key touches the outbound request is {@link
 * #applyAuthHeaders(HttpRequest.Builder)} (the {@code x-api-key} / {@code Authorization: Bearer}
 * header). The key is never logged, never placed in the request body, and never echoed into a
 * degraded reason string.
 *
 * <p>Package-private by design — it is an internal sharing point, not a public extension API.
 */
abstract class AbstractNlAskProvider implements NlAskProvider {

    /** Name of the structured-output tool/function both providers ask the model to call. */
    protected static final String TOOL_NAME = "emit_query";

    /** Name of the off-topic refusal tool/function — the scope guardrail (security control). */
    protected static final String REFUSAL_TOOL_NAME = "refuse_off_topic";

    /** Prefix on the degraded reason when the model refuses an off-topic question. */
    protected static final String REFUSAL_REASON_PREFIX = "OFF_TOPIC: ";

    /** Shared, thread-safe JSON mapper. */
    protected static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;

    protected AbstractNlAskProvider(HttpClient http) {
        this.http = http;
    }

    /** Default {@link HttpClient} shared by both providers — 15s connect timeout. */
    protected static HttpClient defaultHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    // ---------- provider-specific hooks ----------

    /** The HTTPS endpoint to POST to. */
    protected abstract String endpoint();

    /** The model id echoed into responses and the request body. */
    protected abstract String model();

    /** Per-request timeout. */
    protected abstract Duration requestTimeout();

    /**
     * Apply the provider's authentication headers to the outbound request. This is the single
     * chokepoint where the API key touches the request — keep it header-only.
     */
    protected abstract void applyAuthHeaders(HttpRequest.Builder builder);

    /**
     * Serialise the provider-specific request body JSON. Package-private (not {@code protected}) so
     * the subclass overrides can keep their package-visible access used by the unit tests.
     */
    abstract String buildRequestBody(NlAskRequest request) throws IOException;

    /**
     * Parse the provider-specific response body into an {@link NlAskResponse}. Named distinctly from
     * the subclasses' {@code static parseToolResponse(String, String)} test entry points so the
     * static and instance methods don't clash on erasure.
     */
    protected abstract NlAskResponse doParseToolResponse(String body, String model) throws IOException;

    // ---------- orchestration (identical across providers) ----------

    @Override
    public final NlAskResponse ask(NlAskRequest request) {
        try {
            String body = buildRequestBody(request);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint()))
                    .timeout(requestTimeout())
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            applyAuthHeaders(builder);
            HttpRequest httpRequest = builder.build();
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return NlAskResponse.degraded(
                        "HTTP " + response.statusCode() + ": " + truncate(response.body(), 200), model());
            }
            return doParseToolResponse(response.body(), model());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return NlAskResponse.degraded("Transport error: " + e.getClass().getSimpleName(), model());
        } catch (RuntimeException e) {
            return NlAskResponse.degraded("Unexpected error: " + e.getClass().getSimpleName(), model());
        }
    }

    // ---------- shared helpers ----------

    /** Serialise the cube ref the model must echo back verbatim. */
    protected static String cubeRefJson(NlAskRequest request) {
        ObjectNode r = MAPPER.createObjectNode();
        r.put("connectionName", request.cubeRef().getConnectionName());
        r.put("catalog", request.cubeRef().getCatalog());
        r.put("schema", request.cubeRef().getSchema());
        r.put("cubeName", request.cubeRef().getCubeName());
        return r.toString();
    }

    /** Cap a string at {@code max} chars, appending {@code "..."} when truncated. */
    protected static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
