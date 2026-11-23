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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Composes the JSON body of a {@code WEBHOOK_DIGEST} chat message (saiku#1099): a small summary of
 * measure &rarr; current value plus a prominent deep link to the LIVE dashboard, shaped for a Slack or
 * Microsoft Teams incoming webhook.
 *
 * <p><b>Link-based only</b> — same posture as the email digest ({@code DashboardDigestContent},
 * saiku#943): there is NO rendered dashboard image and NO PNG/PDF attachment here. The renderer
 * (#1810) is deliberately out of scope; when it lands, a thumbnail can be attached alongside this text
 * summary without changing the shape below.
 *
 * <p>Both payloads are built as plain {@code Map}/{@code List} trees (Jackson-serializable), never
 * hand-assembled JSON strings, so nested string values are safely quoted/escaped by the serializer —
 * there is no HTML-escaping concern here the way there is for the email body.
 */
public final class ChannelDigestContent {

    private ChannelDigestContent() {}

    /** One resolved row of the digest: a display label and the measure's current formatted value. */
    public record MeasureLine(String label, String value) {}

    private static String heading(String dashboardTitle) {
        if (dashboardTitle != null && !dashboardTitle.isBlank()) {
            return dashboardTitle;
        }
        return "Dashboard digest";
    }

    /**
     * Build a Slack <a href="https://api.slack.com/block-kit">Block Kit</a> {@code blocks} payload: a
     * header, a bulleted measure summary, and (when a link is available) a button linking back to the
     * live dashboard.
     */
    public static Map<String, Object> slackPayload(
            String dashboardTitle, List<MeasureLine> lines, String dashboardUrl) {
        List<Object> blocks = new ArrayList<>();

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("type", "header");
        header.put("text", plainText(heading(dashboardTitle)));
        blocks.add(header);

        StringBuilder summary = new StringBuilder();
        if (lines != null) {
            for (MeasureLine line : lines) {
                if (summary.length() > 0) {
                    summary.append('\n');
                }
                summary.append("*")
                        .append(mrkdwnEscape(line.label()))
                        .append(":* ")
                        .append(mrkdwnEscape(line.value()));
            }
        }
        if (summary.length() > 0) {
            Map<String, Object> section = new LinkedHashMap<>();
            section.put("type", "section");
            section.put("text", mrkdwn(summary.toString()));
            blocks.add(section);
        }

        if (dashboardUrl != null && !dashboardUrl.isBlank()) {
            Map<String, Object> button = new LinkedHashMap<>();
            button.put("type", "button");
            button.put("text", plainText("Open the live dashboard"));
            button.put("url", dashboardUrl);

            Map<String, Object> actions = new LinkedHashMap<>();
            actions.put("type", "actions");
            actions.put("elements", List.of(button));
            blocks.add(actions);
        } else {
            Map<String, Object> note = new LinkedHashMap<>();
            note.put("type", "context");
            note.put(
                    "elements",
                    List.of(
                            mrkdwn(
                                    "_The live dashboard link is unavailable (the server's public base URL is not configured)._")));
            blocks.add(note);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", heading(dashboardTitle)); // fallback text for notifications/screen readers
        payload.put("blocks", blocks);
        return payload;
    }

    /**
     * Build a Microsoft Teams incoming-webhook <a
     * href="https://learn.microsoft.com/en-us/microsoftteams/platform/webhooks-and-connectors/how-to/connectors-using">{@code
     * MessageCard}</a> payload: a title, a measure summary in the body text, and (when a link is
     * available) a {@code potentialAction} that opens the live dashboard.
     */
    public static Map<String, Object> teamsPayload(
            String dashboardTitle, List<MeasureLine> lines, String dashboardUrl) {
        StringBuilder body = new StringBuilder("Here is your scheduled summary of key measures.\n\n");
        if (lines != null) {
            for (MeasureLine line : lines) {
                body.append("**")
                        .append(line.label())
                        .append(":** ")
                        .append(line.value())
                        .append("\n\n");
            }
        }
        if (dashboardUrl == null || dashboardUrl.isBlank()) {
            body.append("_The live dashboard link is unavailable (the server's public base URL is not configured)._");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("@type", "MessageCard");
        payload.put("@context", "http://schema.org/extensions");
        payload.put("summary", heading(dashboardTitle));
        payload.put("themeColor", "2B6CB0");
        payload.put("title", heading(dashboardTitle));
        payload.put("text", body.toString());

        if (dashboardUrl != null && !dashboardUrl.isBlank()) {
            Map<String, Object> target = new LinkedHashMap<>();
            target.put("os", "default");
            target.put("uri", dashboardUrl);

            Map<String, Object> action = new LinkedHashMap<>();
            action.put("@type", "OpenUri");
            action.put("name", "Open the live dashboard");
            action.put("targets", List.of(target));

            payload.put("potentialAction", List.of(action));
        }
        return payload;
    }

    private static Map<String, Object> plainText(String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "plain_text");
        m.put("text", text);
        m.put("emoji", true);
        return m;
    }

    private static Map<String, Object> mrkdwn(String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "mrkdwn");
        m.put("text", text);
        return m;
    }

    /** Escape Slack {@code mrkdwn} special characters in data-derived text (mirrors Slack's own guidance). */
    static String mrkdwnEscape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
