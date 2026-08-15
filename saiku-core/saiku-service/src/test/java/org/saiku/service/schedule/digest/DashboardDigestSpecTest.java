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
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** Payload parse + validation for a DASHBOARD_DIGEST job (saiku#943). */
public class DashboardDigestSpecTest {

    private static Map<String, Object> measure(String cube, String name, String label) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cube", cube);
        m.put("measure", name);
        if (label != null) {
            m.put("label", label);
        }
        return m;
    }

    private static Map<String, Object> validPayload() {
        Map<String, Object> dash = new LinkedHashMap<>();
        dash.put("path", "shared/exec.saikudash");
        dash.put("title", "Executive Overview");
        List<Object> measures = new ArrayList<>();
        measures.add(measure("conn/cat/schema/Sales", "Unit Sales", "Total Units"));
        measures.add(measure("conn/cat/schema/Sales", "Store Sales", null));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("dashboard", dash);
        p.put("measures", measures);
        p.put("recipients", List.of("ops@example.com", "OPS@example.com", "boss@example.com"));
        return p;
    }

    @Test
    public void parsesFullPayload() {
        DashboardDigestSpec spec = DashboardDigestSpec.fromPayload(validPayload());
        assertEquals("shared/exec.saikudash", spec.getDashboardPath());
        assertEquals("Executive Overview", spec.getDashboardTitle());
        assertEquals(2, spec.getMeasures().size());
        assertEquals("Unit Sales", spec.getMeasures().get(0).getMeasure());
        assertEquals("Total Units", spec.getMeasures().get(0).getLabel());
        assertEquals("Sales", spec.getMeasures().get(0).getCube().getCubeName());
        // Label defaults to the measure name when omitted.
        assertEquals("Store Sales", spec.getMeasures().get(1).getLabel());
    }

    @Test
    public void recipientsAreDeduplicatedCaseInsensitively() {
        DashboardDigestSpec spec = DashboardDigestSpec.fromPayload(validPayload());
        // ops@ and OPS@ collapse to one; boss@ stays.
        assertEquals(2, spec.getRecipients().size());
    }

    @Test
    public void absentRecipientsYieldsEmptyList() {
        Map<String, Object> p = validPayload();
        p.remove("recipients");
        DashboardDigestSpec spec = DashboardDigestSpec.fromPayload(p);
        assertTrue(spec.getRecipients().isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullPayloadRejected() {
        DashboardDigestSpec.fromPayload(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void missingMeasuresRejected() {
        Map<String, Object> p = validPayload();
        p.remove("measures");
        DashboardDigestSpec.fromPayload(p);
    }

    @Test(expected = IllegalArgumentException.class)
    public void emptyMeasuresRejected() {
        Map<String, Object> p = validPayload();
        p.put("measures", new ArrayList<>());
        DashboardDigestSpec.fromPayload(p);
    }

    @Test(expected = IllegalArgumentException.class)
    public void measureMissingCubeRejected() {
        Map<String, Object> p = validPayload();
        p.put("measures", List.of(measure(null, "Unit Sales", null)));
        DashboardDigestSpec.fromPayload(p);
    }

    @Test(expected = IllegalArgumentException.class)
    public void measureMissingNameRejected() {
        Map<String, Object> p = validPayload();
        p.put("measures", List.of(measure("conn/cat/schema/Sales", "  ", null)));
        DashboardDigestSpec.fromPayload(p);
    }

    @Test
    public void dashboardMayBeAbsent() {
        Map<String, Object> p = validPayload();
        p.remove("dashboard");
        DashboardDigestSpec spec = DashboardDigestSpec.fromPayload(p);
        // No dashboard path => a link cannot be built later; the digest still summarizes measures.
        assertEquals(null, spec.getDashboardPath());
        assertEquals(2, spec.getMeasures().size());
    }
}
