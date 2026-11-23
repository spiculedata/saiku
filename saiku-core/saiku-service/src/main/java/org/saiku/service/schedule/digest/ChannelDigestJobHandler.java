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

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.saiku.service.mail.send.MailLinkBuilder;
import org.saiku.service.schedule.JobHandler;
import org.saiku.service.schedule.ScheduledJobFile;
import org.saiku.service.schedule.alert.MeasureValueReader;
import org.saiku.service.schedule.alert.WebhookJsonSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code WEBHOOK_DIGEST} {@link JobHandler} (saiku#1099): on a schedule, post a small summary of key
 * measures plus a prominent deep link to the LIVE dashboard to an admin-pasted Slack or Microsoft Teams
 * <b>incoming-webhook URL</b>.
 *
 * <p><b>Webhooks only — no OAuth.</b> An admin pastes a Slack or Teams incoming-webhook URL and Saiku
 * HTTP-POSTs the digest to it. There is no bot token, no stored OAuth credential, and no "Add Saiku to
 * your workspace" install flow here — that variant is explicitly out of scope for this repo (see
 * saiku-cloud#1210).
 *
 * <p><b>Link-based, lean version.</b> Same posture as the email digest ({@link DashboardDigestJobHandler},
 * saiku#943): there is NO dashboard renderer, NO headless browser and NO PNG/PDF attached to the
 * message — just a text summary and a call-to-action link. The renderer (#1810) is deliberately out of
 * scope; wiring a rendered thumbnail into the payload built by {@link ChannelDigestContent} is a
 * follow-up once #1810 lands.
 *
 * <p>On each {@link #handle(ScheduledJobFile) run} (the owner-identity {@code JobRunner} has already
 * established the owner's {@code SecurityContext}, so measures are read under exactly the owner's RLS —
 * this handler does NOT re-impersonate):
 *
 * <ol>
 *   <li>Parse + validate the {@link ChannelDigestSpec} from the job payload. A malformed spec (including
 *       an SSRF-unsafe webhook URL) throws, and the engine records a sanitized FAILED run (it never kills
 *       the ticker).</li>
 *   <li>Read each measure's CURRENT value via the injected {@link MeasureValueReader} — the SAME
 *       off-request seam the threshold-alert and dashboard-digest handlers use.</li>
 *   <li>Build the deep link from {@link MailLinkBuilder#dashboardUrl(String)} (ops public base URL + the
 *       validated repo path — SSRF-safe, no request-controlled host).</li>
 *   <li>Build the channel-shaped payload via {@link ChannelDigestContent} (Slack Block Kit or Teams
 *       MessageCard) and POST it via {@link WebhookJsonSender} — SSRF-validated at parse time AND again
 *       immediately before send, with DNS-rebinding hardening.</li>
 * </ol>
 *
 * <p>There is no "self" fallback here (unlike the email digest) — a chat webhook has no equivalent of a
 * server self-address. A delivery failure simply fails the run; the scheduler's backoff/auto-disable
 * takes over from there.
 */
public final class ChannelDigestJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(ChannelDigestJobHandler.class);

    /** Formats a scalar measure value with grouping + up to 4 fraction digits. */
    private static final ThreadLocal<DecimalFormat> VALUE_FORMAT =
            ThreadLocal.withInitial(() -> new DecimalFormat("#,##0.####"));

    private final MeasureValueReader valueReader;
    private final WebhookJsonSender webhookSender;
    private final MailLinkBuilder linkBuilder;

    public ChannelDigestJobHandler(
            MeasureValueReader valueReader, WebhookJsonSender webhookSender, MailLinkBuilder linkBuilder) {
        if (valueReader == null) {
            throw new IllegalArgumentException("valueReader is required");
        }
        if (webhookSender == null) {
            throw new IllegalArgumentException("webhookSender is required");
        }
        this.valueReader = valueReader;
        this.webhookSender = webhookSender;
        this.linkBuilder = linkBuilder == null ? new MailLinkBuilder((String) null) : linkBuilder;
    }

    @Override
    public void handle(ScheduledJobFile job) throws Exception {
        if (job == null) {
            throw new IllegalArgumentException("job is required");
        }
        Map<String, Object> payload = job.getPayload();
        ChannelDigestSpec spec = ChannelDigestSpec.fromPayload(payload);

        // (1) Read each measure's current value under the owner's already-established SecurityContext.
        List<ChannelDigestContent.MeasureLine> lines = new ArrayList<>();
        for (ChannelDigestSpec.Measure m : spec.getMeasures()) {
            double value = valueReader.readMeasure(m.getCube(), m.getMeasure(), m.getFilters());
            lines.add(new ChannelDigestContent.MeasureLine(m.getLabel(), format(value)));
        }

        // (2) Build the deep link from the ops public base URL + the validated repo path (SSRF-safe).
        String dashboardUrl =
                spec.getDashboardPath() == null ? null : linkBuilder.dashboardUrl(spec.getDashboardPath());

        // (3) Build the channel-shaped payload and deliver.
        ChannelDigestChannel channel = spec.getChannel();
        Map<String, Object> body =
                switch (channel.getType()) {
                    case SLACK -> ChannelDigestContent.slackPayload(spec.getDashboardTitle(), lines, dashboardUrl);
                    case TEAMS -> ChannelDigestContent.teamsPayload(spec.getDashboardTitle(), lines, dashboardUrl);
                };

        webhookSender.send(channel.getWebhookUrl(), body, "Saiku-ChannelDigest");
        log.info("Channel digest job {}: delivered via {}", job.getId(), channel.getType());
    }

    private static String format(double value) {
        return VALUE_FORMAT.get().format(value);
    }
}
