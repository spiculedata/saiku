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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Posts a fired {@link AlertEvent} as a small JSON body to an admin-authored webhook URL (saiku#1098).
 *
 * <p><b>SSRF-safe.</b> The URL is re-validated with {@link WebhookUrlValidator} immediately before the
 * request (https-only, no internal / loopback / link-local host) — even though it was already
 * validated at alert-create time, re-checking here means no code path can POST to an unvalidated
 * target. The URL is admin-fixed off the job payload; there is no request-time host from any untrusted
 * source.
 *
 * <p><b>DNS-rebinding hardening (saiku#1846).</b> The validator resolves the host and checks every
 * address, but the JDK {@link HttpClient} then resolves the hostname <i>again, independently</i>, at
 * connect time — a short-TTL / rebinding record can flip to an internal IP in that gap. The JDK client
 * exposes no builder hook to pin the connection to a pre-validated {@link InetAddress} without breaking
 * TLS (connecting by IP literal would make SNI + certificate hostname validation run against the IP and
 * fail), so a fully airtight pin is not achievable here. Instead we <b>minimise the TOCTOU window</b>:
 * we re-resolve and re-validate the host immediately before the {@code send()} and additionally assert
 * that the fresh address set is a subset of the set the validator originally approved. Any address that
 * appeared since the first check (the rebinding signature) fails the send.
 *
 * <p><b>Residual (accepted, LOW — saiku#1846).</b> A record that flips in the sub-millisecond gap
 * between our final re-validation and the client's own connect-time resolution is still theoretically
 * reachable. This is inherent to the JDK client's independent resolution and the absence of a
 * pin-by-address hook that preserves TLS; the webhook URL is admin-authored (not attacker-supplied), so
 * SEC rated this LOW. We do not break HTTPS or override DNS globally to close it.
 *
 * <p>Uses the JDK {@link HttpClient} (auto-instrumented by the OTel agent when attached). The client is
 * injectable so tests capture the exact request without a live server.
 */
public final class WebhookAlertChannel implements AlertChannel {

    private static final Logger log = LoggerFactory.getLogger(WebhookAlertChannel.class);
    private static final ObjectMapper MAPPER =
            new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private final HttpClient http;
    private final Duration requestTimeout;
    private final WebhookUrlValidator.HostResolver resolver;

    public WebhookAlertChannel() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), Duration.ofSeconds(15));
    }

    /** Visible for tests — inject a stub {@link HttpClient} to capture the posted request. */
    public WebhookAlertChannel(HttpClient http, Duration requestTimeout) {
        this(http, requestTimeout, InetAddress::getAllByName);
    }

    /**
     * Visible for tests — inject a stub {@link HttpClient} and a {@link WebhookUrlValidator.HostResolver}
     * so a rebinding flip (different addresses on successive resolutions) can be simulated hermetically.
     */
    WebhookAlertChannel(HttpClient http, Duration requestTimeout, WebhookUrlValidator.HostResolver resolver) {
        if (http == null) {
            throw new IllegalArgumentException("HttpClient is required");
        }
        this.http = http;
        this.requestTimeout = requestTimeout == null ? Duration.ofSeconds(15) : requestTimeout;
        this.resolver = resolver == null ? InetAddress::getAllByName : resolver;
    }

    @Override
    public void deliver(AlertEvent event, AlertChannelConfig config) throws Exception {
        if (config == null || config.getType() != AlertChannelConfig.Type.WEBHOOK) {
            throw new IllegalArgumentException("WebhookAlertChannel requires a WEBHOOK channel config");
        }
        // Defence-in-depth: re-validate the target right before we open a connection, capturing the
        // exact address set the validator approved.
        WebhookUrlValidator.ValidatedTarget validated =
                WebhookUrlValidator.validateResolved(config.getWebhookUrl(), resolver);
        URI target = validated.uri();

        // DNS-rebinding hardening (saiku#1846): re-resolve immediately before the send and require the
        // fresh set to be a subset of the approved set. A flip to any new (e.g. internal) address — the
        // rebinding signature — aborts the send. See the class javadoc for the accepted residual window.
        assertNoRebind(target, validated.addresses());

        String body = MAPPER.writeValueAsString(payload(event));
        HttpRequest request = HttpRequest.newBuilder(target)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("User-Agent", "Saiku-ThresholdAlert")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
        int status = resp.statusCode();
        if (status < 200 || status >= 300) {
            // Never echo the response body — it may reflect attacker/endpoint content; keep the failure short.
            throw new AlertDeliveryException("webhook returned HTTP " + status);
        }
        log.info("Threshold alert webhook delivered for job {} (HTTP {})", event.jobId(), status);
    }

    /**
     * Re-resolve {@code target}'s host and fail the send if the result diverges from {@code approved} —
     * the DNS-rebinding signature. Every address the host currently resolves to must (a) be one the
     * validator already approved and (b) pass the SSRF range-check again. A literal-IP target re-resolves
     * to itself, so this is a no-op flip-check for literals.
     */
    private void assertNoRebind(URI target, InetAddress[] approved) throws Exception {
        String host = target.getHost();
        if (host == null) {
            throw new AlertDeliveryException("webhook url lost its host before send");
        }
        InetAddress[] fresh;
        try {
            fresh = resolver.resolve(host);
        } catch (Exception e) {
            throw new AlertDeliveryException("webhook host no longer resolves before send");
        }
        if (fresh == null || fresh.length == 0) {
            throw new AlertDeliveryException("webhook host no longer resolves before send");
        }
        Set<String> approvedIps = Arrays.stream(approved)
                .map(InetAddress::getHostAddress)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (InetAddress addr : fresh) {
            // Any address that wasn't in the validated set, or that is now internal, is a rebind.
            if (!approvedIps.contains(addr.getHostAddress()) || WebhookUrlValidator.isBlockedAddress(addr)) {
                throw new AlertDeliveryException("webhook host DNS changed before send (possible rebinding); refusing");
            }
        }
    }

    /** The JSON payload sent to the webhook. Data-only; carries no secret. */
    static Map<String, Object> payload(AlertEvent event) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "threshold_alert");
        m.put("jobId", event.jobId());
        m.put("cube", event.cube());
        m.put("measure", event.measure());
        m.put("comparison", event.comparison().name());
        m.put("value", event.value());
        if (event.previousValue() != null) {
            m.put("previousValue", event.previousValue());
        }
        m.put("threshold", event.threshold());
        m.put("timestamp", event.timestamp());
        return m;
    }
}
