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
import org.saiku.service.mail.MailConfig;
import org.saiku.service.mail.MailMessage;
import org.saiku.service.mail.MailSender;
import org.saiku.service.mail.send.MailLinkBuilder;
import org.saiku.service.mail.send.MailSendDisabledException;
import org.saiku.service.mail.send.MultiRecipientMailService;
import org.saiku.service.schedule.JobHandler;
import org.saiku.service.schedule.ScheduledJobFile;
import org.saiku.service.schedule.alert.MeasureValueReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code DASHBOARD_DIGEST} {@link JobHandler} (saiku#943): on a schedule, email a small summary of
 * key measures plus a prominent deep link to the LIVE dashboard.
 *
 * <p><b>Link-based, lean version.</b> There is NO dashboard renderer, NO headless browser and NO PDF —
 * the digest is a plain HTML table of measure &rarr; current value and a single call-to-action link to
 * the live dashboard. The renderer (#1810) is deliberately out of scope.
 *
 * <p>On each {@link #handle(ScheduledJobFile) run} (the owner-identity {@code JobRunner} has already
 * established the owner's {@code SecurityContext}, so measures are read under exactly the owner's RLS —
 * this handler does NOT re-impersonate):
 *
 * <ol>
 *   <li>Parse + validate the {@link DashboardDigestSpec} from the job payload. A malformed spec throws,
 *       and the engine records a sanitized FAILED run (it never kills the ticker).</li>
 *   <li>Read each measure's CURRENT value via the injected {@link MeasureValueReader} — the SAME
 *       off-request seam the threshold-alert handler uses (production impl {@code AiMeasureValueReader}
 *       builds a fresh {@code ThinQueryService} from singleton collaborators; it NEVER touches the
 *       session-scoped {@code thinQueryBean}, so it is safe on a scheduler worker thread).</li>
 *   <li>Build the HTML body via {@link DashboardDigestContent} (all data-derived strings HTML-escaped)
 *       with the deep link from {@link MailLinkBuilder#dashboardUrl(String)} (ops public base URL + the
 *       validated repo path — SSRF-safe, no request-controlled host).</li>
 *   <li><b>Deliver</b> honouring the send gate:
 *     <ul>
 *       <li>if the spec names recipients, hand the content + recipient set to {@link
 *           MultiRecipientMailService#send} — which enforces the default-OFF master flag +
 *           {@code RecipientGate.clear()} (suppressed? &rarr; allowlisted? &rarr; consent CONFIRMED?).
 *           With the flag OFF (default), subscriptions to others send NOTHING — same safe posture as
 *           everything else. This handler NEVER composes a non-self message that bypasses the gate.</li>
 *       <li>otherwise (no recipients, or the gate/flag cleared none), fall back to the server's own
 *           self-address ({@link MailConfig#selfTo()}) via the #1098 self-email pattern, so a
 *           self-subscription still works out of the box.</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>This handler is read-only over the job payload — it records no per-run state (unlike the
 * threshold-alert handler), so a digest is a pure snapshot each run.
 */
public final class DashboardDigestJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(DashboardDigestJobHandler.class);

    /** Formats a scalar measure value with grouping + up to 4 fraction digits. */
    private static final ThreadLocal<DecimalFormat> VALUE_FORMAT =
            ThreadLocal.withInitial(() -> new DecimalFormat("#,##0.####"));

    private final MeasureValueReader valueReader;
    private final MultiRecipientMailService multiRecipientMailService;
    private final MailSender mailSender;
    private final MailConfig mailConfig;
    private final MailLinkBuilder linkBuilder;

    public DashboardDigestJobHandler(
            MeasureValueReader valueReader,
            MultiRecipientMailService multiRecipientMailService,
            MailSender mailSender,
            MailConfig mailConfig,
            MailLinkBuilder linkBuilder) {
        if (valueReader == null) {
            throw new IllegalArgumentException("valueReader is required");
        }
        if (mailSender == null || mailConfig == null) {
            throw new IllegalArgumentException("mailSender and mailConfig are required");
        }
        this.valueReader = valueReader;
        this.multiRecipientMailService = multiRecipientMailService;
        this.mailSender = mailSender;
        this.mailConfig = mailConfig;
        this.linkBuilder = linkBuilder == null ? new MailLinkBuilder((String) null) : linkBuilder;
    }

    @Override
    public void handle(ScheduledJobFile job) throws Exception {
        if (job == null) {
            throw new IllegalArgumentException("job is required");
        }
        Map<String, Object> payload = job.getPayload();
        DashboardDigestSpec spec = DashboardDigestSpec.fromPayload(payload);

        // (1) Read each measure's current value under the owner's already-established SecurityContext.
        List<DashboardDigestContent.MeasureLine> lines = new ArrayList<>();
        for (DashboardDigestSpec.Measure m : spec.getMeasures()) {
            double value = valueReader.readMeasure(m.getCube(), m.getMeasure(), m.getFilters());
            lines.add(new DashboardDigestContent.MeasureLine(m.getLabel(), format(value)));
        }

        // (2) Build the deep link from the ops public base URL + the validated repo path (SSRF-safe).
        String dashboardUrl =
                spec.getDashboardPath() == null ? null : linkBuilder.dashboardUrl(spec.getDashboardPath());

        // (3) Compose the HTML email (all data-derived strings escaped).
        String subject = DashboardDigestContent.subject(spec.getDashboardTitle());
        String html = DashboardDigestContent.htmlBody(spec.getDashboardTitle(), lines, dashboardUrl);

        // (4) Deliver: through the gate when recipients are named, else self-email fallback.
        deliver(job.getId(), spec.getRecipients(), subject, html);
    }

    /**
     * Route delivery. Non-self recipients ONLY through {@link MultiRecipientMailService} (default-OFF
     * flag + {@code RecipientGate}). If nothing is cleared to send — no recipients, the flag OFF, or the
     * gate cleared none — fall back to the server's own self-address so a self-subscription still works.
     */
    private void deliver(String jobId, List<String> recipients, String subject, String html) throws Exception {
        boolean sentToOthers = false;
        if (recipients != null && !recipients.isEmpty() && multiRecipientMailService != null) {
            try {
                MultiRecipientMailService.Result result = multiRecipientMailService.send(
                        mailSender,
                        mailConfig.from(),
                        recipients,
                        MultiRecipientMailService.MailContent.of(subject, html));
                sentToOthers = result.sent() > 0;
                log.info(
                        "Dashboard digest job {}: recipient send complete (requested={} cleared={} sent={} failed={} dropped={})",
                        jobId,
                        result.requested(),
                        result.cleared(),
                        result.sent(),
                        result.failed(),
                        result.dropped());
            } catch (MailSendDisabledException disabled) {
                // Default posture: the send-to-others master flag is OFF. Nothing was mailed to any
                // non-self address. Fall through to the self-email fallback below.
                log.info(
                        "Dashboard digest job {}: send-to-others disabled — no non-self mail sent; falling back to self",
                        jobId);
            }
        }

        // Self-email fallback: deliver to the server's own configured self-address (never a client /
        // payload-supplied recipient), reusing the #1098 self-send pattern. Only when nothing was sent
        // to others (default when the flag is off, or when no recipients are named).
        if (!sentToOthers) {
            sendSelf(jobId, subject, html);
        }
    }

    /** Fail-closed self-send: recipient is ONLY {@link MailConfig#selfTo()} — never client-supplied. */
    private void sendSelf(String jobId, String subject, String html) throws Exception {
        if (!mailSender.isConfigured()) {
            throw new DashboardDigestDeliveryException("email not configured");
        }
        if (!mailConfig.selfSendConfigured()) {
            throw new DashboardDigestDeliveryException("email recipient (selfTo) not configured");
        }
        MailMessage msg = MailMessage.of(
                mailConfig.selfTo(), // recipient: the server's own address, never client/payload-supplied
                mailConfig.from(),
                subject,
                html,
                List.of(),
                List.of());
        mailSender.send(msg);
        log.info("Dashboard digest job {}: delivered to self-address", jobId);
    }

    private static String format(double value) {
        return VALUE_FORMAT.get().format(value);
    }
}
