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
import org.saiku.service.schema.generate.draft.DraftCube;
import org.saiku.service.schema.generate.draft.DraftDimension;
import org.saiku.service.schema.generate.draft.DraftHierarchy;
import org.saiku.service.schema.generate.draft.DraftLevel;
import org.saiku.service.schema.generate.draft.DraftMeasure;
import org.saiku.service.schema.generate.draft.DraftSchema;
import org.saiku.service.schema.generate.enrich.SuggestionSet;

/**
 * Opt-in {@link LlmProvider} that delegates to Anthropic's Messages API. Off by default:
 * construction requires an explicit API key; {@link #enrich} performs an outbound HTTPS POST.
 *
 * <p>Schema-constrained output is driven by Anthropic's tool-use feature: we declare a single
 * tool {@code return_suggestions} whose {@code input_schema} mirrors {@link SuggestionSet} and
 * force the model to call it via {@code tool_choice}. The tool's {@code input} object is
 * deserialised back into a {@code SuggestionSet} — no prose is parsed, only structured JSON.
 *
 * <p>On parse failure or missing tool_use block, the provider returns a degraded empty
 * {@link SuggestionSet} rather than throwing, per the {@link LlmProvider} contract.
 *
 * <p>This class talks to the HTTPS API directly using JDK 17 {@link HttpClient} — no Anthropic
 * SDK dependency. Model id, temperature, max_tokens and request timeout are configurable.
 */
public final class AnthropicProvider implements LlmProvider {

    /** Default model id. Bump this when Anthropic publishes a newer stable Sonnet. */
    public static final String DEFAULT_MODEL = "claude-sonnet-4-6";

    private static final String ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";
    private static final String TOOL_NAME = "return_suggestions";

    private static final String SYSTEM_PROMPT = "You are a Mondrian OLAP schema assistant. Review the "
            + "provided draft schema and emit refinement suggestions as renames, hierarchies, "
            + "aggregator changes, degenerate-dimension promotions, or ignore ops using the "
            + "return_suggestions tool. Do not propose structural changes such as new cubes or "
            + "new joins — only refinements of what is already in the draft. Keep rationales "
            + "short. Use the targetPath convention: cubes/{cube}, "
            + "cubes/{cube}/dimensions/{dim}, cubes/{cube}/measures/{measure}, etc. "
            + "Always call the return_suggestions tool; never answer in prose.";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Hand-written JSON Schema describing a {@link SuggestionSet}. */
    private static final String INPUT_SCHEMA_JSON = "{"
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
    private final HttpClient http;

    public AnthropicProvider(String apiKey) {
        this(Config.of(apiKey));
    }

    public AnthropicProvider(Config config) {
        this(
                config,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());
    }

    /** Package-private ctor for tests that want to inject a fake client. */
    AnthropicProvider(Config config, HttpClient http) {
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
                    .uri(URI.create(ENDPOINT))
                    .timeout(config.requestTimeout())
                    .header("x-api-key", config.apiKey())
                    .header("anthropic-version", API_VERSION)
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
        root.put("temperature", config.temperature());
        root.put("system", SYSTEM_PROMPT);

        ArrayNode tools = root.putArray("tools");
        ObjectNode tool = tools.addObject();
        tool.put("name", TOOL_NAME);
        tool.put("description", "Emit Mondrian schema refinement suggestions as a structured SuggestionSet.");
        tool.set("input_schema", MAPPER.readTree(INPUT_SCHEMA_JSON));

        ObjectNode toolChoice = root.putObject("tool_choice");
        toolChoice.put("type", "tool");
        toolChoice.put("name", TOOL_NAME);

        ArrayNode messages = root.putArray("messages");
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        String draftJson = serializeDraftForPrompt(request.draft());
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

    /**
     * Minimal inline serializer for {@link DraftSchema} — stable-enough JSON for the prompt. We do
     * not promote the test-scope {@code DraftSchemaJson} helper into main here; this purpose
     * (prompt context) tolerates a simpler projection.
     */
    static String serializeDraftForPrompt(DraftSchema draft) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("name", draft.name());
        ArrayNode cubes = root.putArray("cubes");
        for (DraftCube cube : draft.cubes()) {
            ObjectNode c = cubes.addObject();
            c.put("name", cube.name());
            c.put("sourceFactTable", cube.sourceFactTable());
            ArrayNode dims = c.putArray("dimensions");
            for (DraftDimension d : cube.dimensions()) {
                dims.add(serializeDimension(d));
            }
            ArrayNode measures = c.putArray("measures");
            for (DraftMeasure m : cube.measures()) {
                ObjectNode mNode = measures.addObject();
                mNode.put("name", m.name());
                mNode.put("column", m.column());
                mNode.put(
                        "aggregator",
                        m.aggregator() == null ? null : m.aggregator().name());
            }
        }
        ArrayNode shared = root.putArray("sharedDimensions");
        for (DraftDimension d : draft.sharedDimensions()) {
            shared.add(serializeDimension(d));
        }
        return MAPPER.writeValueAsString(root);
    }

    private static ObjectNode serializeDimension(DraftDimension d) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("name", d.name());
        n.put("type", d.type() == null ? null : d.type().name());
        n.put("sourceTable", d.sourceTable());
        n.put("foreignKey", d.foreignKey());
        ArrayNode hs = n.putArray("hierarchies");
        for (DraftHierarchy h : d.hierarchies()) {
            ObjectNode hn = hs.addObject();
            hn.put("name", h.name());
            hn.put("primaryKey", h.primaryKey());
            ArrayNode ls = hn.putArray("levels");
            for (DraftLevel lvl : h.levels()) {
                ObjectNode ln = ls.addObject();
                ln.put("name", lvl.name());
                ln.put("column", lvl.column());
                ln.put("type", lvl.type() == null ? null : lvl.type().name());
            }
        }
        return n;
    }

    // ---------- response parsing ----------

    /**
     * Parse an Anthropic Messages API response body into an {@link EnrichResponse}. Visible for
     * testing — this is the core deserialisation contract.
     *
     * <p>Looks for a {@code tool_use} content block named {@link #TOOL_NAME} and deserialises its
     * {@code input} field as a {@link SuggestionSet}. If no such block exists, returns a degraded
     * empty set (per the provider contract).
     */
    static EnrichResponse parseToolResponse(String body) throws IOException {
        JsonNode root = MAPPER.readTree(body);
        JsonNode content = root.path("content");

        Map<String, Object> metadata = new LinkedHashMap<>();
        String model = root.path("model").asText(null);
        if (model != null) {
            metadata.put("model", model);
        }
        JsonNode usage = root.path("usage");
        if (usage.isObject()) {
            if (usage.has("input_tokens")) {
                metadata.put("usage_input_tokens", usage.get("input_tokens").asInt());
            }
            if (usage.has("output_tokens")) {
                metadata.put("usage_output_tokens", usage.get("output_tokens").asInt());
            }
        }

        if (content.isArray()) {
            for (JsonNode block : content) {
                if ("tool_use".equals(block.path("type").asText())
                        && TOOL_NAME.equals(block.path("name").asText())) {
                    JsonNode input = block.path("input");
                    SuggestionSet set = MAPPER.treeToValue(input, SuggestionSet.class);
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
        metadata.put("reason", "no_tool_use_block");
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
