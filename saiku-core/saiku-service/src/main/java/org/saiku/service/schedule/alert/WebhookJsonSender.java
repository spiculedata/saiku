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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * POSTs an arbitrary JSON body to an admin-authored webhook URL (saiku#1099 — Slack / Microsoft Teams
 * incoming-webhook channel digests). Extracted alongside {@link WebhookAlertChannel} (saiku#1098) so a
 * second webhook-shaped feature reuses exactly the same SSRF hardening rather than re-deriving it:
 *
 * <ul>
 *   <li><b>SSRF-safe.</b> The URL is re-validated with {@link WebhookUrlValidator} immediately before
 *       the request (https-only, no internal / loopback / link-local host) — even when it was already
 *       validated at job-create time, re-checking here means no code path can POST to an unvalidated
 *       target.</li>
 *   <li><b>DNS-rebinding hardening (mirrors saiku#1846).</b> The validator resolves the host and checks
 *       every address, but the JDK {@link HttpClient} then resolves the hostname <i>again,
 *       independently</i>, at connect time. We minimise the TOCTOU window: re-resolve and re-validate
 *       immediately before the send, requiring the fresh address set to be a subset of the originally
 *       approved set. Any address that appeared since the first check aborts the send.</li>
 * </ul>
 *
 * <p>Payload-agnostic: callers (Slack's Block Kit shape, Teams' MessageCard shape, …) hand over a plain
 * {@code Map<String, Object>} and this class only handles the validated transport.
 */
public final class WebhookJsonSender {

    private static final Logger log = LoggerFactory.getLogger(WebhookJsonSender.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;
    private final Duration requestTimeout;
    private final WebhookUrlValidator.HostResolver resolver;

    public WebhookJsonSender() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), Duration.ofSeconds(15));
    }

    /** Visible for tests — inject a stub {@link HttpClient} to capture the posted request. */
    public WebhookJsonSender(HttpClient http, Duration requestTimeout) {
        this(http, requestTimeout, InetAddress::getAllByName);
    }

    /**
     * Visible for tests — inject a stub {@link HttpClient} and a {@link WebhookUrlValidator.HostResolver}
     * so a rebinding flip (different addresses on successive resolutions) can be simulated hermetically.
     */
    WebhookJsonSender(HttpClient http, Duration requestTimeout, WebhookUrlValidator.HostResolver resolver) {
        if (http == null) {
            throw new IllegalArgumentException("HttpClient is required");
        }
        this.http = http;
        this.requestTimeout = requestTimeout == null ? Duration.ofSeconds(15) : requestTimeout;
        this.resolver = resolver == null ? InetAddress::getAllByName : resolver;
    }

    /**
     * Validate {@code webhookUrl}, POST {@code jsonBody} as {@code application/json}, and throw unless the
     * response is 2xx.
     *
     * @param webhookUrl the admin-authored target URL
     * @param jsonBody the payload to serialize as the request body
     * @param userAgent the {@code User-Agent} header value (identifies which feature sent the request)
     */
    public void send(String webhookUrl, Map<String, Object> jsonBody, String userAgent) throws Exception {
        // Defence-in-depth: re-validate the target right before we open a connection, capturing the exact
        // address set the validator approved.
        WebhookUrlValidator.ValidatedTarget validated = WebhookUrlValidator.validateResolved(webhookUrl, resolver);
        URI target = validated.uri();

        // DNS-rebinding hardening (mirrors saiku#1846): re-resolve immediately before the send and require
        // the fresh set to be a subset of the approved set.
        assertNoRebind(target, validated.addresses());

        String body = MAPPER.writeValueAsString(jsonBody);
        HttpRequest request = HttpRequest.newBuilder(target)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("User-Agent", userAgent == null ? "Saiku-WebhookDigest" : userAgent)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
        int status = resp.statusCode();
        if (status < 200 || status >= 300) {
            // Never echo the response body — it may reflect attacker/endpoint content; keep the failure short.
            throw new WebhookDeliveryException("webhook returned HTTP " + status);
        }
        log.info("Webhook delivered (HTTP {})", status);
    }

    /**
     * Re-resolve {@code target}'s host and fail the send if the result diverges from {@code approved} —
     * the DNS-rebinding signature. Every address the host currently resolves to must (a) be one the
     * validator already approved and (b) pass the SSRF range-check again.
     */
    private void assertNoRebind(URI target, InetAddress[] approved) throws Exception {
        String host = target.getHost();
        if (host == null) {
            throw new WebhookDeliveryException("webhook url lost its host before send");
        }
        InetAddress[] fresh;
        try {
            fresh = resolver.resolve(host);
        } catch (Exception e) {
            throw new WebhookDeliveryException("webhook host no longer resolves before send");
        }
        if (fresh == null || fresh.length == 0) {
            throw new WebhookDeliveryException("webhook host no longer resolves before send");
        }
        Set<String> approvedIps = Arrays.stream(approved)
                .map(InetAddress::getHostAddress)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (InetAddress addr : fresh) {
            if (!approvedIps.contains(addr.getHostAddress()) || WebhookUrlValidator.isBlockedAddress(addr)) {
                throw new WebhookDeliveryException(
                        "webhook host DNS changed before send (possible rebinding); refusing");
            }
        }
    }
}
