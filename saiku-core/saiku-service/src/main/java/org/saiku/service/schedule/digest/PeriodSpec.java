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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.saiku.service.olap.ai.AiFilterSelection;
import org.saiku.service.schedule.alert.PayloadParsing;

/**
 * The optional period-over-period <b>slicer</b> of one digest measure (saiku#1119): which time axis the
 * measure is compared along, and which relative preset is the "current" period. The "previous" period is
 * always the {@value #PREVIOUS_PRESET} preset on the SAME level, so the pair is guaranteed
 * like-for-like — same axis, same granularity, one step back.
 *
 * <p>Both sides are expressed as ordinary {@link AiFilterSelection}s with {@code op = "relative"}, which
 * the typed AI Query converter already translates to MDX ({@code Qtd()} for the current period,
 * {@code Tail(level.Members, 2).Item(0)} for the previous one). There is no hand-written MDX here, and
 * no date arithmetic: the period is defined by the cube's own member order, so it works for any
 * hierarchical time dimension without the digest knowing anything about the data.
 *
 * <p>Payload shape (per {@code payload.measures[]} entry, optional):
 *
 * <pre>{@code
 * "period": {
 *   "dimension": "Time",          // required
 *   "hierarchy": "Time",          // required
 *   "level":     "Quarter",       // required
 *   "current":   "qtd"            // optional; default "qtd"
 * }
 * }</pre>
 *
 * <p>{@code current} accepts the set-valued relative presets ({@code last_n_days|last_n_months|
 * last_n_quarters|last_n_years|ytd|mtd|qtd}). {@value #PREVIOUS_PRESET} is rejected there on purpose:
 * the "current" side must not be the same member the previous-period side resolves to, otherwise the
 * delta is always zero and the digest is silently useless.
 */
public final class PeriodSpec {

    /** The one preset the "previous" side always uses — the member immediately before the current one. */
    public static final String PREVIOUS_PRESET = "previous_period";

    /** Default "current" preset: the quarter to date. Digest cadence and this default agree. */
    public static final String DEFAULT_CURRENT_PRESET = "qtd";

    /**
     * The set-valued relative presets an admin may name as the "current" period. {@link
     * #PREVIOUS_PRESET} is deliberately absent — see the class javadoc.
     */
    private static final Set<String> CURRENT_PRESETS =
            Set.of("last_n_days", "last_n_months", "last_n_quarters", "last_n_years", "ytd", "mtd", "qtd");

    private final String dimension;
    private final String hierarchy;
    private final String level;
    private final String currentValue;

    PeriodSpec(String dimension, String hierarchy, String level, String currentValue) {
        this.dimension = dimension;
        this.hierarchy = hierarchy;
        this.level = level;
        this.currentValue = currentValue;
    }

    public String getDimension() {
        return dimension;
    }

    public String getHierarchy() {
        return hierarchy;
    }

    public String getLevel() {
        return level;
    }

    /** The relative preset naming the "current" period (default {@value #DEFAULT_CURRENT_PRESET}). */
    public String getCurrentValue() {
        return currentValue;
    }

    /** The {@code relative} slicer that scopes a query to the current period. */
    public AiFilterSelection currentFilter() {
        return filter(currentValue);
    }

    /** The {@code relative} slicer that scopes a query to the immediately-preceding period. */
    public AiFilterSelection previousFilter() {
        return filter(PREVIOUS_PRESET);
    }

    private AiFilterSelection filter(String value) {
        AiFilterSelection f = new AiFilterSelection();
        f.setDimension(dimension);
        f.setHierarchy(hierarchy);
        f.setLevel(level);
        f.setOp("relative");
        f.setValue(value);
        return f;
    }

    /**
     * Swap a period slicer into a measure's filter list, replacing any filter already on the same
     * hierarchy.
     *
     * <p>The replacement is not cosmetic: the AI Query converter REJECTS two filters targeting one
     * hierarchy (Mondrian's WHERE clause is a tuple, and a tuple may not carry two members of the same
     * hierarchy — saiku#784). An admin who wrote a fixed-member slicer on {@code Time.Quarter} and then
     * added a {@code period} block would otherwise get a hard query failure instead of a delta.
     *
     * @param filters the measure's static slicers (may be null)
     * @return a new list — never mutates the caller's list or its elements
     */
    public List<AiFilterSelection> applyTo(List<AiFilterSelection> filters) {
        List<AiFilterSelection> out = new java.util.ArrayList<>();
        if (filters != null) {
            for (AiFilterSelection f : filters) {
                if (f == null) {
                    continue;
                }
                if (hierarchy.equals(f.getHierarchy()) || hierarchy.equals(f.getDimension())) {
                    continue; // superseded by the period slicer on this axis
                }
                out.add(f);
            }
        }
        out.add(currentFilter());
        return out;
    }

    /**
     * Parse the optional {@code period} block. Returns {@code null} when absent (a measure with no
     * period contributes a current value only — the #943 behaviour).
     *
     * @throws IllegalArgumentException on a missing axis or an unsupported preset
     */
    static PeriodSpec fromPayload(Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("payload.measures[].period must be an object");
        }
        String dimension = PayloadParsing.readString(m.get("dimension"));
        String hierarchy = PayloadParsing.readString(m.get("hierarchy"));
        String level = PayloadParsing.readString(m.get("level"));
        if (dimension == null || hierarchy == null || level == null) {
            throw new IllegalArgumentException("payload.measures[].period requires dimension, hierarchy and level");
        }
        String current = PayloadParsing.readString(m.get("current"));
        String currentValue = current == null ? DEFAULT_CURRENT_PRESET : current.toLowerCase(Locale.ROOT);
        if (!CURRENT_PRESETS.contains(currentValue)) {
            throw new IllegalArgumentException("payload.measures[].period.current '" + current
                    + "' is not supported — expected one of " + CURRENT_PRESETS
                    + " (the previous side is always '" + PREVIOUS_PRESET + "')");
        }
        return new PeriodSpec(dimension, hierarchy, level, currentValue);
    }
}
