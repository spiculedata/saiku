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

import java.util.Locale;
import java.util.Map;
import org.saiku.service.schedule.alert.PayloadParsing;
import org.saiku.service.schedule.alert.WebhookUrlValidator;

/**
 * Where a {@code WEBHOOK_DIGEST} job posts its digest (saiku#1099): an admin-pasted Slack or Microsoft
 * Teams <b>incoming-webhook URL</b>. This is the webhooks-only, no-OAuth path — there is no bot token,
 * no stored credential, and no "Add Saiku to your workspace" install flow anywhere in this type (that
 * OAuth-bot / Graph one-click variant is explicitly out of scope for this repo; see saiku-cloud#1210).
 *
 * <p>The URL is SSRF-validated at parse time (same {@link WebhookUrlValidator} saiku#1098 built for
 * threshold-alert webhooks: https-only, no loopback/link-local/private/internal host) and again
 * immediately before every send by {@link org.saiku.service.schedule.alert.WebhookJsonSender}.
 */
public final class ChannelDigestChannel {

    public enum Type {
        SLACK,
        TEAMS
    }

    private final Type type;
    private final String webhookUrl;

    private ChannelDigestChannel(Type type, String webhookUrl) {
        this.type = type;
        this.webhookUrl = webhookUrl;
    }

    public Type getType() {
        return type;
    }

    /** The validated incoming-webhook URL. Never null on a successfully parsed channel. */
    public String getWebhookUrl() {
        return webhookUrl;
    }

    /**
     * Parse and validate the {@code payload.channel} block. Throws {@link IllegalArgumentException} on a
     * missing/unknown type or a missing / SSRF-unsafe URL.
     */
    public static ChannelDigestChannel fromPayload(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("payload.channel is required (object with a 'type' and 'webhookUrl')");
        }
        String typeStr = PayloadParsing.readString(m.get("type"));
        if (typeStr == null) {
            throw new IllegalArgumentException("payload.channel.type is required (SLACK or TEAMS)");
        }
        Type type;
        try {
            type = Type.valueOf(typeStr.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown channel type '" + typeStr + "' — expected SLACK or TEAMS");
        }
        String url = PayloadParsing.readString(m.get("webhookUrl"));
        if (url == null) {
            throw new IllegalArgumentException("payload.channel.webhookUrl is required");
        }
        // SSRF gate: throws IllegalArgumentException on any unsafe / non-https / internal host.
        WebhookUrlValidator.validate(url);
        return new ChannelDigestChannel(type, url);
    }

    /**
     * Build a channel config for a URL <b>without</b> running the live-DNS SSRF gate. Visible for tests
     * that inject their own resolver into {@link org.saiku.service.schedule.alert.WebhookJsonSender}
     * (which re-validates before send) so a hostname-based rebinding scenario stays hermetic. Production
     * always goes through {@link #fromPayload(Object)}, which validates.
     */
    static ChannelDigestChannel forWebhookUrlUnvalidated(Type type, String url) {
        return new ChannelDigestChannel(type, url);
    }
}
