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

/** Unit tests for {@link OpenAINlAskProvider}. No network. */
public class OpenAINlAskProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AiCubeRef CUBE = new AiCubeRef("conn", "FoodMart 2009", "FoodMart", "Sales");
    private static final String SCHEMA = "{\"cubeName\":\"Sales\"}";
    private static final String REQUEST_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"cube\":{\"type\":\"object\"}}}";

    @Test
    public void requestBodyBindsFunctionParametersAndForcesToolChoice() throws Exception {
        OpenAINlAskProvider provider = new OpenAINlAskProvider(
                new OpenAINlAskProvider.Config("k", "gpt-x", OpenAINlAskProvider.DEFAULT_ENDPOINT, 0.0, 1024, null));
        NlAskRequest req = new NlAskRequest(CUBE, "show sales by country", SCHEMA, REQUEST_SCHEMA, List.of());

        JsonNode root = MAPPER.readTree(provider.buildRequestBody(req));

        assertEquals("gpt-x", root.get("model").asText());
        assertEquals(1024, root.get("max_tokens").asInt());
        // tool_choice "required" — model must call a function, picks among the four scoped functions.
        assertEquals("required", root.get("tool_choice").asText());

        // AUTO routing exposes all four functions: emit_query, emit_insight, emit_view_change,
        // and the refuse_off_topic scope guardrail (always appended last).
        JsonNode tools = root.get("tools");
        assertEquals(4, tools.size());
        assertEquals("function", tools.get(0).get("type").asText());
        assertEquals("emit_query", tools.get(0).get("function").get("name").asText());
        assertEquals(
                "object",
                tools.get(0).get("function").get("parameters").get("type").asText());
        JsonNode refusal = tools.get(tools.size() - 1);
        assertEquals("function", refusal.get("type").asText());
        assertEquals("refuse_off_topic", refusal.get("function").get("name").asText());

        // System message embeds the cube schema
        JsonNode messages = root.get("messages");
        assertEquals("system", messages.get(0).get("role").asText());
        assertTrue(messages.get(0).get("content").asText().contains("FoodMart 2009"));

        // Final message is the user question
        assertEquals("user", messages.get(messages.size() - 1).get("role").asText());
        assertEquals(
                "show sales by country",
                messages.get(messages.size() - 1).get("content").asText());
    }

    @Test
    public void requestBodyKeepsHistoryBetweenSystemAndQuestion() throws Exception {
        OpenAINlAskProvider provider = new OpenAINlAskProvider(
                new OpenAINlAskProvider.Config("k", "gpt-x", OpenAINlAskProvider.DEFAULT_ENDPOINT, 0.0, 1024, null));
        NlAskRequest req = new NlAskRequest(
                CUBE,
                "now by region",
                SCHEMA,
                REQUEST_SCHEMA,
                List.of(NlAskMessage.user("sales by country"), NlAskMessage.assistant("{...}")));

        JsonNode messages = MAPPER.readTree(provider.buildRequestBody(req)).get("messages");

        // system + 2 history + 1 final user = 4
        assertEquals(4, messages.size());
        assertEquals("system", messages.get(0).get("role").asText());
        assertEquals("user", messages.get(1).get("role").asText());
        assertEquals("sales by country", messages.get(1).get("content").asText());
        assertEquals("assistant", messages.get(2).get("role").asText());
        assertEquals("user", messages.get(3).get("role").asText());
    }

    @Test
    public void parseToolResponseExtractsArgumentsAsJson() throws Exception {
        // OpenAI returns `arguments` as a JSON-encoded STRING (not nested JSON), so the inner
        // braces need to be escaped in the wire body.
        String body = "{"
                + "\"id\":\"x\",\"model\":\"gpt-x\","
                + "\"usage\":{\"prompt_tokens\":42,\"completion_tokens\":17},"
                + "\"choices\":[{"
                + "  \"message\":{"
                + "    \"role\":\"assistant\","
                + "    \"tool_calls\":[{"
                + "      \"id\":\"c1\",\"type\":\"function\","
                + "      \"function\":{\"name\":\"emit_query\","
                + "        \"arguments\":\"{\\\"cube\\\":{\\\"cubeName\\\":\\\"Sales\\\"},\\\"measures\\\":[]}\"}"
                + "    }]"
                + "  }"
                + "}]}";

        NlAskResponse resp = OpenAINlAskProvider.parseToolResponse(body, "gpt-x");

        assertFalse(resp.degraded());
        assertEquals("gpt-x", resp.model());
        assertEquals(42, resp.inputTokens());
        assertEquals(17, resp.outputTokens());

        JsonNode parsed = MAPPER.readTree(resp.aiQueryRequestJson());
        assertEquals("Sales", parsed.get("cube").get("cubeName").asText());
    }

    @Test
    public void parseToolResponseDegradesWhenNoChoices() throws Exception {
        NlAskResponse resp = OpenAINlAskProvider.parseToolResponse("{\"choices\":[]}", "gpt-x");
        assertTrue(resp.degraded());
        assertEquals("no choices", resp.reason());
    }

    @Test
    public void parseToolResponseDegradesWhenNoToolCalls() throws Exception {
        String body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"sorry\"}}]}";
        NlAskResponse resp = OpenAINlAskProvider.parseToolResponse(body, "gpt-x");
        assertTrue(resp.degraded());
        assertEquals("no tool_calls", resp.reason());
    }

    @Test
    public void parseToolResponseDegradesWhenArgumentsBlank() throws Exception {
        String body = "{\"choices\":[{\"message\":{\"tool_calls\":[{\"function\":{\"name\":\"emit_query\","
                + "\"arguments\":\"\"}}]}}]}";
        NlAskResponse resp = OpenAINlAskProvider.parseToolResponse(body, "gpt-x");
        assertTrue(resp.degraded());
        assertEquals("empty tool_call arguments", resp.reason());
    }

    @Test
    public void parseToolResponseReturnsOffTopicWhenModelCallsRefusalTool() throws Exception {
        String body = "{\"choices\":[{\"message\":{\"tool_calls\":[{\"function\":{\"name\":\"refuse_off_topic\","
                + "\"arguments\":\"{\\\"reason\\\":\\\"That's about weather, not the Sales cube.\\\"}\"}}]}}]}";
        NlAskResponse resp = OpenAINlAskProvider.parseToolResponse(body, "gpt-x");
        assertTrue(resp.degraded());
        assertEquals("OFF_TOPIC: That's about weather, not the Sales cube.", resp.reason());
    }

    @Test
    public void parseToolResponseDegradesWhenToolCallsExcludeEmitQuery() throws Exception {
        String body = "{\"choices\":[{\"message\":{\"tool_calls\":[{\"function\":{\"name\":\"some_other_fn\","
                + "\"arguments\":\"{}\"}}]}}]}";
        NlAskResponse resp = OpenAINlAskProvider.parseToolResponse(body, "gpt-x");
        assertTrue(resp.degraded());
        assertEquals("tool_calls did not include a recognised tool", resp.reason());
    }

    // ---------- characterization: security invariants (issue #1159) ----------

    /** Security invariant: the API key may appear ONLY in the Bearer auth header, never in the body. */
    @Test
    public void askSendsKeyInBearerHeaderAndNeverInBody() {
        StubHttp fake = StubHttp.fixed(200, "{\"choices\":[]}");
        OpenAINlAskProvider provider = new OpenAINlAskProvider(
                new OpenAINlAskProvider.Config(
                        "sk-secret-key",
                        "gpt-x",
                        OpenAINlAskProvider.DEFAULT_ENDPOINT,
                        0.0,
                        1024,
                        Duration.ofSeconds(5)),
                fake);

        provider.ask(new NlAskRequest(CUBE, "show sales", SCHEMA, REQUEST_SCHEMA, List.of()));

        HttpRequest sent = fake.lastRequest;
        assertNotNull(sent);
        assertEquals(
                "Bearer sk-secret-key",
                sent.headers().firstValue("authorization").orElse(null));
        assertEquals(
                "application/json", sent.headers().firstValue("content-type").orElse(null));
        assertFalse("API key must never appear in the request body", fake.lastBody.contains("sk-secret-key"));
    }

    /** Security invariant: refuse_off_topic function is always present and tool_choice forces a call. */
    @Test
    public void requestAlwaysCarriesRefusalFunctionAndForcesToolChoice() throws Exception {
        OpenAINlAskProvider provider = new OpenAINlAskProvider(
                new OpenAINlAskProvider.Config("k", "gpt-x", OpenAINlAskProvider.DEFAULT_ENDPOINT, 0.0, 1024, null));
        JsonNode root = MAPPER.readTree(
                provider.buildRequestBody(new NlAskRequest(CUBE, "q", SCHEMA, REQUEST_SCHEMA, List.of())));

        assertEquals("required", root.get("tool_choice").asText());
        JsonNode tools = root.get("tools");
        JsonNode refusal = tools.get(tools.size() - 1).get("function");
        assertEquals("refuse_off_topic", refusal.get("name").asText());
        assertEquals("reason", refusal.get("parameters").get("required").get(0).asText());
    }

    /** Locks the system-prompt guardrail wording — must keep "function" (not "tool") for OpenAI. */
    @Test
    public void systemPromptKeepsGuardrailWordingVerbatim() throws Exception {
        OpenAINlAskProvider provider = new OpenAINlAskProvider(
                new OpenAINlAskProvider.Config("k", "gpt-x", OpenAINlAskProvider.DEFAULT_ENDPOINT, 0.0, 1024, null));
        String system = MAPPER.readTree(
                        provider.buildRequestBody(new NlAskRequest(CUBE, "q", SCHEMA, REQUEST_SCHEMA, List.of())))
                .get("messages")
                .get(0)
                .get("content")
                .asText();
        assertTrue(system.contains("SCOPE GUARDRAIL"));
        assertTrue(system.contains("call refuse_off_topic with a one-sentence reason"));
        assertTrue(system.contains("Always call exactly ONE function"));
    }

    @Test
    public void askReturnsDegradedOnHttpError() {
        HttpClient fake = StubHttp.fixed(503, "upstream offline");
        OpenAINlAskProvider provider = new OpenAINlAskProvider(
                new OpenAINlAskProvider.Config(
                        "k", "gpt-x", OpenAINlAskProvider.DEFAULT_ENDPOINT, 0.0, 1024, Duration.ofSeconds(5)),
                fake);

        NlAskResponse resp = provider.ask(new NlAskRequest(CUBE, "q", SCHEMA, REQUEST_SCHEMA, List.of()));

        assertTrue(resp.degraded());
        assertTrue(resp.reason().startsWith("HTTP 503"));
        assertEquals("gpt-x", resp.model());
    }

    @Test
    public void askReturnsParsedRequestOn2xx() {
        String body = "{\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3},"
                + "\"choices\":[{\"message\":{\"tool_calls\":[{\"function\":{\"name\":\"emit_query\","
                + "\"arguments\":\"{\\\"cube\\\":{\\\"cubeName\\\":\\\"Sales\\\"}}\"}}]}}]}";
        HttpClient fake = StubHttp.fixed(200, body);
        OpenAINlAskProvider provider = new OpenAINlAskProvider(
                new OpenAINlAskProvider.Config(
                        "k", "gpt-x", OpenAINlAskProvider.DEFAULT_ENDPOINT, 0.0, 1024, Duration.ofSeconds(5)),
                fake);

        NlAskResponse resp = provider.ask(new NlAskRequest(CUBE, "q", SCHEMA, REQUEST_SCHEMA, List.of()));

        assertFalse(resp.degraded());
        assertNotNull(resp.aiQueryRequestJson());
        assertEquals(7, resp.inputTokens());
        assertEquals(3, resp.outputTokens());
    }

    @Test
    public void askReturnsDegradedOnTransportError() {
        HttpClient fake = StubHttp.throwingIo();
        OpenAINlAskProvider provider = new OpenAINlAskProvider(
                new OpenAINlAskProvider.Config(
                        "k", "gpt-x", OpenAINlAskProvider.DEFAULT_ENDPOINT, 0.0, 1024, Duration.ofSeconds(5)),
                fake);

        NlAskResponse resp = provider.ask(new NlAskRequest(CUBE, "q", SCHEMA, REQUEST_SCHEMA, List.of()));
        assertTrue(resp.degraded());
        assertTrue(resp.reason().startsWith("Transport error"));
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
