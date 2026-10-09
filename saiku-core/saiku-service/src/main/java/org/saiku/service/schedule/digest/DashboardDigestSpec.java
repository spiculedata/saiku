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
import java.util.Locale;
import java.util.Map;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiFilterSelection;
import org.saiku.service.schedule.alert.PayloadParsing;

/**
 * The parsed, validated configuration of a {@code DASHBOARD_DIGEST} job (saiku#943), read from the
 * opaque {@link org.saiku.service.schedule.ScheduledJobFile#getPayload() payload} map an admin supplied
 * when creating the job via {@code POST /saiku/admin/jobs}.
 *
 * <p>This is the <b>lean, link-based</b> subscription: the digest emails a small table of key measures'
 * current values plus a prominent deep link to the LIVE dashboard. There is NO dashboard renderer, NO
 * headless browser and NO PDF — the renderer (#1810) is deliberately out of scope.
 *
 * <p><b>No secrets.</b> Like every job payload this is stored verbatim on disk, so it must never carry
 * credentials or tokens. The deep-link host resolves at send time from the ops-only public base URL (via
 * {@link org.saiku.service.mail.send.MailLinkBuilder}); nothing sensitive lives here.
 *
 * <p>Expected payload shape (keys):
 *
 * <pre>{@code
 * {
 *   "dashboard": {
 *     "path":  "shared/exec.saikudash",     // JCR repo path; used to build the live deep link
 *     "title": "Executive Overview"          // optional display title for the email
 *   },
 *   "measures": [                             // one or more measures to summarize
 *     {
 *       "cube":    "conn/catalog/schema/cube",  // string or {connectionName,catalog,schema,cubeName}
 *       "measure": "Unit Sales",
 *       "label":   "Total Units",               // optional display label; defaults to the measure name
 *       "filters": [ ... ],                     // optional slicer(s), AiFilterSelection shape
 *       "period":  {                            // optional period-over-period slicer (saiku#1119)
 *         "dimension": "Time", "hierarchy": "Time", "level": "Quarter",
 *         "current":   "qtd"                    // optional; default "qtd"
 *       }
 *     }
 *   ],
 *   "insight": {                                // optional insight block (saiku#1119); absent = #943 behaviour
 *     "narrate":    true,                       // optional; default true — LLM narration (falls back to template)
 *     "maxBullets": 3                           // optional; default 3
 *   },
 *   "recipients": [ "ops@example.com", ... ]  // optional; empty/absent => self-email fallback
 * }
 * }</pre>
 */
public final class DashboardDigestSpec {

    private final String dashboardPath;
    private final String dashboardTitle;
    private final List<Measure> measures;
    private final List<String> recipients;
    private final boolean insightEnabled;
    private final boolean insightNarrate;
    private final int insightMaxBullets;

    DashboardDigestSpec(String dashboardPath, String dashboardTitle, List<Measure> measures, List<String> recipients) {
        this(dashboardPath, dashboardTitle, measures, recipients, false, true, Insight.DEFAULT_MAX_BULLETS);
    }

    DashboardDigestSpec(
            String dashboardPath,
            String dashboardTitle,
            List<Measure> measures,
            List<String> recipients,
            boolean insightEnabled,
            boolean insightNarrate,
            int insightMaxBullets) {
        this.dashboardPath = dashboardPath;
        this.dashboardTitle = dashboardTitle;
        this.measures = measures == null ? new ArrayList<>() : measures;
        this.recipients = recipients == null ? new ArrayList<>() : recipients;
        this.insightEnabled = insightEnabled;
        this.insightNarrate = insightNarrate;
        this.insightMaxBullets = insightMaxBullets;
    }

    /** The dashboard's JCR repository path (used to build the deep link), or null when unspecified. */
    public String getDashboardPath() {
        return dashboardPath;
    }

    /** The dashboard's display title for the email, or null. */
    public String getDashboardTitle() {
        return dashboardTitle;
    }

    /** The measures to summarize (never null; guaranteed non-empty by {@link #fromPayload}). */
    public List<Measure> getMeasures() {
        return measures;
    }

    /** The admin-supplied recipient references — may be empty (then self-email fallback). Never null. */
    public List<String> getRecipients() {
        return recipients;
    }

    /**
     * Whether the insight digest runs at all (saiku#1119). False — the #943 behaviour — whenever the
     * payload carries no {@code insight} block, so every job written before this feature keeps producing
     * exactly the measure table it always did.
     */
    public boolean isInsightEnabled() {
        return insightEnabled;
    }

    /**
     * Whether the bullets are LLM-narrated. When false the deterministic
     * {@link TemplateDigestNarrator} writes them. Narration also degrades to the template at runtime
     * (no provider, egress policy denies, transport error), so this only chooses the first attempt.
     */
    public boolean isInsightNarrate() {
        return insightNarrate;
    }

    /** The bullet cap for the insight digest. Always &ge; 1. */
    public int getInsightMaxBullets() {
        return insightMaxBullets;
    }

    /** Defaults + parsing for the optional {@code insight} block. */
    public static final class Insight {

        static final int DEFAULT_MAX_BULLETS = 3;
        /** A digest longer than this stops being a digest. */
        static final int MAX_MAX_BULLETS = 10;

        private final boolean narrate;
        private final int maxBullets;

        private Insight(boolean narrate, int maxBullets) {
            this.narrate = narrate;
            this.maxBullets = maxBullets;
        }

        /**
         * Parse the optional block. An absent (or null) block means the feature is OFF; an empty object
         * means ON with defaults.
         *
         * @return {@code null} when absent
         * @throws IllegalArgumentException on an unusable value (a non-boolean flag, a non-numeric or
         *     out-of-range cap) — the engine turns this into a recorded FAILED run, never a stack trace
         */
        static Insight fromPayload(Object raw) {
            if (raw == null) {
                return null;
            }
            if (!(raw instanceof Map<?, ?> m)) {
                throw new IllegalArgumentException("payload.insight must be an object");
            }
            boolean narrate = readBoolean(m.get("narrate"), true);
            int maxBullets = readMaxBullets(m.get("maxBullets"));
            return new Insight(narrate, maxBullets);
        }

        boolean narrate() {
            return narrate;
        }

        int maxBullets() {
            return maxBullets;
        }

        private static boolean readBoolean(Object v, boolean fallback) {
            if (v == null) {
                return fallback;
            }
            if (v instanceof Boolean b) {
                return b;
            }
            String s = PayloadParsing.readString(v);
            if (s == null) {
                return fallback;
            }
            if ("true".equalsIgnoreCase(s)) {
                return true;
            }
            if ("false".equalsIgnoreCase(s)) {
                return false;
            }
            throw new IllegalArgumentException("payload.insight.narrate must be true or false");
        }

        private static int readMaxBullets(Object v) {
            if (v == null) {
                return DEFAULT_MAX_BULLETS;
            }
            Double n = PayloadParsing.readDouble(v);
            if (n == null) {
                return DEFAULT_MAX_BULLETS;
            }
            int cap = n.intValue();
            if (cap < 1 || cap > MAX_MAX_BULLETS) {
                throw new IllegalArgumentException(
                        "payload.insight.maxBullets must be between 1 and " + MAX_MAX_BULLETS);
            }
            return cap;
        }
    }

    /**
     * Parse and validate the spec from a job payload. Throws {@link IllegalArgumentException} on any
     * missing/invalid field — the engine turns that into a recorded FAILED run (never a stack trace).
     *
     * @param payload the opaque job payload map; may be {@code null}
     */
    public static DashboardDigestSpec fromPayload(Map<String, Object> payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload is required for a DASHBOARD_DIGEST job");
        }

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
            PeriodSpec period = PeriodSpec.fromPayload(m.get("period"));
            measures.add(new Measure(cube, measure, label == null ? measure : label, filters, period));
        }

        List<String> recipients = readRecipients(payload.get("recipients"));
        Insight insight = Insight.fromPayload(payload.get("insight"));
        return new DashboardDigestSpec(
                dashboardPath,
                dashboardTitle,
                measures,
                recipients,
                insight != null,
                insight == null || insight.narrate(),
                insight == null ? Insight.DEFAULT_MAX_BULLETS : insight.maxBullets());
    }

    private static List<String> readRecipients(Object v) {
        List<String> out = new ArrayList<>();
        if (!(v instanceof List<?> list)) {
            return out;
        }
        List<String> seen = new ArrayList<>();
        for (Object o : list) {
            String s = PayloadParsing.readString(o);
            if (s == null) {
                continue;
            }
            String key = s.toLowerCase(Locale.ROOT);
            if (!seen.contains(key)) {
                seen.add(key);
                out.add(s);
            }
        }
        return out;
    }

    /** One measure to summarize: its cube ref, the measure name, a display label, and optional slicers. */
    public static final class Measure {

        private final AiCubeRef cube;
        private final String measure;
        private final String label;
        private final List<AiFilterSelection> filters;
        private final PeriodSpec period;

        Measure(AiCubeRef cube, String measure, String label, List<AiFilterSelection> filters, PeriodSpec period) {
            this.cube = cube;
            this.measure = measure;
            this.label = label;
            this.filters = filters == null ? new ArrayList<>() : filters;
            this.period = period;
        }

        public AiCubeRef getCube() {
            return cube;
        }

        public String getMeasure() {
            return measure;
        }

        /** The display label for the email row (defaults to the measure name). */
        public String getLabel() {
            return label;
        }

        public List<AiFilterSelection> getFilters() {
            return filters;
        }

        /**
         * The period-over-period slicer for this measure (saiku#1119), or null when the payload
         * declares none — in which case the measure contributes a current value only, exactly as before.
         */
        public PeriodSpec getPeriod() {
            return period;
        }
    }
}
