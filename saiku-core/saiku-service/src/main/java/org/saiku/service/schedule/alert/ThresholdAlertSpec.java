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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiFilterSelection;

/**
 * The parsed, validated configuration of a {@code THRESHOLD_ALERT} job (saiku#1098), read from the
 * opaque {@link org.saiku.service.schedule.ScheduledJobFile#getPayload() payload} map an admin supplied
 * when creating the job via {@code POST /saiku/admin/jobs}.
 *
 * <p><b>No secrets.</b> Like every job payload this is stored verbatim on disk, so it must never carry
 * credentials or tokens. The webhook URL is admin-authored and validated ({@link
 * org.saiku.service.schedule.alert.WebhookUrlValidator}); the email channel resolves the server's own
 * self-address at send time from the mail-config layer — nothing sensitive lives here.
 *
 * <p>Expected payload shape (keys):
 *
 * <pre>{@code
 * {
 *   "cube":        "connection/catalog/schema/cubeName",   // or a {connectionName,catalog,schema,cubeName} object
 *   "measure":     "Unit Sales",                            // measure name as exposed by the cube schema
 *   "comparison":  "GT" | "LT" | "ABS_CHANGE" | "PCT_CHANGE",
 *   "threshold":   1000,                                    // number
 *   "cooldownMillis": 3600000,                              // optional; default 0 (no cooldown)
 *   "filters":     [ ... ],                                 // optional slicer(s), same shape as AiFilterSelection
 *   "channel": {
 *     "type": "WEBHOOK" | "EMAIL_SELF",
 *     "url":  "https://hooks.example.com/..."               // required for WEBHOOK; SSRF-validated
 *   }
 * }
 * }</pre>
 */
public final class ThresholdAlertSpec {

    /** Payload key under which the handler records the value observed on the last run (for change comparisons). */
    public static final String KEY_LAST_VALUE = "lastObservedValue";

    /** Payload key under which the handler records epoch-millis of the last fired breach (for cooldown). */
    public static final String KEY_LAST_FIRED_AT = "lastFiredAt";

    private final AiCubeRef cube;
    private final String measure;
    private final AlertComparison comparison;
    private final double threshold;
    private final long cooldownMillis;
    private final List<AiFilterSelection> filters;
    private final AlertChannelConfig channel;

    ThresholdAlertSpec(
            AiCubeRef cube,
            String measure,
            AlertComparison comparison,
            double threshold,
            long cooldownMillis,
            List<AiFilterSelection> filters,
            AlertChannelConfig channel) {
        this.cube = cube;
        this.measure = measure;
        this.comparison = comparison;
        this.threshold = threshold;
        this.cooldownMillis = cooldownMillis;
        this.filters = filters == null ? new ArrayList<>() : filters;
        this.channel = channel;
    }

    public AiCubeRef getCube() {
        return cube;
    }

    public String getMeasure() {
        return measure;
    }

    public AlertComparison getComparison() {
        return comparison;
    }

    public double getThreshold() {
        return threshold;
    }

    public long getCooldownMillis() {
        return cooldownMillis;
    }

    public List<AiFilterSelection> getFilters() {
        return filters;
    }

    public AlertChannelConfig getChannel() {
        return channel;
    }

    /**
     * Parse and validate the spec from a job payload. Throws {@link IllegalArgumentException} on any
     * missing/invalid field — the engine turns that into a recorded FAILED run (never a stack trace).
     *
     * @param payload the opaque job payload map; may be {@code null}
     */
    public static ThresholdAlertSpec fromPayload(Map<String, Object> payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload is required for a THRESHOLD_ALERT job");
        }
        AiCubeRef cube = PayloadParsing.readCube(payload.get("cube"));
        if (cube == null) {
            throw new IllegalArgumentException("payload.cube is required (string 'conn/cat/schema/cube' or object)");
        }
        String measure = PayloadParsing.readString(payload.get("measure"));
        if (measure == null || measure.isBlank()) {
            throw new IllegalArgumentException("payload.measure is required");
        }
        AlertComparison comparison = AlertComparison.parse(PayloadParsing.readString(payload.get("comparison")));
        Double threshold = PayloadParsing.readDouble(payload.get("threshold"));
        if (threshold == null) {
            throw new IllegalArgumentException("payload.threshold is required and must be a number");
        }
        long cooldownMillis = 0L;
        Double cd = PayloadParsing.readDouble(payload.get("cooldownMillis"));
        if (cd != null) {
            if (cd < 0) {
                throw new IllegalArgumentException("payload.cooldownMillis must be >= 0");
            }
            cooldownMillis = cd.longValue();
        }
        List<AiFilterSelection> filters = PayloadParsing.readFilters(payload.get("filters"));
        AlertChannelConfig channel = AlertChannelConfig.fromPayload(payload.get("channel"));
        return new ThresholdAlertSpec(cube, measure, comparison, threshold, cooldownMillis, filters, channel);
    }
}
