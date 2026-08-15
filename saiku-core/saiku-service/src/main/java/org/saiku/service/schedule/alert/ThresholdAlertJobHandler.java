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

import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;
import org.saiku.service.schedule.JobHandler;
import org.saiku.service.schedule.ScheduledJobFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The first real {@link JobHandler} (saiku#1098): evaluate a measure on a cadence and fire an alert
 * when it crosses a configured threshold. <b>Scheduler-only</b> — it never uses the send-to-others gate
 * or the analysis renderer. Alerts go to a webhook or to the server's own self-address only.
 *
 * <p>On each {@link #handle(ScheduledJobFile) run} (the scheduler already established the owner's
 * {@code SecurityContext}, so this queries under exactly the owner's RLS scope — it does NOT
 * re-impersonate):
 *
 * <ol>
 *   <li>Parse + validate the {@link ThresholdAlertSpec} from the job payload. A malformed spec throws,
 *       and the engine records a sanitized FAILED run.</li>
 *   <li>Read the measure's CURRENT value via the injected {@link MeasureValueReader}.</li>
 *   <li>Decide a breach with {@link AlertComparison} (GT / LT / ABS_CHANGE / PCT_CHANGE), comparing —
 *       for the change comparisons — against the value observed on the PREVIOUS run (tracked in the
 *       job's own payload). Always record the current value as the new baseline.</li>
 *   <li>On a breach, honour the cooldown: if the last fired breach is within {@code cooldownMillis},
 *       suppress this fire (record OK, do nothing). Otherwise deliver via the configured {@link
 *       AlertChannel} and stamp the fire time.</li>
 *   <li>On no breach, record OK and do nothing.</li>
 * </ol>
 *
 * <p><b>State persistence.</b> The handler mutates {@code job.getPayload()} (last-observed value +
 * last-fired timestamp). The scheduler saves the same job record after a successful run, so this
 * per-job state survives without a separate store. It is fail-safe: a missing / unreadable marker is
 * treated as "no prior observation" (first-run baseline) and "not in cooldown".
 */
public final class ThresholdAlertJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(ThresholdAlertJobHandler.class);

    private final MeasureValueReader valueReader;
    private final Map<AlertChannelConfig.Type, AlertChannel> channels;
    private final Clock clock;

    public ThresholdAlertJobHandler(
            MeasureValueReader valueReader, AlertChannel webhookChannel, AlertChannel emailSelfChannel) {
        this(valueReader, webhookChannel, emailSelfChannel, Clock.systemUTC());
    }

    /** Visible for tests — inject a controllable {@link Clock} so cooldown windows are deterministic. */
    public ThresholdAlertJobHandler(
            MeasureValueReader valueReader, AlertChannel webhookChannel, AlertChannel emailSelfChannel, Clock clock) {
        if (valueReader == null) {
            throw new IllegalArgumentException("valueReader is required");
        }
        this.valueReader = valueReader;
        this.channels = new EnumMap<>(AlertChannelConfig.Type.class);
        if (webhookChannel != null) {
            this.channels.put(AlertChannelConfig.Type.WEBHOOK, webhookChannel);
        }
        if (emailSelfChannel != null) {
            this.channels.put(AlertChannelConfig.Type.EMAIL_SELF, emailSelfChannel);
        }
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public void handle(ScheduledJobFile job) throws Exception {
        if (job == null) {
            throw new IllegalArgumentException("job is required");
        }
        Map<String, Object> payload = job.getPayload();
        ThresholdAlertSpec spec = ThresholdAlertSpec.fromPayload(payload);

        // (2) Read the current value under the owner's already-established SecurityContext (no re-impersonation).
        double current = valueReader.readMeasure(spec.getCube(), spec.getMeasure(), spec.getFilters());

        // (3) Evaluate against the previous observation (for change comparisons).
        Double previous = readPreviousValue(payload);
        boolean breached = spec.getComparison().breached(current, previous, spec.getThreshold());

        // Always record the fresh baseline for the next run's change comparison.
        payload.put(ThresholdAlertSpec.KEY_LAST_VALUE, current);

        if (!breached) {
            log.debug(
                    "Threshold alert job {}: no breach (measure={}, value={}, comparison={}, threshold={})",
                    job.getId(),
                    spec.getMeasure(),
                    current,
                    spec.getComparison(),
                    spec.getThreshold());
            return;
        }

        long now = clock.millis();
        // (4) Cooldown: suppress a re-fire within cooldownMillis of the last fired breach.
        if (inCooldown(payload, spec.getCooldownMillis(), now)) {
            log.info(
                    "Threshold alert job {}: breach suppressed by cooldown ({}ms)",
                    job.getId(),
                    spec.getCooldownMillis());
            return;
        }

        AlertChannel channel = channels.get(spec.getChannel().getType());
        if (channel == null) {
            throw new AlertDeliveryException(
                    "no channel wired for type " + spec.getChannel().getType());
        }

        AlertEvent event = new AlertEvent(
                job.getId(),
                spec.getCube().toString(),
                spec.getMeasure(),
                spec.getComparison(),
                current,
                previous,
                spec.getThreshold(),
                now);
        channel.deliver(event, spec.getChannel());

        // Stamp the fire time only AFTER a successful deliver, so a failed send doesn't start the cooldown.
        payload.put(ThresholdAlertSpec.KEY_LAST_FIRED_AT, now);
        log.info(
                "Threshold alert job {}: FIRED via {} (measure={}, value={}, threshold={})",
                job.getId(),
                spec.getChannel().getType(),
                spec.getMeasure(),
                current,
                spec.getThreshold());
    }

    private static Double readPreviousValue(Map<String, Object> payload) {
        Object v = payload == null ? null : payload.get(ThresholdAlertSpec.KEY_LAST_VALUE);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** Fail-safe cooldown check: an unreadable / absent marker means "not in cooldown". */
    private static boolean inCooldown(Map<String, Object> payload, long cooldownMillis, long now) {
        if (cooldownMillis <= 0 || payload == null) {
            return false;
        }
        Object v = payload.get(ThresholdAlertSpec.KEY_LAST_FIRED_AT);
        long lastFired;
        if (v instanceof Number n) {
            lastFired = n.longValue();
        } else if (v instanceof String s) {
            try {
                lastFired = Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return false;
            }
        } else {
            return false;
        }
        return now - lastFired < cooldownMillis;
    }
}
