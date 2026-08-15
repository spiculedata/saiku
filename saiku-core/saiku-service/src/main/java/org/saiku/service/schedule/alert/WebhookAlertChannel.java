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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * <p>Uses the JDK {@link HttpClient} (auto-instrumented by the OTel agent when attached). The client is
 * injectable so tests capture the exact request without a live server.
 */
public final class WebhookAlertChannel implements AlertChannel {

    private static final Logger log = LoggerFactory.getLogger(WebhookAlertChannel.class);
    private static final ObjectMapper MAPPER =
            new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private final HttpClient http;
    private final Duration requestTimeout;

    public WebhookAlertChannel() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), Duration.ofSeconds(15));
    }

    /** Visible for tests — inject a stub {@link HttpClient} to capture the posted request. */
    public WebhookAlertChannel(HttpClient http, Duration requestTimeout) {
        if (http == null) {
            throw new IllegalArgumentException("HttpClient is required");
        }
        this.http = http;
        this.requestTimeout = requestTimeout == null ? Duration.ofSeconds(15) : requestTimeout;
    }

    @Override
    public void deliver(AlertEvent event, AlertChannelConfig config) throws Exception {
        if (config == null || config.getType() != AlertChannelConfig.Type.WEBHOOK) {
            throw new IllegalArgumentException("WebhookAlertChannel requires a WEBHOOK channel config");
        }
        // Defence-in-depth: re-validate the target right before we open a connection.
        URI target = WebhookUrlValidator.validate(config.getWebhookUrl());

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
