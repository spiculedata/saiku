/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.mcp.outbound;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLSession;

/**
 * Test-only fake {@link HttpClient} that replays a fixed, ordered queue of canned responses (or
 * throws) — no network. Shared by every {@code org.saiku.service.mcp.outbound} test that exercises
 * {@link McpOutboundClient} (which is {@code final}, so it can't be subclassed/mocked directly; this
 * fakes the transport it's built on instead), same pattern as {@code AnthropicNlAskProviderTest}'s
 * {@code StubHttp}.
 */
final class ScriptedHttpClient extends HttpClient {

    private final Deque<Resp> queue;
    final List<HttpRequest> requests = new ArrayList<>();

    private ScriptedHttpClient(Deque<Resp> queue) {
        this.queue = queue;
    }

    static ScriptedHttpClient of(Resp... responses) {
        Deque<Resp> q = new ArrayDeque<>();
        for (Resp r : responses) q.add(r);
        return new ScriptedHttpClient(q);
    }

    /** The very next {@code send()} throws {@code t}. */
    static ScriptedHttpClient throwing(Throwable t) {
        return of(Resp.throwing(t));
    }

    /** One canned response: HTTP status + body + optional extra headers, or a throw. */
    static final class Resp {
        final int status;
        final String body;
        final Map<String, String> headers = new HashMap<>();
        final Throwable throwOnSend;

        private Resp(int status, String body, Throwable throwOnSend) {
            this.status = status;
            this.body = body;
            this.throwOnSend = throwOnSend;
        }

        static Resp ok(String body) {
            return new Resp(200, body, null);
        }

        static Resp status(int status, String body) {
            return new Resp(status, body, null);
        }

        static Resp throwing(Throwable t) {
            return new Resp(0, null, t);
        }

        Resp withHeader(String name, String value) {
            headers.put(name, value);
            return this;
        }
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
        requests.add(request);
        Resp r = queue.poll();
        if (r == null) {
            throw new IllegalStateException(
                    "ScriptedHttpClient queue exhausted at request #" + requests.size() + " (" + request.uri() + ")");
        }
        if (r.throwOnSend != null) {
            if (r.throwOnSend instanceof IOException io) throw io;
            if (r.throwOnSend instanceof RuntimeException re) throw re;
            throw new IOException(r.throwOnSend);
        }
        @SuppressWarnings("unchecked")
        HttpResponse<T> resp = (HttpResponse<T>) new StubResponse(request, r.status, r.body, r.headers);
        return resp;
    }

    @Override
    public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(
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

    private static final class StubResponse implements HttpResponse<String> {
        private final HttpRequest req;
        private final int status;
        private final String body;
        private final Map<String, String> headers;

        StubResponse(HttpRequest req, int status, String body, Map<String, String> headers) {
            this.req = req;
            this.status = status;
            this.body = body;
            this.headers = headers;
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
            Map<String, List<String>> asLists = new HashMap<>();
            headers.forEach((k, v) -> asLists.put(k, List.of(v)));
            return HttpHeaders.of(asLists, (a, b) -> true);
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
