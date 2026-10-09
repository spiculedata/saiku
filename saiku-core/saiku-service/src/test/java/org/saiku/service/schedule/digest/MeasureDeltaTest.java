/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schedule.digest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** The period-over-period arithmetic behind every digest bullet (saiku#1119). */
public class MeasureDeltaTest {

    private static MeasureDelta delta(String label, double current, double previous) {
        return new MeasureDelta(null, label, current, previous);
    }

    @Test
    public void absoluteChangeIsCurrentMinusPrevious() {
        assertEquals(200d, delta("Sales", 1200d, 1000d).absoluteChange(), 1e-9);
        assertEquals(-200d, delta("Sales", 800d, 1000d).absoluteChange(), 1e-9);
        assertEquals(0d, delta("Sales", 1000d, 1000d).absoluteChange(), 1e-9);
    }

    @Test
    public void percentChangeIsRelativeToTheBaseline() {
        assertEquals(0.2d, delta("Sales", 1200d, 1000d).percentChange(), 1e-9);
        assertEquals(-0.2d, delta("Sales", 800d, 1000d).percentChange(), 1e-9);
    }

    @Test
    public void percentChangeFromANegativeBaselineUsesTheMagnitude() {
        // A loss widening from -1000 to -1500 is a 50% move expressed as -50%: the percentage carries
        // the direction, and dividing by the baseline's magnitude (not its signed value) keeps the
        // magnitude of the move at 0.5 rather than 0.5 with a doubled sign.
        assertEquals(-0.5d, delta("Margin", -1500d, -1000d).percentChange(), 1e-9);
        assertEquals(
                MeasureDelta.Direction.DOWN, delta("Margin", -1500d, -1000d).direction());
    }

    @Test
    public void percentChangeFromZeroIsUndefinedNotInfinite() {
        assertNull(delta("New Metric", 500d, 0d).percentChange());
        assertEquals("", delta("New Metric", 500d, 0d).formattedPercent());
    }

    @Test
    public void direction() {
        assertEquals(MeasureDelta.Direction.UP, delta("Sales", 1200d, 1000d).direction());
        assertEquals(MeasureDelta.Direction.DOWN, delta("Sales", 800d, 1000d).direction());
        assertEquals(MeasureDelta.Direction.FLAT, delta("Sales", 1000d, 1000d).direction());
    }

    @Test
    public void formatsGroupedAndSigned() {
        MeasureDelta d = delta("Sales", 1200d, 1000d);
        assertEquals("1,200", d.formattedCurrent());
        assertEquals("1,000", d.formattedPrevious());
        assertEquals("+200", d.formattedChange());
        assertEquals("+20.0%", d.formattedPercent());
    }

    @Test
    public void formatsADropWithAMinusSign() {
        MeasureDelta d = delta("Sales", 800d, 1000d);
        assertEquals("-200", d.formattedChange());
        assertEquals("-20.0%", d.formattedPercent());
    }

    @Test
    public void aNullLabelRendersAsEmptyRatherThanNull() {
        assertEquals("", delta(null, 1d, 1d).label());
    }
}
