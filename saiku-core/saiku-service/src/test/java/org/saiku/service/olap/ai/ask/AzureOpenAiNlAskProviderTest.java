/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

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

/**
 * Unit tests for {@link AzureOpenAiNlAskProvider} — the Azure OpenAI Service adapter (saiku#1431).
 *
 * <p>The whole delta from {@link OpenAINlAskProvider} is the auth header shape ({@code api-key}
 * instead of {@code Authorization: Bearer}) and the requirement that the endpoint be explicitly
 * configured. Everything else (request body, response parsing) is exercised on the parent's tests.
 */
public class AzureOpenAiNlAskProviderTest {

    private static final AiCubeRef CUBE = new AiCubeRef("conn", "FoodMart 2009", "FoodMart", "Sales");
    private static final String SCHEMA = "{\"cubeName\":\"Sales\"}";
    private static final String REQUEST_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"cubeName\":{\"type\":\"string\"}}}";
    private static final String AZURE_ENDPOINT =
            "https://my-resource.openai.azure.com/openai/deployments/my-dep/chat/completions?api-version=2024-02-15-preview";

    /**
     * The security invariant: Azure OpenAI must use the {@code api-key} header AND must NOT set
     * {@code Authorization: Bearer …}. A misconfiguration where the parent's Bearer auth leaks
     * through would send the key to whatever host the endpoint URL points at, and Azure would
     * reject it — but the key would still land in the traced request.
     */
    @Test
    public void askSendsKeyInApiKeyHeaderNotBearer() {
        StubHttp fake = StubHttp.fixed(200, "{\"choices\":[]}");
        AzureOpenAiNlAskProvider provider = new AzureOpenAiNlAskProvider(
                new OpenAINlAskProvider.Config(
                        "azure-secret-key", "my-dep", AZURE_ENDPOINT, 0.0, 1024, Duration.ofSeconds(5)),
                fake);

        provider.ask(new NlAskRequest(CUBE, "show sales", SCHEMA, REQUEST_SCHEMA, List.of()));

        HttpRequest sent = fake.lastRequest;
        assertNotNull(sent);
        assertEquals("azure-secret-key", sent.headers().firstValue("api-key").orElse(null));
        assertNull(
                "Azure OpenAI must never send an Authorization: Bearer header",
                sent.headers().firstValue("authorization").orElse(null));
        assertFalse("API key must never appear in the request body", fake.lastBody.contains("azure-secret-key"));
    }

    @Test
    public void hitsConfiguredEndpoint() {
        StubHttp fake = StubHttp.fixed(200, "{\"choices\":[]}");
        AzureOpenAiNlAskProvider provider = new AzureOpenAiNlAskProvider(
                new OpenAINlAskProvider.Config("k", "my-dep", AZURE_ENDPOINT, 0.0, 1024, Duration.ofSeconds(5)), fake);

        provider.ask(new NlAskRequest(CUBE, "q", SCHEMA, REQUEST_SCHEMA, List.of()));

        assertNotNull(fake.lastRequest);
        assertEquals(AZURE_ENDPOINT, fake.lastRequest.uri().toString());
    }

    @Test
    public void modelFieldIsSentAsDeploymentName() {
        // Azure ignores the model field (deployment name in URL is the routing key) but the
        // parent provider still sends it. Verify the deployment name lands in the body so the
        // request is well-formed and Azure's request-log shows a matching model attribution.
        StubHttp fake = StubHttp.fixed(200, "{\"choices\":[]}");
        AzureOpenAiNlAskProvider provider = new AzureOpenAiNlAskProvider(
                new OpenAINlAskProvider.Config("k", "my-dep", AZURE_ENDPOINT, 0.0, 1024, Duration.ofSeconds(5)), fake);

        provider.ask(new NlAskRequest(CUBE, "q", SCHEMA, REQUEST_SCHEMA, List.of()));

        // The model field on the wire matches the configured value.
        assertNotNull(fake.lastBody);
        assertEquals(
                "the deployment name lands as model in the body", true, fake.lastBody.contains("\"model\":\"my-dep\""));
    }

    /* ---- StubHttp — same shape as the sibling OpenAI provider test. ---- */

    /** Minimal {@link HttpClient} stub — only {@code send} is exercised. Captures the last request. */
    private static final class StubHttp extends HttpClient {
        private final int status;
        private final String body;
        HttpRequest lastRequest;
        String lastBody;

        private StubHttp(int status, String body) {
            this.status = status;
            this.body = body;
        }

        static StubHttp fixed(int status, String body) {
            return new StubHttp(status, body);
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
            this.lastRequest = request;
            this.lastBody = bodyAsString(request);
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
