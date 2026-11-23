/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.describe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.CookieHandler;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.net.ssl.SSLSession;
import org.junit.Test;

/** Unit tests for {@link TileDescribeService}. No real network calls. */
public class TileDescribeServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void isConfigured_falseWithNoKeyAndNoEnv() {
        TileDescribeService svc = new TileDescribeService("", "", "", 0);
        // Only true if ANTHROPIC_API_KEY happens to be set in this environment.
        assertEquals(System.getenv("ANTHROPIC_API_KEY") != null, svc.isConfigured());
    }

    @Test
    public void isConfigured_trueWhenKeyPresent() {
        TileDescribeService svc = new TileDescribeService("test-key", "claude-x", "https://example.invalid", 30);
        assertTrue(svc.isConfigured());
    }

    @Test
    public void describe_notConfigured_returnsDegradedWithoutNetworkCall() {
        TileDescribeService svc = new TileDescribeService("", "", "", 0, StubHttp.fixed(200, "{}"));
        DescribeQueryResult result = svc.describe("{\"measures\":[\"Store Sales\"]}");
        assertTrue(result.degraded());
        assertTrue(result.reason().contains("not configured"));
    }

    @Test
    public void buildRequestBody_forcesTheDescribeTool() throws Exception {
        TileDescribeService svc =
                new TileDescribeService("k", "claude-test", "https://example.invalid", 30, StubHttp.fixed(200, "{}"));
        String body = svc.buildRequestBody("{\"measures\":[\"Store Sales\"],\"rows\":[]}");
        JsonNode root = MAPPER.readTree(body);

        assertEquals("claude-test", root.path("model").asText());
        assertEquals("tool", root.path("tool_choice").path("type").asText());
        assertEquals(
                "emit_tile_description", root.path("tool_choice").path("name").asText());

        JsonNode tools = root.path("tools");
        assertEquals(1, tools.size());
        assertEquals("emit_tile_description", tools.get(0).path("name").asText());
        JsonNode required = tools.get(0).path("input_schema").path("required");
        assertEquals(2, required.size());

        assertTrue(root.path("system").asText().contains("STRUCTURE ONLY"));
        assertTrue(root.path("messages").get(0).path("content").asText().contains("Store Sales"));
    }

    @Test
    public void parseToolResponse_extractsTitleAndDescription() throws Exception {
        String body = "{\"usage\":{\"input_tokens\":42,\"output_tokens\":11},"
                + "\"content\":[{\"type\":\"tool_use\",\"name\":\"emit_tile_description\","
                + "\"input\":{\"title\":\"Sales by region, last 4 quarters\","
                + "\"description\":\"Compares quarterly sales across regions.\"}}]}";

        DescribeQueryResult result = TileDescribeService.parseToolResponse(body, "claude-x");

        assertFalse(result.degraded());
        assertEquals("Sales by region, last 4 quarters", result.title());
        assertEquals("Compares quarterly sales across regions.", result.description());
        assertEquals("claude-x", result.model());
        assertEquals(42, result.promptTokens());
        assertEquals(11, result.responseTokens());
    }

    @Test
    public void parseToolResponse_degradesWhenToolInputEmpty() throws Exception {
        String body = "{\"content\":[{\"type\":\"tool_use\",\"name\":\"emit_tile_description\"}]}";
        DescribeQueryResult result = TileDescribeService.parseToolResponse(body, "claude-x");
        assertTrue(result.degraded());
        assertEquals("empty tool_use input", result.reason());
    }

    @Test
    public void parseToolResponse_degradesWhenNoToolUseBlock() throws Exception {
        String body = "{\"content\":[{\"type\":\"text\",\"text\":\"nope\"}]}";
        DescribeQueryResult result = TileDescribeService.parseToolResponse(body, "claude-x");
        assertTrue(result.degraded());
        assertEquals("no tool_use block", result.reason());
    }

    @Test
    public void describe_happyPathReturnsOkResult() {
        String upstreamBody = "{\"usage\":{\"input_tokens\":5,\"output_tokens\":3},"
                + "\"content\":[{\"type\":\"tool_use\",\"name\":\"emit_tile_description\","
                + "\"input\":{\"title\":\"T\",\"description\":\"D\"}}]}";
        TileDescribeService svc = new TileDescribeService(
                "k", "claude-test", "https://example.invalid", 30, StubHttp.fixed(200, upstreamBody));
        DescribeQueryResult result = svc.describe("{}");
        assertFalse(result.degraded());
        assertEquals("T", result.title());
        assertEquals("D", result.description());
    }

    @Test
    public void describe_nonTwoxxUpstreamStatusDegrades() {
        TileDescribeService svc =
                new TileDescribeService("k", "claude-test", "https://example.invalid", 30, StubHttp.fixed(500, "oops"));
        DescribeQueryResult result = svc.describe("{}");
        assertTrue(result.degraded());
        assertTrue(result.reason().contains("HTTP 500"));
    }

    @Test
    public void describe_transportErrorDegradesRatherThanThrowing() {
        TileDescribeService svc =
                new TileDescribeService("k", "claude-test", "https://example.invalid", 30, StubHttp.throwingIo());
        DescribeQueryResult result = svc.describe("{}");
        assertTrue(result.degraded());
        assertTrue(result.reason().contains("Transport error"));
    }

    /** Minimal {@link HttpClient} stub — only {@code send} is exercised. */
    private static final class StubHttp extends HttpClient {
        private final int status;
        private final String body;
        private final boolean throwIo;

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
            if (throwIo) {
                throw new IOException("boom");
            }
            @SuppressWarnings("unchecked")
            HttpResponse<T> resp = (HttpResponse<T>) new StubResponse(request, status, body);
            return resp;
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
        public Optional<CookieHandler> cookieHandler() {
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
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
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
        private final HttpRequest request;
        private final int status;
        private final String body;

        StubResponse(HttpRequest request, int status, String body) {
            this.request = request;
            this.status = status;
            this.body = body;
        }

        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public HttpRequest request() {
            return request;
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
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
