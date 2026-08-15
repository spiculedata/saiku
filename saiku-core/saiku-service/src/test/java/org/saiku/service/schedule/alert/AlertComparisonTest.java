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

import static org.junit.Assert.*;

import org.junit.Test;

/** Unit tests for the per-comparison breach logic in {@link AlertComparison} (saiku#1098). */
public class AlertComparisonTest {

    @Test
    public void gt() {
        assertTrue(AlertComparison.GT.breached(101, null, 100));
        assertFalse(AlertComparison.GT.breached(100, null, 100)); // strictly greater
        assertFalse(AlertComparison.GT.breached(99, null, 100));
    }

    @Test
    public void lt() {
        assertTrue(AlertComparison.LT.breached(99, null, 100));
        assertFalse(AlertComparison.LT.breached(100, null, 100));
        assertFalse(AlertComparison.LT.breached(101, null, 100));
    }

    @Test
    public void absChange() {
        assertFalse("first run never breaches", AlertComparison.ABS_CHANGE.breached(1000, null, 25));
        assertTrue(AlertComparison.ABS_CHANGE.breached(130, 100.0, 25)); // +30
        assertTrue(AlertComparison.ABS_CHANGE.breached(70, 100.0, 25)); // -30
        assertFalse(AlertComparison.ABS_CHANGE.breached(110, 100.0, 25)); // +10
        assertTrue("boundary is >=", AlertComparison.ABS_CHANGE.breached(125, 100.0, 25));
    }

    @Test
    public void pctChange() {
        assertFalse("first run never breaches", AlertComparison.PCT_CHANGE.breached(1000, null, 10));
        assertTrue(AlertComparison.PCT_CHANGE.breached(120, 100.0, 10)); // +20%
        assertTrue(AlertComparison.PCT_CHANGE.breached(80, 100.0, 10)); // -20%
        assertFalse(AlertComparison.PCT_CHANGE.breached(105, 100.0, 10)); // +5%
        assertTrue("boundary is >=", AlertComparison.PCT_CHANGE.breached(110, 100.0, 10));
    }

    @Test
    public void pctChange_zeroPreviousNeverBreaches() {
        assertFalse(AlertComparison.PCT_CHANGE.breached(50, 0.0, 10));
    }

    @Test
    public void parseIsCaseInsensitive() {
        assertEquals(AlertComparison.GT, AlertComparison.parse("gt"));
        assertEquals(AlertComparison.PCT_CHANGE, AlertComparison.parse("Pct_Change"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void parseRejectsUnknown() {
        AlertComparison.parse("NEQ");
    }

    @Test(expected = IllegalArgumentException.class)
    public void parseRejectsNull() {
        AlertComparison.parse(null);
    }

    @Test
    public void needsPrevious() {
        assertTrue(AlertComparison.ABS_CHANGE.needsPrevious());
        assertTrue(AlertComparison.PCT_CHANGE.needsPrevious());
        assertFalse(AlertComparison.GT.needsPrevious());
        assertFalse(AlertComparison.LT.needsPrevious());
    }
}
