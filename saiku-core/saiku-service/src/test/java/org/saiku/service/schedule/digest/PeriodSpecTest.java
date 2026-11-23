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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.saiku.service.olap.ai.AiFilterSelection;

/** Parsing + slicer translation of the insight {@code period} block (saiku#1119). */
public class PeriodSpecTest {

    private static Map<String, Object> period(String dimension, String hierarchy, String level, String current) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dimension", dimension);
        m.put("hierarchy", hierarchy);
        m.put("level", level);
        if (current != null) {
            m.put("current", current);
        }
        return m;
    }

    @Test
    public void absentBlockIsNoPeriod() {
        assertNull(PeriodSpec.fromPayload(null));
    }

    @Test
    public void defaultsToQuarterToDate() {
        PeriodSpec p = PeriodSpec.fromPayload(period("Time", "Time", "Quarter", null));
        assertEquals("qtd", p.getCurrentValue());
        assertEquals("Time", p.getDimension());
        assertEquals("Quarter", p.getLevel());
    }

    @Test
    public void currentFilterIsRelativeAndPreviousIsThePriorMember() {
        PeriodSpec p = PeriodSpec.fromPayload(period("Time", "Time", "Quarter", "mtd"));
        AiFilterSelection current = p.currentFilter();
        assertEquals("relative", current.getOp());
        assertEquals("mtd", current.getValue());
        assertEquals("Time", current.getDimension());
        assertEquals("Time", current.getHierarchy());
        assertEquals("Quarter", current.getLevel());

        AiFilterSelection previous = p.previousFilter();
        assertEquals("relative", previous.getOp());
        assertEquals(PeriodSpec.PREVIOUS_PRESET, previous.getValue());
        // Same axis, same granularity — otherwise the pair is not comparable.
        assertEquals(current.getDimension(), previous.getDimension());
        assertEquals(current.getHierarchy(), previous.getHierarchy());
        assertEquals(current.getLevel(), previous.getLevel());
    }

    @Test
    public void caseIsNormalisedOnThePreset() {
        PeriodSpec p = PeriodSpec.fromPayload(period("Time", "Time", "Month", "YTD"));
        assertEquals("ytd", p.getCurrentValue());
    }

    @Test
    public void rejectsMissingAxis() {
        Map<String, Object> m = period("Time", "Time", null, null);
        try {
            PeriodSpec.fromPayload(m);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("period requires"));
        }
    }

    @Test
    public void rejectsPreviousPeriodAsTheCurrentSide() {
        try {
            PeriodSpec.fromPayload(period("Time", "Time", "Quarter", "previous_period"));
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("not supported"));
        }
    }

    @Test
    public void rejectsUnknownPreset() {
        try {
            PeriodSpec.fromPayload(period("Time", "Time", "Quarter", "last_n_decades"));
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("last_n_quarters"));
        }
    }

    @Test
    public void rejectsNonObjectBlock() {
        try {
            PeriodSpec.fromPayload("Time");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("must be an object"));
        }
    }

    @Test
    public void applyReplacesAFilterOnTheSameAxis() {
        PeriodSpec p = PeriodSpec.fromPayload(period("Time", "Time", "Quarter", "qtd"));
        AiFilterSelection timeSlice = new AiFilterSelection("Time", "Time", "Quarter", List.of("[Time].[Q1]"));
        AiFilterSelection storeSlice = new AiFilterSelection("Store", "Store", "Store Country", List.of("[USA]"));
        List<AiFilterSelection> filters = p.applyTo(List.of(timeSlice, storeSlice));
        assertEquals(2, filters.size());
        AiFilterSelection periodFilter = filters.get(1);
        assertEquals("relative", periodFilter.getOp());
        assertNotNull(periodFilter.getValue());
        // The unrelated slicer survives untouched.
        assertEquals("[USA]", filters.get(0).getMembers().get(0));
    }

    @Test
    public void applyOnAnEmptyListJustAddsThePeriod() {
        PeriodSpec p = PeriodSpec.fromPayload(period("Time", "Time", "Quarter", "qtd"));
        List<AiFilterSelection> filters = p.applyTo(null);
        assertEquals(1, filters.size());
        assertEquals("qtd", filters.get(0).getValue());
    }
}
