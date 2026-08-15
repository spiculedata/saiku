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

import java.util.List;

/**
 * Composes the subject + HTML body of a {@code DASHBOARD_DIGEST} email (saiku#943): a small table of
 * measure &rarr; current value plus a prominent deep link to the LIVE dashboard.
 *
 * <p><b>Link-based only.</b> There is NO rendered dashboard, NO image, NO PDF — the email is a plain
 * HTML summary and a single call-to-action link. The renderer (#1810) is out of scope.
 *
 * <p><b>HTML safety.</b> Every data-derived string (measure labels, formatted values, dashboard title)
 * is HTML-escaped with the same {@code escape()} discipline as {@code SelfEmailAlertChannel}, so a
 * measure label like {@code <script>...} is neutralised in the body. The deep-link href is built
 * upstream from the ops-only public base URL + a validated repo path (see {@link
 * org.saiku.service.mail.send.MailLinkBuilder#dashboardUrl(String)}), so it carries no request-controlled
 * host; here it is additionally attribute-escaped defensively.
 */
public final class DashboardDigestContent {

    private DashboardDigestContent() {}

    /** One resolved row of the digest: a display label and the measure's current formatted value. */
    public record MeasureLine(String label, String value) {}

    /**
     * The email subject. Includes the dashboard title when present, else a generic label. The subject is
     * plain text (no HTML), so it is NOT escaped here.
     */
    public static String subject(String dashboardTitle) {
        if (dashboardTitle != null && !dashboardTitle.isBlank()) {
            return "Saiku dashboard digest: " + dashboardTitle;
        }
        return "Saiku dashboard digest";
    }

    /**
     * Build the HTML body: a heading, the measure summary table, and (when a link is available) a
     * prominent "Open the live dashboard" button/link. When {@code dashboardUrl} is null (public base URL
     * unconfigured) the link is omitted and a short note explains the deep link is unavailable.
     *
     * @param dashboardTitle optional display title (escaped)
     * @param lines the resolved measure rows (labels + values, both escaped)
     * @param dashboardUrl the absolute deep link, or null when unavailable (then no link is emitted)
     */
    public static String htmlBody(String dashboardTitle, List<MeasureLine> lines, String dashboardUrl) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><body>");
        sb.append("<h2>").append(escape(heading(dashboardTitle))).append("</h2>");
        sb.append("<p>Here is your scheduled summary of key measures.</p>");

        sb.append("<table cellpadding=\"6\" cellspacing=\"0\" border=\"1\">");
        sb.append("<thead><tr><th align=\"left\">Measure</th><th align=\"left\">Current value</th></tr></thead>");
        sb.append("<tbody>");
        if (lines != null) {
            for (MeasureLine line : lines) {
                sb.append("<tr><td>")
                        .append(escape(line.label()))
                        .append("</td><td>")
                        .append(escape(line.value()))
                        .append("</td></tr>");
            }
        }
        sb.append("</tbody></table>");

        if (dashboardUrl != null && !dashboardUrl.isBlank()) {
            sb.append("<p style=\"margin-top:16px\">");
            sb.append("<a href=\"")
                    .append(escape(dashboardUrl))
                    .append("\" style=\"display:inline-block;padding:10px 18px;background:#2b6cb0;")
                    .append("color:#ffffff;text-decoration:none;border-radius:4px\">")
                    .append("Open the live dashboard")
                    .append("</a>");
            sb.append("</p>");
        } else {
            sb.append("<p style=\"margin-top:16px;color:#666\"><em>The live dashboard link is unavailable ")
                    .append("(the server's public base URL is not configured).</em></p>");
        }

        sb.append("</body></html>");
        return sb.toString();
    }

    private static String heading(String dashboardTitle) {
        if (dashboardTitle != null && !dashboardTitle.isBlank()) {
            return dashboardTitle;
        }
        return "Dashboard digest";
    }

    /**
     * Minimal HTML escaping — measure/dashboard names are admin-controlled, but escape defensively so a
     * value like {@code <script>} is neutralised in the email body (mirrors {@code SelfEmailAlertChannel}).
     */
    static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
