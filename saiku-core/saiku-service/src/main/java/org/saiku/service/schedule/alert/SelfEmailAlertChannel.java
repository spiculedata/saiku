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

import java.time.Instant;
import java.util.List;
import org.saiku.service.mail.MailConfig;
import org.saiku.service.mail.MailMessage;
import org.saiku.service.mail.MailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Emails a fired {@link AlertEvent} to the server's OWN configured self-address (saiku#1098).
 *
 * <p><b>Self-send only.</b> The recipient is {@code MailConfig.selfTo()} — the exact same
 * admin-configured server address the {@code /email/self} endpoint uses — and NOTHING else. There is
 * no client / payload / event-supplied recipient anywhere in this class: the arbitrary-recipient path
 * is the gated surface and is deliberately out of scope for alerts. This never touches the
 * send-to-others recipient gate or the analysis renderer.
 *
 * <p>Fails closed: if no real transport is configured, or no self-address is set, delivery throws so
 * the engine records a clear FAILED (rather than silently dropping a breach).
 */
public final class SelfEmailAlertChannel implements AlertChannel {

    private static final Logger log = LoggerFactory.getLogger(SelfEmailAlertChannel.class);

    private final MailSender mailSender;
    private final MailConfig mailConfig;

    public SelfEmailAlertChannel(MailSender mailSender, MailConfig mailConfig) {
        if (mailSender == null || mailConfig == null) {
            throw new IllegalArgumentException("mailSender and mailConfig are required");
        }
        this.mailSender = mailSender;
        this.mailConfig = mailConfig;
    }

    @Override
    public void deliver(AlertEvent event, AlertChannelConfig config) throws Exception {
        if (config == null || config.getType() != AlertChannelConfig.Type.EMAIL_SELF) {
            throw new IllegalArgumentException("SelfEmailAlertChannel requires an EMAIL_SELF channel config");
        }
        if (!mailSender.isConfigured()) {
            throw new AlertDeliveryException("email not configured");
        }
        // Fail closed: the recipient comes ONLY from the admin-configured server self-address.
        if (!mailConfig.selfSendConfigured()) {
            throw new AlertDeliveryException("email recipient (selfTo) not configured");
        }
        MailMessage msg = MailMessage.of(
                mailConfig.selfTo(), // recipient: the server's own address, never client/event-supplied
                mailConfig.from(),
                subject(event),
                htmlBody(event),
                List.of(),
                List.of());
        mailSender.send(msg);
        log.info("Threshold alert email delivered for job {} to self-address", event.jobId());
    }

    static String subject(AlertEvent event) {
        return "Saiku alert: " + event.measure() + " " + describe(event.comparison()) + " threshold";
    }

    static String htmlBody(AlertEvent event) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><body>");
        sb.append("<h2>Threshold alert</h2>");
        sb.append("<p>A scheduled threshold alert fired.</p>");
        sb.append("<ul>");
        sb.append(row("Cube", event.cube()));
        sb.append(row("Measure", event.measure()));
        sb.append(row("Comparison", event.comparison().name()));
        sb.append(row("Current value", String.valueOf(event.value())));
        if (event.previousValue() != null) {
            sb.append(row("Previous value", String.valueOf(event.previousValue())));
        }
        sb.append(row("Threshold", String.valueOf(event.threshold())));
        sb.append(row("Detected at", Instant.ofEpochMilli(event.timestamp()).toString()));
        sb.append("</ul>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private static String row(String label, String value) {
        return "<li><strong>" + escape(label) + ":</strong> " + escape(value) + "</li>";
    }

    private static String describe(AlertComparison c) {
        return switch (c) {
            case GT -> "above";
            case LT -> "below";
            case ABS_CHANGE -> "changed past";
            case PCT_CHANGE -> "moved past";
        };
    }

    /** Minimal HTML escaping — the cube/measure names are admin-controlled, but escape defensively. */
    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
