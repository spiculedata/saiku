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
import java.util.List;
import java.util.Map;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiFilterSelection;
import org.saiku.service.schedule.alert.PayloadParsing;

/**
 * The parsed, validated configuration of a {@code WEBHOOK_DIGEST} job (saiku#1099), read from the
 * opaque {@link org.saiku.service.schedule.ScheduledJobFile#getPayload() payload} map an admin supplied
 * when creating the job via {@code POST /saiku/admin/jobs}.
 *
 * <p>Companion to the email-based {@link DashboardDigestSpec} (saiku#943): same lean, link-based shape
 * (a small table of key measures' current values plus a deep link back into Saiku), but delivered as a
 * Slack / Microsoft Teams chat message via an admin-pasted incoming-webhook URL instead of email. There
 * is NO dashboard renderer, NO headless browser and NO PNG attachment — the renderer (#1810) is
 * deliberately out of scope, exactly as it was for #943.
 *
 * <p><b>No secrets except the webhook URL itself.</b> Like every job payload this is stored verbatim on
 * disk. The webhook URL is admin-authored and SSRF-validated ({@link ChannelDigestChannel}); it carries
 * no bot token or OAuth credential — that flow is explicitly out of scope (saiku-cloud#1210).
 *
 * <p>Expected payload shape (keys):
 *
 * <pre>{@code
 * {
 *   "channel": {
 *     "type":       "SLACK" | "TEAMS",
 *     "webhookUrl": "https://hooks.slack.com/services/..."   // admin-pasted incoming-webhook URL
 *   },
 *   "dashboard": {
 *     "path":  "shared/exec.saikudash",     // JCR repo path; used to build the live deep link
 *     "title": "Executive Overview"          // optional display title for the message
 *   },
 *   "measures": [                             // one or more measures to summarize
 *     {
 *       "cube":    "conn/catalog/schema/cube",  // string or {connectionName,catalog,schema,cubeName}
 *       "measure": "Unit Sales",
 *       "label":   "Total Units",               // optional display label; defaults to the measure name
 *       "filters": [ ... ]                      // optional slicer(s), AiFilterSelection shape
 *     }
 *   ]
 * }
 * }</pre>
 */
public final class ChannelDigestSpec {

    private final ChannelDigestChannel channel;
    private final String dashboardPath;
    private final String dashboardTitle;
    private final List<Measure> measures;

    ChannelDigestSpec(
            ChannelDigestChannel channel, String dashboardPath, String dashboardTitle, List<Measure> measures) {
        this.channel = channel;
        this.dashboardPath = dashboardPath;
        this.dashboardTitle = dashboardTitle;
        this.measures = measures == null ? new ArrayList<>() : measures;
    }

    /** The validated Slack / Teams incoming-webhook target. Never null. */
    public ChannelDigestChannel getChannel() {
        return channel;
    }

    /** The dashboard's JCR repository path (used to build the deep link), or null when unspecified. */
    public String getDashboardPath() {
        return dashboardPath;
    }

    /** The dashboard's display title for the message, or null. */
    public String getDashboardTitle() {
        return dashboardTitle;
    }

    /** The measures to summarize (never null; guaranteed non-empty by {@link #fromPayload}). */
    public List<Measure> getMeasures() {
        return measures;
    }

    /**
     * Parse and validate the spec from a job payload. Throws {@link IllegalArgumentException} on any
     * missing/invalid field — the engine turns that into a recorded FAILED run (never a stack trace).
     *
     * @param payload the opaque job payload map; may be {@code null}
     */
    public static ChannelDigestSpec fromPayload(Map<String, Object> payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload is required for a WEBHOOK_DIGEST job");
        }

        ChannelDigestChannel channel = ChannelDigestChannel.fromPayload(payload.get("channel"));

        String dashboardPath = null;
        String dashboardTitle = null;
        Object dash = payload.get("dashboard");
        if (dash instanceof Map<?, ?> dm) {
            dashboardPath = PayloadParsing.readString(dm.get("path"));
            dashboardTitle = PayloadParsing.readString(dm.get("title"));
        } else if (dash instanceof String s) {
            dashboardPath = PayloadParsing.readString(s);
        }

        Object rawMeasures = payload.get("measures");
        if (!(rawMeasures instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("payload.measures is required and must be a non-empty list");
        }
        List<Measure> measures = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                throw new IllegalArgumentException("each payload.measures entry must be an object");
            }
            AiCubeRef cube = PayloadParsing.readCube(m.get("cube"));
            if (cube == null) {
                throw new IllegalArgumentException(
                        "payload.measures[].cube is required (string 'conn/cat/schema/cube' or object)");
            }
            String measure = PayloadParsing.readString(m.get("measure"));
            if (measure == null || measure.isBlank()) {
                throw new IllegalArgumentException("payload.measures[].measure is required");
            }
            String label = PayloadParsing.readString(m.get("label"));
            List<AiFilterSelection> filters = PayloadParsing.readFilters(m.get("filters"));
            measures.add(new Measure(cube, measure, label == null ? measure : label, filters));
        }

        return new ChannelDigestSpec(channel, dashboardPath, dashboardTitle, measures);
    }

    /** One measure to summarize: its cube ref, the measure name, a display label, and optional slicers. */
    public static final class Measure {

        private final AiCubeRef cube;
        private final String measure;
        private final String label;
        private final List<AiFilterSelection> filters;

        Measure(AiCubeRef cube, String measure, String label, List<AiFilterSelection> filters) {
            this.cube = cube;
            this.measure = measure;
            this.label = label;
            this.filters = filters == null ? new ArrayList<>() : filters;
        }

        public AiCubeRef getCube() {
            return cube;
        }

        public String getMeasure() {
            return measure;
        }

        /** The display label for the message row (defaults to the measure name). */
        public String getLabel() {
            return label;
        }

        public List<AiFilterSelection> getFilters() {
            return filters;
        }
    }
}
