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

import java.util.Locale;
import java.util.Map;

/**
 * Where a breached {@code THRESHOLD_ALERT} sends its notification (saiku#1098). Exactly two channels
 * are supported, both scheduler-only — <b>neither the send-to-others gate nor the renderer is
 * involved</b>:
 *
 * <ul>
 *   <li>{@link Type#WEBHOOK} — POST a small JSON payload to an admin-authored URL. The URL is
 *       SSRF-validated at parse time ({@link WebhookUrlValidator}): https-only, no
 *       loopback/link-local/private/internal host. There is no request-time host from any untrusted
 *       source.</li>
 *   <li>{@link Type#EMAIL_SELF} — email the server's OWN configured self-address ({@code selfTo}),
 *       reusing the same self-send recipient the {@code /email/self} endpoint uses. There is NO
 *       arbitrary-recipient path here — that is the gated surface and is out of scope for alerts.</li>
 * </ul>
 */
public final class AlertChannelConfig {

    public enum Type {
        WEBHOOK,
        EMAIL_SELF
    }

    private final Type type;
    private final String webhookUrl;

    private AlertChannelConfig(Type type, String webhookUrl) {
        this.type = type;
        this.webhookUrl = webhookUrl;
    }

    public Type getType() {
        return type;
    }

    /** The validated webhook URL, or null for the email-self channel. */
    public String getWebhookUrl() {
        return webhookUrl;
    }

    /**
     * Parse and validate the channel block. Throws {@link IllegalArgumentException} on a missing /
     * unknown type, or (for WEBHOOK) a missing / SSRF-unsafe URL.
     */
    public static AlertChannelConfig fromPayload(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("payload.channel is required (object with a 'type')");
        }
        String typeStr = PayloadParsing.readString(m.get("type"));
        if (typeStr == null) {
            throw new IllegalArgumentException("payload.channel.type is required (WEBHOOK or EMAIL_SELF)");
        }
        Type type;
        try {
            type = Type.valueOf(typeStr.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "unknown channel type '" + typeStr + "' — expected WEBHOOK or EMAIL_SELF");
        }
        if (type == Type.WEBHOOK) {
            String url = PayloadParsing.readString(m.get("url"));
            if (url == null) {
                throw new IllegalArgumentException("payload.channel.url is required for a WEBHOOK channel");
            }
            // SSRF gate: throws IllegalArgumentException on any unsafe / non-https / internal host.
            WebhookUrlValidator.validate(url);
            return new AlertChannelConfig(Type.WEBHOOK, url);
        }
        return new AlertChannelConfig(Type.EMAIL_SELF, null);
    }
}
