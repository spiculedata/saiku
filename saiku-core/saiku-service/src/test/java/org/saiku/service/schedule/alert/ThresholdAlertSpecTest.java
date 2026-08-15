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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** Parsing + validation of {@link ThresholdAlertSpec} and {@link AlertChannelConfig} (saiku#1098). */
public class ThresholdAlertSpecTest {

    private static Map<String, Object> base() {
        Map<String, Object> channel = new HashMap<>();
        channel.put("type", "WEBHOOK");
        channel.put("url", "https://93.184.216.34/x");
        Map<String, Object> p = new HashMap<>();
        p.put("cube", "conn/cat/schema/Sales");
        p.put("measure", "Unit Sales");
        p.put("comparison", "GT");
        p.put("threshold", 100);
        p.put("channel", channel);
        return p;
    }

    @Test
    public void parsesFullSpecWithStringCube() {
        ThresholdAlertSpec spec = ThresholdAlertSpec.fromPayload(base());
        assertEquals("conn", spec.getCube().getConnectionName());
        assertEquals("Sales", spec.getCube().getCubeName());
        assertEquals("Unit Sales", spec.getMeasure());
        assertEquals(AlertComparison.GT, spec.getComparison());
        assertEquals(100.0, spec.getThreshold(), 0.0001);
        assertEquals(0L, spec.getCooldownMillis());
        assertEquals(AlertChannelConfig.Type.WEBHOOK, spec.getChannel().getType());
    }

    @Test
    public void parsesCubeObject() {
        Map<String, Object> p = base();
        Map<String, Object> cube = new HashMap<>();
        cube.put("connectionName", "c");
        cube.put("catalog", "cat");
        cube.put("schema", "s");
        cube.put("cubeName", "Sales");
        p.put("cube", cube);
        ThresholdAlertSpec spec = ThresholdAlertSpec.fromPayload(p);
        assertEquals("c", spec.getCube().getConnectionName());
        assertEquals("Sales", spec.getCube().getCubeName());
    }

    @Test
    public void parsesCooldownAndFilters() {
        Map<String, Object> p = base();
        p.put("cooldownMillis", 60000);
        Map<String, Object> f = new HashMap<>();
        f.put("dimension", "Store");
        f.put("level", "Country");
        f.put("members", List.of("[Store].[USA]"));
        p.put("filters", List.of(f));
        ThresholdAlertSpec spec = ThresholdAlertSpec.fromPayload(p);
        assertEquals(60000L, spec.getCooldownMillis());
        assertEquals(1, spec.getFilters().size());
        assertEquals("Store", spec.getFilters().get(0).getDimension());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNullPayload() {
        ThresholdAlertSpec.fromPayload(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingMeasure() {
        Map<String, Object> p = base();
        p.remove("measure");
        ThresholdAlertSpec.fromPayload(p);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingThreshold() {
        Map<String, Object> p = base();
        p.remove("threshold");
        ThresholdAlertSpec.fromPayload(p);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsBadCube() {
        Map<String, Object> p = base();
        p.put("cube", "only/three/parts");
        ThresholdAlertSpec.fromPayload(p);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNegativeCooldown() {
        Map<String, Object> p = base();
        p.put("cooldownMillis", -1);
        ThresholdAlertSpec.fromPayload(p);
    }

    @Test(expected = IllegalArgumentException.class)
    public void webhookChannelRejectsSsrfUrl() {
        Map<String, Object> p = base();
        Map<String, Object> channel = new HashMap<>();
        channel.put("type", "WEBHOOK");
        channel.put("url", "https://127.0.0.1/hook");
        p.put("channel", channel);
        ThresholdAlertSpec.fromPayload(p);
    }

    @Test(expected = IllegalArgumentException.class)
    public void webhookChannelRequiresUrl() {
        Map<String, Object> p = base();
        Map<String, Object> channel = new HashMap<>();
        channel.put("type", "WEBHOOK");
        p.put("channel", channel);
        ThresholdAlertSpec.fromPayload(p);
    }

    @Test
    public void emailSelfChannelNeedsNoUrl() {
        Map<String, Object> p = base();
        Map<String, Object> channel = new HashMap<>();
        channel.put("type", "EMAIL_SELF");
        p.put("channel", channel);
        ThresholdAlertSpec spec = ThresholdAlertSpec.fromPayload(p);
        assertEquals(AlertChannelConfig.Type.EMAIL_SELF, spec.getChannel().getType());
        assertNull(spec.getChannel().getWebhookUrl());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownChannelType() {
        Map<String, Object> p = base();
        Map<String, Object> channel = new HashMap<>();
        channel.put("type", "SLACK");
        p.put("channel", channel);
        ThresholdAlertSpec.fromPayload(p);
    }
}
