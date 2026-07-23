/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.net.ssl.SSLSession;
import org.junit.Test;
import org.saiku.service.olap.ai.AiCubeRef;

/** Unit tests for {@link AnthropicNlAskProvider}. No network. */
public class AnthropicNlAskProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AiCubeRef CUBE = new AiCubeRef("conn", "FoodMart 2009", "FoodMart", "Sales");
    private static final String SCHEMA = "{\"cubeName\":\"Sales\",\"measures\":[{\"name\":\"Store Sales\"}]}";
    private static final String REQUEST_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"cube\":{\"type\":\"object\"}}}";

    @Test
    public void requestBodyBindsSchemaAsToolInputSchema() throws Exception {
        AnthropicNlAskProvider provider =
                new AnthropicNlAskProvider(new AnthropicNlAskProvider.Config("k", "claude-x", 0.0, 1024, null));
        NlAskRequest req = new NlAskRequest(CUBE, "show sales by country", SCHEMA, REQUEST_SCHEMA, List.of());

        JsonNode root = MAPPER.readTree(provider.buildRequestBody(req));

        assertEquals("claude-x", root.get("model").asText());
        assertEquals(1024, root.get("max_tokens").asInt());
        // tool_choice "any" lets the model pick among the five scoped tools.
        assertEquals("any", root.get("tool_choice").get("type").asText());

        // AUTO routing exposes all five tools: emit_query, emit_insight, emit_email_draft,
        // emit_view_change, and the refuse_off_topic scope guardrail (always appended last).
        JsonNode tools = root.get("tools");
        assertEquals(5, tools.size());
        assertEquals("emit_query", tools.get(0).get("name").asText());
        assertEquals("object", tools.get(0).get("input_schema").get("type").asText());
        JsonNode refusal = tools.get(tools.size() - 1);
        assertEquals("refuse_off_topic", refusal.get("name").asText());
        assertEquals(
                "string",
                refusal.get("input_schema")
                        .get("properties")
                        .get("reason")
                        .get("type")
                        .asText());

        // System prompt embeds the cube schema + ref
        String system = root.get("system").asText();
        assertTrue(system.contains("Store Sales"));
        assertTrue(system.contains("FoodMart 2009"));

        // Single user message — no history
        JsonNode messages = root.get("messages");
        assertEquals(1, messages.size());
        assertEquals("user", messages.get(0).get("role").asText());
        assertEquals("show sales by country", messages.get(0).get("content").asText());
    }

    @Test
    public void omitsTemperatureWhenSentinelNegative() throws Exception {
        // OMIT_TEMPERATURE (negative) must drop the `temperature` field entirely — the Claude-5
        // family rejects it as deprecated (400 invalid_request_error).
        AnthropicNlAskProvider provider = new AnthropicNlAskProvider(new AnthropicNlAskProvider.Config(
                "k", "claude-sonnet-5", AnthropicNlAskProvider.OMIT_TEMPERATURE, 1024, null));
        NlAskRequest req = new NlAskRequest(CUBE, "show sales", SCHEMA, REQUEST_SCHEMA, List.of());

        JsonNode root = MAPPER.readTree(provider.buildRequestBody(req));

        assertFalse("temperature must be omitted when the sentinel is set", root.has("temperature"));
    }

    @Test
    public void includesTemperatureWhenExplicitlySet() throws Exception {
        AnthropicNlAskProvider provider =
                new AnthropicNlAskProvider(new AnthropicNlAskProvider.Config("k", "claude-x", 0.2, 1024, null));
        NlAskRequest req = new NlAskRequest(CUBE, "show sales", SCHEMA, REQUEST_SCHEMA, List.of());

        JsonNode root = MAPPER.readTree(provider.buildRequestBody(req));

        assertTrue(root.has("temperature"));
        assertEquals(0.2, root.get("temperature").asDouble(), 1e-9);
    }

    @Test
    public void requestBodyIncludesConversationHistoryBeforeNewQuestion() throws Exception {
        AnthropicNlAskProvider provider =
                new AnthropicNlAskProvider(new AnthropicNlAskProvider.Config("k", "claude-x", 0.0, 1024, null));
        NlAskRequest req = new NlAskRequest(
                CUBE,
                "now break it down by region",
                SCHEMA,
                REQUEST_SCHEMA,
                List.of(NlAskMessage.user("sales by country"), NlAskMessage.assistant("{...}")));

        JsonNode messages = MAPPER.readTree(provider.buildRequestBody(req)).get("messages");

        assertEquals(3, messages.size());
        assertEquals("user", messages.get(0).get("role").asText());
        assertEquals("sales by country", messages.get(0).get("content").asText());
        assertEquals("assistant", messages.get(1).get("role").asText());
        assertEquals("user", messages.get(2).get("role").asText());
        assertEquals(
                "now break it down by region", messages.get(2).get("content").asText());
    }

    @Test
    public void parseToolResponseExtractsToolInputAsJson() throws Exception {
        String body = "{"
                + "\"id\":\"msg_x\",\"model\":\"claude-x\","
                + "\"usage\":{\"input_tokens\":42,\"output_tokens\":17},"
                + "\"content\":["
                + "{\"type\":\"tool_use\",\"name\":\"emit_query\",\"input\":"
                + "{\"cube\":{\"cubeName\":\"Sales\"},\"measures\":[{\"name\":\"Store Sales\"}]}}"
                + "]}";

        NlAskResponse resp = AnthropicNlAskProvider.parseToolResponse(body, "claude-x");

        assertFalse(resp.degraded());
        assertEquals("claude-x", resp.model());
        assertEquals(42, resp.inputTokens());
        assertEquals(17, resp.outputTokens());
        JsonNode parsed = MAPPER.readTree(resp.aiQueryRequestJson());
        assertEquals("Sales", parsed.get("cube").get("cubeName").asText());
        assertEquals("Store Sales", parsed.get("measures").get(0).get("name").asText());
    }

    @Test
    public void requestBodyWithEmptyTranscriptIsUnchanged() throws Exception {
        AnthropicNlAskProvider provider =
                new AnthropicNlAskProvider(new AnthropicNlAskProvider.Config("k", "claude-x", 0.0, 1024, null));
        NlAskRequest req = new NlAskRequest(CUBE, "show sales by country", SCHEMA, REQUEST_SCHEMA, List.of())
                .withToolTranscript(List.of());

        JsonNode messages = MAPPER.readTree(provider.buildRequestBody(req)).get("messages");

        assertEquals(1, messages.size());
        assertEquals("user", messages.get(0).get("role").asText());
        assertEquals("show sales by country", messages.get(0).get("content").asText());
        for (JsonNode m : messages) {
            if (m.has("content") && m.get("content").isArray()) {
                for (JsonNode block : m.get("content")) {
                    assertFalse(
                            "no tool_use/tool_result block should appear with an empty transcript",
                            "tool_use".equals(block.path("type").asText())
                                    || "tool_result".equals(block.path("type").asText()));
                }
            }
        }
    }

    @Test
    public void requestBodyRendersOneToolTurnAsToolUseAndToolResultPair() throws Exception {
        AnthropicNlAskProvider provider =
                new AnthropicNlAskProvider(new AnthropicNlAskProvider.Config("k", "claude-x", 0.0, 1024, null));
        NlAskRequest req = new NlAskRequest(CUBE, "now break it down by region", SCHEMA, REQUEST_SCHEMA, List.of())
                .withToolTranscript(List.of(
                        new ToolTurn("toolu_1", "emit_query", "{\"cube\":\"x\"}", "| Tier | Bal |\n| Large | 7000 |")));

        JsonNode messages = MAPPER.readTree(provider.buildRequestBody(req)).get("messages");

        // ...history(none)..., {user, question}, {assistant tool_use}, {user tool_result}
        assertEquals(3, messages.size());
        JsonNode asstMsg = messages.get(1);
        assertEquals("assistant", asstMsg.get("role").asText());
        JsonNode toolUse = asstMsg.get("content").get(0);
        assertEquals("tool_use", toolUse.get("type").asText());
        assertEquals("toolu_1", toolUse.get("id").asText());
        assertEquals("emit_query", toolUse.get("name").asText());
        assertEquals("x", toolUse.get("input").get("cube").asText());

        JsonNode toolResultMsg = messages.get(2);
        assertEquals("user", toolResultMsg.get("role").asText());
        JsonNode toolResult = toolResultMsg.get("content").get(0);
        assertEquals("tool_result", toolResult.get("type").asText());
        assertEquals("toolu_1", toolResult.get("tool_use_id").asText());
        assertEquals(
                "| Tier | Bal |\n| Large | 7000 |", toolResult.get("content").asText());
    }

    @Test
    public void requestBodyRendersNullResultDigestAsWithheldPlaceholder() throws Exception {
        AnthropicNlAskProvider provider =
                new AnthropicNlAskProvider(new AnthropicNlAskProvider.Config("k", "claude-x", 0.0, 1024, null));
        NlAskRequest req = new NlAskRequest(CUBE, "q", SCHEMA, REQUEST_SCHEMA, List.of())
                .withToolTranscript(List.of(new ToolTurn("toolu_2", "emit_query", "{}", null)));

        JsonNode messages = MAPPER.readTree(provider.buildRequestBody(req)).get("messages");

        JsonNode toolResultMsg = messages.get(messages.size() - 1);
        JsonNode toolResult = toolResultMsg.get("content").get(0);
        assertEquals(
                "(result withheld by data policy)", toolResult.get("content").asText());
    }

    @Test
    public void parseToolResponseCapturesToolUseId() throws Exception {
        String body = "{"
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1},"
                + "\"content\":["
                + "{\"type\":\"tool_use\",\"id\":\"toolu_abc\",\"name\":\"emit_query\",\"input\":{\"cube\":\"x\"}}"
                + "]}";

        NlAskResponse resp = AnthropicNlAskProvider.parseToolResponse(body, "claude-x");

        assertFalse(resp.degraded());
        assertEquals(NlAskResponse.Kind.QUERY, resp.kind());
        assertEquals("toolu_abc", resp.toolCallId());
    }

    @Test
    public void parseToolResponseDegradesWhenNoToolUseBlock() throws Exception {
        String body = "{\"content\":[{\"type\":\"text\",\"text\":\"sorry I refuse\"}]}";
        NlAskResponse resp = AnthropicNlAskProvider.parseToolResponse(body, "claude-x");
        assertTrue(resp.degraded());
        assertEquals("no tool_use block", resp.reason());
    }

    @Test
    public void parseToolResponseDegradesWhenInputMissing() throws Exception {
        String body = "{\"content\":[{\"type\":\"tool_use\",\"name\":\"emit_query\"}]}";
        NlAskResponse resp = AnthropicNlAskProvider.parseToolResponse(body, "claude-x");
        assertTrue(resp.degraded());
        assertEquals("empty tool_use input", resp.reason());
    }

    /** AUTO routing offers emit_email_draft with a schema requiring `summary`. */
    @Test
    public void requestBodyIncludesEmailDraftToolWithRequiredSummary() throws Exception {
        AnthropicNlAskProvider provider =
                new AnthropicNlAskProvider(new AnthropicNlAskProvider.Config("k", "claude-x", 0.0, 1024, null));
        JsonNode root = MAPPER.readTree(
                provider.buildRequestBody(new NlAskRequest(CUBE, "q", SCHEMA, REQUEST_SCHEMA, List.of())));

        JsonNode tools = root.get("tools");
        JsonNode emailTool = null;
        for (JsonNode tool : tools) {
            if ("emit_email_draft".equals(tool.get("name").asText())) {
                emailTool = tool;
            }
        }
        assertNotNull("emit_email_draft tool must be present in AUTO mode", emailTool);
        assertEquals("object", emailTool.get("input_schema").get("type").asText());
        assertEquals(
                "summary", emailTool.get("input_schema").get("required").get(0).asText());
    }

    @Test
    public void parseToolResponseExtractsEmailDraftInputAsJson() throws Exception {
        String body = "{\"usage\":{\"input_tokens\":11,\"output_tokens\":5},"
                + "\"content\":[{\"type\":\"tool_use\",\"name\":\"emit_email_draft\","
                + "\"input\":{\"summary\":\"Large tier leads at 7,000.\"}}]}";

        NlAskResponse resp = AnthropicNlAskProvider.parseToolResponse(body, "claude-x");

        assertFalse(resp.degraded());
        assertEquals(NlAskResponse.Kind.EMAIL_DRAFT, resp.kind());
        assertEquals("claude-x", resp.model());
        assertEquals(11, resp.inputTokens());
        assertEquals(5, resp.outputTokens());
        JsonNode parsed = MAPPER.readTree(resp.payloadJson());
        assertEquals("Large tier leads at 7,000.", parsed.get("summary").asText());
    }

    @Test
    public void parseToolResponseDegradesWhenEmailDraftInputMissing() throws Exception {
        String body = "{\"content\":[{\"type\":\"tool_use\",\"name\":\"emit_email_draft\"}]}";
        NlAskResponse resp = AnthropicNlAskProvider.parseToolResponse(body, "claude-x");
        assertTrue(resp.degraded());
        assertEquals("empty email draft tool input", resp.reason());
    }

    @Test
    public void parseToolResponseReturnsOffTopicWhenModelCallsRefusalTool() throws Exception {
        String body = "{\"content\":["
                + "{\"type\":\"tool_use\",\"name\":\"refuse_off_topic\","
                + "\"input\":{\"reason\":\"That's about weather, not the Sales cube.\"}}"
                + "]}";
        NlAskResponse resp = AnthropicNlAskProvider.parseToolResponse(body, "claude-x");
        assertTrue(resp.degraded());
        assertEquals("OFF_TOPIC: That's about weather, not the Sales cube.", resp.reason());
    }

    @Test
    public void askReturnsDegradedOnHttpError() {
        HttpClient fake = StubHttp.fixed(503, "upstream offline");
        AnthropicNlAskProvider provider = new AnthropicNlAskProvider(
                new AnthropicNlAskProvider.Config("k", "claude-x", 0.0, 1024, Duration.ofSeconds(5)), fake);

        NlAskResponse resp = provider.ask(new NlAskRequest(CUBE, "q", SCHEMA, REQUEST_SCHEMA, List.of()));

        assertTrue(resp.degraded());
        assertTrue(resp.reason().startsWith("HTTP 503"));
        assertEquals("claude-x", resp.model());
    }

    @Test
    public void askReturnsParsedRequestOn2xx() {
        String body = "{\"usage\":{\"input_tokens\":7,\"output_tokens\":3},"
                + "\"content\":[{\"type\":\"tool_use\",\"name\":\"emit_query\","
                + "\"input\":{\"cube\":{\"cubeName\":\"Sales\"},\"measures\":[]}}]}";
        HttpClient fake = StubHttp.fixed(200, body);
        AnthropicNlAskProvider provider = new AnthropicNlAskProvider(
                new AnthropicNlAskProvider.Config("k", "claude-x", 0.0, 1024, Duration.ofSeconds(5)), fake);

        NlAskResponse resp = provider.ask(new NlAskRequest(CUBE, "q", SCHEMA, REQUEST_SCHEMA, List.of()));

        assertFalse(resp.degraded());
        assertNotNull(resp.aiQueryRequestJson());
        assertEquals(7, resp.inputTokens());
        assertEquals(3, resp.outputTokens());
    }

    @Test
    public void askReturnsDegradedOnTransportError() {
        HttpClient fake = StubHttp.throwingIo();
        AnthropicNlAskProvider provider = new AnthropicNlAskProvider(
                new AnthropicNlAskProvider.Config("k", "claude-x", 0.0, 1024, Duration.ofSeconds(5)), fake);

        NlAskResponse resp = provider.ask(new NlAskRequest(CUBE, "q", SCHEMA, REQUEST_SCHEMA, List.of()));
        assertTrue(resp.degraded());
        assertTrue(resp.reason().startsWith("Transport error"));
    }

    // ---------- characterization: security invariants (issue #1159) ----------

    /**
     * Security invariant: the API key may appear ONLY in the {@code x-api-key} header (plus the
     * {@code anthropic-version} pin), never in the serialized request body.
     */
    @Test
    public void askSendsKeyInHeaderAndNeverInBody() {
        StubHttp fake = StubHttp.fixed(200, "{\"content\":[]}");
        AnthropicNlAskProvider provider = new AnthropicNlAskProvider(
                new AnthropicNlAskProvider.Config("sk-secret-key", "claude-x", 0.0, 1024, Duration.ofSeconds(5)), fake);

        provider.ask(new NlAskRequest(CUBE, "show sales", SCHEMA, REQUEST_SCHEMA, List.of()));

        HttpRequest sent = fake.lastRequest;
        assertNotNull(sent);
        assertEquals("sk-secret-key", sent.headers().firstValue("x-api-key").orElse(null));
        assertEquals(
                "2023-06-01", sent.headers().firstValue("anthropic-version").orElse(null));
        assertEquals(
                "application/json", sent.headers().firstValue("content-type").orElse(null));
        assertFalse("API key must never appear in the request body", fake.lastBody.contains("sk-secret-key"));
    }

    /** Security invariant: refuse_off_topic tool is always present and tool_choice forces a call. */
    @Test
    public void requestAlwaysCarriesRefusalToolAndForcesToolChoice() throws Exception {
        AnthropicNlAskProvider provider =
                new AnthropicNlAskProvider(new AnthropicNlAskProvider.Config("k", "claude-x", 0.0, 1024, null));
        JsonNode root = MAPPER.readTree(
                provider.buildRequestBody(new NlAskRequest(CUBE, "q", SCHEMA, REQUEST_SCHEMA, List.of())));

        assertEquals("any", root.get("tool_choice").get("type").asText());
        JsonNode tools = root.get("tools");
        JsonNode refusal = tools.get(tools.size() - 1);
        assertEquals("refuse_off_topic", refusal.get("name").asText());
        assertEquals(
                "reason", refusal.get("input_schema").get("required").get(0).asText());
    }

    /** Locks the system-prompt guardrail wording — must keep "tool" (not "function") for Anthropic. */
    @Test
    public void systemPromptKeepsGuardrailWordingVerbatim() throws Exception {
        AnthropicNlAskProvider provider =
                new AnthropicNlAskProvider(new AnthropicNlAskProvider.Config("k", "claude-x", 0.0, 1024, null));
        String system = MAPPER.readTree(
                        provider.buildRequestBody(new NlAskRequest(CUBE, "q", SCHEMA, REQUEST_SCHEMA, List.of())))
                .get("system")
                .asText();
        assertTrue(system.contains("SCOPE GUARDRAIL"));
        assertTrue(system.contains("call refuse_off_topic with a one-sentence reason"));
        assertTrue(system.contains("Always call exactly ONE tool"));
    }

    /** Refusal with no reason field falls back to the canonical OFF_TOPIC default reason. */
    @Test
    public void parseToolResponseRefusalDefaultsReasonWhenAbsent() throws Exception {
        String body = "{\"content\":[{\"type\":\"tool_use\",\"name\":\"refuse_off_topic\",\"input\":{}}]}";
        NlAskResponse resp = AnthropicNlAskProvider.parseToolResponse(body, "claude-x");
        assertTrue(resp.degraded());
        assertEquals("OFF_TOPIC: Question is not about the cube.", resp.reason());
    }

    /** Minimal {@link HttpClient} stub — only {@code send} is exercised. Captures the last request. */
    private static final class StubHttp extends HttpClient {
        private final int status;
        private final String body;
        private final boolean throwIo;
        HttpRequest lastRequest;
        String lastBody;

        private StubHttp(int status, String body, boolean throwIo) {
            this.status = status;
            this.body = body;
            this.throwIo = throwIo;
        }

        static StubHttp fixed(int status, String body) {
            return new StubHttp(status, body, false);
        }

        static StubHttp throwingIo() {
            return new StubHttp(0, "", true);
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
            this.lastRequest = request;
            this.lastBody = bodyAsString(request);
            if (throwIo) {
                throw new IOException("boom");
            }
            @SuppressWarnings("unchecked")
            HttpResponse<T> resp = (HttpResponse<T>) new StubResponse(request, status, body);
            return resp;
        }

        private static String bodyAsString(HttpRequest request) {
            StringBuilder sb = new StringBuilder();
            request.bodyPublisher().ifPresent(pub -> {
                java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer> sub =
                        new java.util.concurrent.Flow.Subscriber<>() {
                            @Override
                            public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
                                s.request(Long.MAX_VALUE);
                            }

                            @Override
                            public void onNext(java.nio.ByteBuffer item) {
                                sb.append(java.nio.charset.StandardCharsets.UTF_8.decode(item));
                            }

                            @Override
                            public void onError(Throwable t) {}

                            @Override
                            public void onComplete() {}
                        };
                pub.subscribe(sub);
            });
            return sb.toString();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<java.net.CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<java.net.ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public javax.net.ssl.SSLContext sslContext() {
            try {
                return javax.net.ssl.SSLContext.getDefault();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public javax.net.ssl.SSLParameters sslParameters() {
            return new javax.net.ssl.SSLParameters();
        }

        @Override
        public Optional<java.net.Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<java.util.concurrent.Executor> executor() {
            return Optional.empty();
        }
    }

    private static final class StubResponse implements HttpResponse<String> {
        private final HttpRequest req;
        private final int status;
        private final String body;

        StubResponse(HttpRequest req, int status, String body) {
            this.req = req;
            this.status = status;
            this.body = body;
        }

        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public HttpRequest request() {
            return req;
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(java.util.Map.of(), (a, b) -> true);
        }

        @Override
        public String body() {
            return body;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return req.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
