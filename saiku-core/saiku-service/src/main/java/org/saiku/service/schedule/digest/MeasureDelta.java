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
import org.saiku.service.olap.ai.AiCubeRef;

/**
 * One measure's period-over-period result: what it reads now, what the same measure read over the
 * previous period, and the delta between them (saiku#1119).
 *
 * <p>Both values come from the SAME typed AI Query read (the {@link
 * org.saiku.service.schedule.alert.MeasureValueReader} seam) under the same owner identity, with the
 * only difference being which relative period slicer is in the WHERE clause. That is what makes the
 * pair comparable: identical cube, identical filters, identical RLS.
 *
 * <p>The {@link #cube()} each value came from is carried along so the narration layer can ground itself
 * in the live schema (and so a digest spanning two cubes is never described in terms of the wrong one).
 *
 * <p>All arithmetic is <b>fail-safe by construction</b>: a measure whose previous value is 0 has no
 * meaningful percentage change, so {@link #percentChange()} is {@code null} rather than
 * {@code Infinity}/{@code NaN} — which is exactly the string that would otherwise reach an email body.
 * Callers render {@code null} as "no prior baseline".
 */
public record MeasureDelta(AiCubeRef cube, String label, double current, double previous) {

    /**
     * Signed values are formatted by hand rather than with a multi-section {@link DecimalFormat}
     * pattern: the reserved {@code '+'} / {@code ';'} characters make such a pattern brittle (and it
     * throws at construction time if quoted incorrectly), and a digest is not worth that footgun.
     */
    private static final ThreadLocal<DecimalFormat> VALUE_FORMAT =
            ThreadLocal.withInitial(() -> new DecimalFormat("#,##0.####"));

    /** The cube this delta was read from; null only in synthetic (test) deltas. */
    @Override
    public AiCubeRef cube() {
        return cube;
    }

    /** The display label from the spec (defaults to the measure name). Never null in practice. */
    @Override
    public String label() {
        return label == null ? "" : label;
    }

    /** Current minus previous. */
    public double absoluteChange() {
        return current - previous;
    }

    /**
     * Relative change as a fraction (0.12 = +12%), or {@code null} when the previous value is 0 — a
     * percentage change from zero is undefined, not infinite.
     */
    public Double percentChange() {
        if (previous == 0d) {
            return null;
        }
        return (current - previous) / Math.abs(previous);
    }

    /** Which way the measure moved. Compares exactly (no epsilon): these are aggregate OLAP values. */
    public Direction direction() {
        double delta = absoluteChange();
        if (delta > 0) {
            return Direction.UP;
        }
        if (delta < 0) {
            return Direction.DOWN;
        }
        return Direction.FLAT;
    }

    public enum Direction {
        UP,
        DOWN,
        FLAT
    }

    /** The current value, grouped. */
    public String formattedCurrent() {
        return VALUE_FORMAT.get().format(current);
    }

    /** The previous-period value, grouped. */
    public String formattedPrevious() {
        return VALUE_FORMAT.get().format(previous);
    }

    /** The absolute change, signed ({@code +120} / {@code -80} / {@code 0}). */
    public String formattedChange() {
        double delta = absoluteChange();
        String sign = delta > 0 ? "+" : (delta < 0 ? "-" : "");
        return sign + VALUE_FORMAT.get().format(Math.abs(delta));
    }

    /** The relative change, signed and to one decimal; empty when there is no prior baseline. */
    public String formattedPercent() {
        Double pct = percentChange();
        return pct == null ? "" : String.format(java.util.Locale.ROOT, "%+.1f%%", pct * 100d);
    }
}
