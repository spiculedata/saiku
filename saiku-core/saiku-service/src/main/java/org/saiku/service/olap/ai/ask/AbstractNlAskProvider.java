/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /** Name of the query-building tool. Picks when the user wants new data / a new breakdown. */
    protected static final String TOOL_NAME = "emit_query";

    /** Name of the off-topic refusal tool/function — the scope guardrail (security control). */
    protected static final String REFUSAL_TOOL_NAME = "refuse_off_topic";

    /**
     * Name of the insight tool — picks when the user asks for analysis of the currently-rendered
     * cellset ("spot trends", "what's interesting", "summarise this"). No query is built; the model
     * just returns markdown commentary on the cellset digest it was given.
     */
    protected static final String INSIGHT_TOOL_NAME = "emit_insight";

    /**
     * Name of the view-change tool — picks when the user asks to change how the existing cellset is
     * displayed ("switch to chart", "show this as a bar chart", "back to grid"). No query is built;
     * the UI applies viewMode + chartType.
     */
    protected static final String VIEW_CHANGE_TOOL_NAME = "emit_view_change";

    /**
     * Name of the email-draft tool — picks when the user explicitly asks to email / send the
     * currently-rendered cellset. No query is built; the model returns a short prose summary the app
     * uses as the pre-filled email body. Draft-only — the AI never sends.
     */
    protected static final String EMAIL_DRAFT_TOOL_NAME = "emit_email_draft";

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
                String reason = "HTTP " + response.statusCode() + ": " + truncate(response.body(), 200);
                if (response.statusCode() == 429) {
                    return NlAskResponse.rateLimited(reason, model(), parseRetryAfterMs(response));
                }
                if (response.statusCode() == 400 && isTransientToolError(response.body())) {
                    return NlAskResponse.retryableToolError(reason, model());
                }
                return NlAskResponse.degraded(reason, model());
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

    /** Matches vendor 429-body wait phrasing, e.g. "try again in 9.512s" / "try again in 2m1.2s". */
    private static final Pattern TRY_AGAIN_IN =
            Pattern.compile("try again in (?:([0-9]+)m)?([0-9]+(?:\\.[0-9]+)?)s", Pattern.CASE_INSENSITIVE);

    /**
     * Best-effort wait hint from a 429: prefer the standard {@code Retry-After} header (seconds,
     * possibly fractional), else parse the vendor body's "try again in Xs" phrasing, else 0
     * (unknown).
     */
    static long parseRetryAfterMs(HttpResponse<String> response) {
        return parseRetryAfterMs(response.headers().firstValue("retry-after"), response.body());
    }

    /**
     * Parse core for {@link #parseRetryAfterMs(HttpResponse)}, split out so it can be unit-tested
     * without needing a real {@code HttpResponse<String>}.
     */
    static long parseRetryAfterMs(Optional<String> retryAfterHeader, String body) {
        // 1) Retry-After header — seconds (integer or fractional). (HTTP-date form not expected
        // from LLM vendors.)
        if (retryAfterHeader.isPresent()) {
            try {
                double secs = Double.parseDouble(retryAfterHeader.get().trim());
                if (secs >= 0) {
                    return (long) Math.ceil(secs * 1000);
                }
            } catch (NumberFormatException ignore) {
                // fall through to body parse
            }
        }
        // 2) Body phrasing: "Please try again in 9.512s" / "try again in 2m1.2s" (Groq).
        if (body != null) {
            Matcher m = TRY_AGAIN_IN.matcher(body);
            if (m.find()) {
                long ms = 0;
                if (m.group(1) != null) {
                    ms += Long.parseLong(m.group(1)) * 60_000; // minutes
                }
                if (m.group(2) != null) {
                    ms += (long) Math.ceil(Double.parseDouble(m.group(2)) * 1000); // seconds
                }
                return ms;
            }
        }
        return 0L; // rate-limited, no parseable hint → caller uses its default
    }

    /**
     * A Groq/LLM "the model fumbled the tool-call format" 400 — transient, worth a re-ask. Detected by
     * the vendor error code {@code tool_use_failed} in the body. Other 400s (genuinely malformed
     * requests) are NOT transient and must NOT be retried, so they fall through to a plain degrade.
     */
    static boolean isTransientToolError(String body) {
        return body != null && body.contains("tool_use_failed");
    }

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

    /**
     * Builds the JSON Schema for the {@code emit_insight} tool's input. Provider-agnostic — both
     * Anthropic and OpenAI consume the same {@code {"type":"object", "properties":...}} shape under
     * their respective tool/function definitions.
     *
     * <p>Two fields: {@code markdown} (required, the analysis body) and {@code headline} (optional
     * 1-line summary the drawer can render above the body).
     */
    protected static ObjectNode insightInputSchema() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        ObjectNode markdown = props.putObject("markdown");
        markdown.put("type", "string");
        markdown.put(
                "description",
                "The analysis itself as markdown — paragraphs, bullets, optional small tables. "
                        + "Keep it tight: 3-8 sentences for trend questions, 1-2 paragraphs for deeper "
                        + "asks. Reference specific row/column captions and figures from the cellset "
                        + "digest provided as context. Do NOT include the user's question back to them, "
                        + "do NOT prefix with 'Here is an analysis'.");
        ObjectNode headline = props.putObject("headline");
        headline.put("type", "string");
        headline.put(
                "description",
                "Optional 1-line headline (<=80 chars) shown above the markdown body in the drawer. "
                        + "Example: 'Revenue concentrated in Q4; CA leads by 38%.'");
        root.putArray("required").add("markdown");
        return root;
    }

    /**
     * Builds the JSON Schema for the {@code emit_email_draft} tool's input. Provider-agnostic — both
     * Anthropic and OpenAI consume the same {@code {"type":"object", "properties":...}} shape under
     * their respective tool/function definitions.
     *
     * <p>One field: {@code summary} (required, the email body).
     */
    protected static ObjectNode emailDraftInputSchema() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        ObjectNode summary = props.putObject("summary");
        summary.put("type", "string");
        summary.put(
                "description",
                "The email body: a short plain-prose summary of the CURRENT cellset (2-3 sentences, "
                        + "reference specific figures from the digest). No greeting, no subject line.");
        root.putArray("required").add("summary");
        return root;
    }

    /**
     * Builds the JSON Schema for the {@code emit_view_change} tool's input. {@code viewMode} +
     * {@code chartType} are constrained to the canonical catalog in {@link AiViewChangeCatalog}; the
     * routing layer rejects any value outside the enum lists.
     *
     * <p>The tool description (per-provider, not here) enumerates the chart-type catalog with hints
     * so the model has enough context to pick well.
     */
    protected static ObjectNode viewChangeInputSchema() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");

        ObjectNode viewMode = props.putObject("viewMode");
        viewMode.put("type", "string");
        ArrayNode viewEnum = viewMode.putArray("enum");
        for (String v : AiViewChangeCatalog.VIEW_MODES) {
            viewEnum.add(v);
        }
        viewMode.put("description", "Target view mode: 'grid' for tabular cellset, 'chart' for a chart render.");

        ObjectNode chartType = props.putObject("chartType");
        chartType.put("type", "string");
        ArrayNode chartEnum = chartType.putArray("enum");
        for (String id : AiViewChangeCatalog.CHART_TYPE_IDS) {
            chartEnum.add(id);
        }
        chartType.put(
                "description",
                "Chart-type id. Only meaningful when viewMode='chart'. Pick the one that fits the "
                        + "cellset shape: line/area for time series, bar/stackedBar for categorical "
                        + "comparisons, pie/donut/treemap for part-to-whole with few categories, "
                        + "heatmap for matrix views, map for geo dims. See the per-id hints above.");

        ObjectNode reason = props.putObject("reason");
        reason.put("type", "string");
        reason.put(
                "description",
                "Optional 1-sentence rationale shown in the drawer "
                        + "('Time-series → line chart fits best.', 'Few categories → pie.').");

        root.putArray("required").add("viewMode");
        return root;
    }

    /**
     * Build a human-readable enumeration of the chart-type catalog suitable for inclusion in the
     * tool description. Each line: {@code "- <id> (<label>, <group>): <hint>"}. The LLM reads this
     * once to learn what each id maps to so it picks sensibly.
     */
    protected static String chartTypeCatalogText() {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map<String, String> entry : AiViewChangeCatalog.CHART_TYPES) {
            sb.append("- ")
                    .append(entry.get("id"))
                    .append(" (")
                    .append(entry.get("label"))
                    .append(", ")
                    .append(entry.get("group"))
                    .append("): ")
                    .append(entry.get("hint"))
                    .append('\n');
        }
        return sb.toString();
    }
}
