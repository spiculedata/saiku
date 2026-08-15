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

/**
 * How a threshold alert decides a measure value is a "breach" (saiku#1098).
 *
 * <ul>
 *   <li>{@link #GT} — breach when {@code current > threshold}.</li>
 *   <li>{@link #LT} — breach when {@code current < threshold}.</li>
 *   <li>{@link #ABS_CHANGE} — breach when {@code |current - previous| >= threshold}. Needs a prior
 *       observation; the FIRST run only records a baseline (never breaches).</li>
 *   <li>{@link #PCT_CHANGE} — breach when {@code |(current - previous) / previous| * 100 >= threshold}
 *       (threshold expressed in percent). Needs a prior non-zero observation; the FIRST run only
 *       records a baseline (never breaches). A previous value of exactly 0 can't yield a percentage,
 *       so it never breaches and simply re-baselines.</li>
 * </ul>
 *
 * <p>The two change comparisons compare against the value observed on the PREVIOUS run of this same
 * job (tracked in the job's own payload by {@link ThresholdAlertJobHandler}), not against a fixed
 * baseline — so an alert fires on movement, not on an absolute level.
 */
public enum AlertComparison {
    GT,
    LT,
    ABS_CHANGE,
    PCT_CHANGE;

    /** True when this comparison needs the previous observation to decide a breach. */
    public boolean needsPrevious() {
        return this == ABS_CHANGE || this == PCT_CHANGE;
    }

    /** Parse case-insensitively; null / unknown throws {@link IllegalArgumentException}. */
    public static AlertComparison parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("comparison is required (one of GT, LT, ABS_CHANGE, PCT_CHANGE)");
        }
        try {
            return AlertComparison.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "unknown comparison '" + raw + "' — expected one of GT, LT, ABS_CHANGE, PCT_CHANGE");
        }
    }

    /**
     * Decide whether {@code current} breaches {@code threshold} for this comparison. For the change
     * comparisons, {@code previous} is the value from the prior run ({@code null} on the first run).
     *
     * @return true when the alert should fire
     */
    public boolean breached(double current, Double previous, double threshold) {
        switch (this) {
            case GT:
                return current > threshold;
            case LT:
                return current < threshold;
            case ABS_CHANGE:
                if (previous == null) {
                    return false; // first run — baseline only
                }
                return Math.abs(current - previous) >= threshold;
            case PCT_CHANGE:
                if (previous == null || previous == 0.0d) {
                    return false; // first run, or no percentage possible from a zero base
                }
                return Math.abs((current - previous) / previous) * 100.0d >= threshold;
            default:
                return false;
        }
    }
}
