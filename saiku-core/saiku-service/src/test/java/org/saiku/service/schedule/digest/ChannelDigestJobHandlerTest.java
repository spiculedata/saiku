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
package org.saiku.service.schedule.digest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.Test;
import org.saiku.service.mail.send.MailLinkBuilder;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiFilterSelection;
import org.saiku.service.schedule.ScheduledJobFile;
import org.saiku.service.schedule.alert.MeasureValueReader;
import org.saiku.service.schedule.alert.WebhookDeliveryException;
import org.saiku.service.schedule.alert.WebhookJsonSender;

/**
 * Delivery + off-request tests for {@link ChannelDigestJobHandler} (saiku#1099). Uses a stub
 * {@link HttpClient} (wrapped by a real {@link WebhookJsonSender}) to capture the posted request without
 * a live server.
 */
public class ChannelDigestJobHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "https://analytics.example.com";

    /** A capturing HttpClient: records the request and returns a canned status. */
    private static final class StubHttpClient extends HttpClient {
        HttpRequest captured;
        String capturedBody;
        int status = 200;

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException {
            this.captured = request;
            request.bodyPublisher().ifPresent(bp -> this.capturedBody = drain(bp));
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
            return URI.create("https://93.184.216.34/digest");
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }

    private static final MeasureValueReader READER = (AiCubeRef cube, String measure, List<AiFilterSelection> f) -> {
        if ("Unit Sales".equals(measure)) {
            return 1234.0;
        }
        return 56789.5;
    };

    private static ScheduledJobFile job(String channelType) {
        Map<String, Object> channel = new LinkedHashMap<>();
        channel.put("type", channelType);
        channel.put("webhookUrl", "https://93.184.216.34/digest");
        Map<String, Object> dash = new LinkedHashMap<>();
        dash.put("path", "shared/exec.saikudash");
        dash.put("title", "Executive Overview");
        Map<String, Object> m1 = new LinkedHashMap<>();
        m1.put("cube", "conn/cat/schema/Sales");
        m1.put("measure", "Unit Sales");
        m1.put("label", "Total Units");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("channel", channel);
        payload.put("dashboard", dash);
        payload.put("measures", List.of(m1));
        ScheduledJobFile j = new ScheduledJobFile();
        j.setId("job-1");
        j.setType("WEBHOOK_DIGEST");
        j.setPayload(payload);
        return j;
    }

    @Test
    public void slackChannel_postsBlockKitPayloadWithMeasureAndLink() throws Exception {
        StubHttpClient http = new StubHttpClient();
        WebhookJsonSender sender = new WebhookJsonSender(http, Duration.ofSeconds(5));
        ChannelDigestJobHandler handler = new ChannelDigestJobHandler(READER, sender, new MailLinkBuilder(BASE));

        handler.handle(job("SLACK"));

        assertNotNull(http.captured);
        assertEquals("https://93.184.216.34/digest", http.captured.uri().toString());
        JsonNode body = MAPPER.readTree(http.capturedBody);
        assertTrue(body.has("blocks"));
        assertEquals(
                "Executive Overview",
                body.get("blocks").get(0).get("text").get("text").asText());
        String summary = body.get("blocks").get(1).get("text").get("text").asText();
        assertTrue(summary.contains("Total Units"));
        assertTrue(summary.contains("1,234"));
        JsonNode button = body.get("blocks").get(2).get("elements").get(0);
        assertEquals(
                BASE + "/ui/dashboards/shared/exec.saikudash", button.get("url").asText());
    }

    @Test
    public void teamsChannel_postsMessageCardPayload() throws Exception {
        StubHttpClient http = new StubHttpClient();
        WebhookJsonSender sender = new WebhookJsonSender(http, Duration.ofSeconds(5));
        ChannelDigestJobHandler handler = new ChannelDigestJobHandler(READER, sender, new MailLinkBuilder(BASE));

        handler.handle(job("TEAMS"));

        assertNotNull(http.captured);
        JsonNode body = MAPPER.readTree(http.capturedBody);
        assertEquals("MessageCard", body.get("@type").asText());
        assertTrue(body.get("text").asText().contains("Total Units"));
        assertEquals(
                BASE + "/ui/dashboards/shared/exec.saikudash",
                body.get("potentialAction")
                        .get(0)
                        .get("targets")
                        .get(0)
                        .get("uri")
                        .asText());
    }

    @Test
    public void nonMeasuresOnlyPayload_omitsLinkWhenBaseUrlUnconfigured() throws Exception {
        StubHttpClient http = new StubHttpClient();
        WebhookJsonSender sender = new WebhookJsonSender(http, Duration.ofSeconds(5));
        ChannelDigestJobHandler handler =
                new ChannelDigestJobHandler(READER, sender, new MailLinkBuilder((String) null));

        handler.handle(job("SLACK"));

        JsonNode body = MAPPER.readTree(http.capturedBody);
        JsonNode last = body.get("blocks").get(body.get("blocks").size() - 1);
        assertEquals("context", last.get("type").asText());
    }

    @Test(expected = WebhookDeliveryException.class)
    public void non2xxResponsePropagates() throws Exception {
        StubHttpClient http = new StubHttpClient();
        http.status = 500;
        WebhookJsonSender sender = new WebhookJsonSender(http, Duration.ofSeconds(5));
        ChannelDigestJobHandler handler = new ChannelDigestJobHandler(READER, sender, new MailLinkBuilder(BASE));

        handler.handle(job("SLACK"));
    }

    @Test
    public void handleRunsOffRequestThreadWithoutScopeError() throws Exception {
        StubHttpClient http = new StubHttpClient();
        WebhookJsonSender sender = new WebhookJsonSender(http, Duration.ofSeconds(5));
        final AtomicBoolean read = new AtomicBoolean(false);
        MeasureValueReader offRequestReader = (cube, measure, f) -> {
            read.set(true);
            return 1234.0;
        };
        ChannelDigestJobHandler handler =
                new ChannelDigestJobHandler(offRequestReader, sender, new MailLinkBuilder(BASE));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Callable<Void> task = () -> {
                handler.handle(job("SLACK"));
                return null;
            };
            pool.submit(task).get();
            assertTrue("the measure read must run off-request", read.get());
            assertNotNull(http.captured);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void malformedSpecThrows() throws Exception {
        StubHttpClient http = new StubHttpClient();
        WebhookJsonSender sender = new WebhookJsonSender(http, Duration.ofSeconds(5));
        ChannelDigestJobHandler handler = new ChannelDigestJobHandler(READER, sender, new MailLinkBuilder(BASE));

        ScheduledJobFile job = job("SLACK");
        job.getPayload().remove("measures");
        handler.handle(job);
    }
}
