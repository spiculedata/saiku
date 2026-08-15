/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.saiku.service.schedule.alert;

import static org.junit.Assert.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.Test;

/**
 * {@link WebhookAlertChannel} posts the correct JSON to the correct URL (saiku#1098). Uses a stub
 * {@link HttpClient} to capture the request without a live server.
 */
public class WebhookAlertChannelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A capturing HttpClient: records the request and returns a canned status. */
    private static final class StubHttpClient extends HttpClient {
        HttpRequest captured;
        String capturedBody;
        int status = 200;
        IOException ioException;

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException {
            this.captured = request;
            request.bodyPublisher().ifPresent(bp -> this.capturedBody = drain(bp));
            if (ioException != null) {
                throw ioException;
            }
            return (HttpResponse<T>) new FakeResponse(status);
        }

        private static String drain(HttpRequest.BodyPublisher bp) {
            StringBuilder sb = new StringBuilder();
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            bp.subscribe(new java.util.concurrent.Flow.Subscriber<>() {
                @Override
                public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
                    s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(java.nio.ByteBuffer item) {
                    byte[] b = new byte[item.remaining()];
                    item.get(b);
                    sb.append(new String(b, java.nio.charset.StandardCharsets.UTF_8));
                }

                @Override
                public void onError(Throwable t) {
                    done.countDown();
                }

                @Override
                public void onComplete() {
                    done.countDown();
                }
            });
            try {
                done.await(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return sb.toString();
        }

        // --- unused abstract API ---
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
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            try {
                return SSLContext.getDefault();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
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

        @Override
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException();
        }
    }

    private record FakeResponse(int code) implements HttpResponse<String> {
        @Override
        public int statusCode() {
            return code;
        }

        @Override
        public HttpRequest request() {
            return null;
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public java.net.http.HttpHeaders headers() {
            return java.net.http.HttpHeaders.of(java.util.Map.of(), (a, b) -> true);
        }

        @Override
        public String body() {
            return "";
        }

        @Override
        public Optional<javax.net.ssl.SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return URI.create("https://93.184.216.34/alert");
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }

    private static AlertEvent event() {
        return new AlertEvent(
                "JOB123",
                "conn/cat/schema/Sales",
                "Unit Sales",
                AlertComparison.GT,
                150.0,
                120.0,
                100.0,
                1700000000000L);
    }

    @Test
    public void postsCorrectJsonToTheUrl() throws Exception {
        StubHttpClient http = new StubHttpClient();
        WebhookAlertChannel channel = new WebhookAlertChannel(http, Duration.ofSeconds(5));
        AlertChannelConfig config = AlertChannelConfig.fromPayload(
                java.util.Map.of("type", "WEBHOOK", "url", "https://93.184.216.34/alert"));

        channel.deliver(event(), config);

        assertNotNull(http.captured);
        assertEquals("https://93.184.216.34/alert", http.captured.uri().toString());
        assertEquals("POST", http.captured.method());
        assertEquals(
                "application/json",
                http.captured.headers().firstValue("Content-Type").orElse(null));

        JsonNode body = MAPPER.readTree(http.capturedBody);
        assertEquals("threshold_alert", body.get("type").asText());
        assertEquals("JOB123", body.get("jobId").asText());
        assertEquals("conn/cat/schema/Sales", body.get("cube").asText());
        assertEquals("Unit Sales", body.get("measure").asText());
        assertEquals("GT", body.get("comparison").asText());
        assertEquals(150.0, body.get("value").asDouble(), 0.0001);
        assertEquals(120.0, body.get("previousValue").asDouble(), 0.0001);
        assertEquals(100.0, body.get("threshold").asDouble(), 0.0001);
        assertEquals(1700000000000L, body.get("timestamp").asLong());
    }

    @Test
    public void non2xxThrows() throws Exception {
        StubHttpClient http = new StubHttpClient();
        http.status = 500;
        WebhookAlertChannel channel = new WebhookAlertChannel(http, Duration.ofSeconds(5));
        AlertChannelConfig config = AlertChannelConfig.fromPayload(
                java.util.Map.of("type", "WEBHOOK", "url", "https://93.184.216.34/alert"));
        try {
            channel.deliver(event(), config);
            fail("expected non-2xx to throw");
        } catch (AlertDeliveryException expected) {
            assertTrue(expected.getMessage().contains("500"));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void wrongChannelTypeRejected() throws Exception {
        StubHttpClient http = new StubHttpClient();
        WebhookAlertChannel channel = new WebhookAlertChannel(http, Duration.ofSeconds(5));
        channel.deliver(event(), AlertChannelConfig.fromPayload(java.util.Map.of("type", "EMAIL_SELF")));
    }

    // --- saiku#1846: DNS-rebinding hardening ---

    /**
     * A resolver that returns a different address on each successive call — the DNS-rebinding
     * signature. Used to prove the channel re-resolves before send and refuses when the host flips.
     */
    private static final class RebindingResolver implements WebhookUrlValidator.HostResolver {
        private final String[] ipsInOrder;
        private int call = 0;

        RebindingResolver(String... ipsInOrder) {
            this.ipsInOrder = ipsInOrder;
        }

        @Override
        public java.net.InetAddress[] resolve(String host) throws java.net.UnknownHostException {
            String ip = ipsInOrder[Math.min(call, ipsInOrder.length - 1)];
            call++;
            return new java.net.InetAddress[] {java.net.InetAddress.getByName(ip)};
        }
    }

    @Test
    public void refusesWhenHostRebindsToInternalBeforeSend() throws Exception {
        StubHttpClient http = new StubHttpClient();
        // First resolution (validation) sees a safe public IP; second (pre-send re-check) flips internal.
        RebindingResolver rebind = new RebindingResolver("93.184.216.34", "169.254.169.254");
        WebhookAlertChannel channel = new WebhookAlertChannel(http, Duration.ofSeconds(5), rebind);
        AlertChannelConfig config = AlertChannelConfig.forWebhookUrlUnvalidated("https://hooks.example.com/alert");
        try {
            channel.deliver(event(), config);
            fail("expected a rebinding flip to abort the send");
        } catch (AlertDeliveryException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("rebinding"));
        }
        // The socket must never have been used — the request was never dispatched.
        assertNull("no request should have been sent after detecting a rebind", http.captured);
    }

    @Test
    public void refusesWhenHostRebindsToANewPublicAddressBeforeSend() throws Exception {
        StubHttpClient http = new StubHttpClient();
        // Even a flip to a *different public* address (not in the approved set) is refused: we send only
        // to an address the validator actually approved.
        RebindingResolver rebind = new RebindingResolver("93.184.216.34", "203.0.113.9");
        WebhookAlertChannel channel = new WebhookAlertChannel(http, Duration.ofSeconds(5), rebind);
        AlertChannelConfig config = AlertChannelConfig.forWebhookUrlUnvalidated("https://hooks.example.com/alert");
        try {
            channel.deliver(event(), config);
            fail("expected a rebinding flip to abort the send");
        } catch (AlertDeliveryException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("rebinding"));
        }
        assertNull(http.captured);
    }

    @Test
    public void sendsWhenHostResolutionIsStable() throws Exception {
        StubHttpClient http = new StubHttpClient();
        // Stable resolution across both calls: validation + pre-send re-check agree — the send proceeds.
        RebindingResolver stable = new RebindingResolver("93.184.216.34", "93.184.216.34");
        WebhookAlertChannel channel = new WebhookAlertChannel(http, Duration.ofSeconds(5), stable);
        AlertChannelConfig config = AlertChannelConfig.forWebhookUrlUnvalidated("https://hooks.example.com/alert");

        channel.deliver(event(), config);

        assertNotNull("a stable host should send normally", http.captured);
        assertEquals("https://hooks.example.com/alert", http.captured.uri().toString());
    }
}
