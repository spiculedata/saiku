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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiFilterSelection;
import org.saiku.service.schedule.JobRunner;
import org.saiku.service.schedule.JobSchedule;
import org.saiku.service.schedule.JobScheduler;
import org.saiku.service.schedule.JobStatus;
import org.saiku.service.schedule.JobStore;
import org.saiku.service.schedule.ScheduledJobFile;

/**
 * A {@code THRESHOLD_ALERT} job registered on the {@link JobScheduler} routes to the {@link
 * ThresholdAlertJobHandler} and the fire + baseline bookkeeping survives a store round-trip
 * (saiku#1098). Uses the in-memory {@link JobStore} and drives {@code runNow} synchronously.
 */
public class ThresholdAlertDispatchTest {

    private JobScheduler scheduler;

    @After
    public void tearDown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    private static final class CapturingChannel implements AlertChannel {
        int deliveries = 0;

        @Override
        public void deliver(AlertEvent event, AlertChannelConfig config) {
            deliveries++;
        }
    }

    private static Map<String, Object> payload() {
        Map<String, Object> channel = new LinkedHashMap<>();
        channel.put("type", "WEBHOOK");
        channel.put("url", "https://93.184.216.34/alert");
        Map<String, Object> p = new HashMap<>();
        p.put("cube", "conn/cat/schema/Sales");
        p.put("measure", "Unit Sales");
        p.put("comparison", "GT");
        p.put("threshold", 100);
        p.put("channel", channel);
        return p;
    }

    @Test(timeout = 5000)
    public void thresholdAlertJobRoutesToHandlerAndFires() {
        JobStore store = new JobStore((String) null);
        scheduler = new JobScheduler(store, null, JobRunner.DIRECT);

        CapturingChannel webhook = new CapturingChannel();
        MeasureValueReader reader = (AiCubeRef cube, String measure, List<AiFilterSelection> filters) -> {
            assertEquals("Unit Sales", measure);
            assertEquals("Sales", cube.getCubeName());
            return 150.0; // breaches GT 100
        };
        ThresholdAlertJobHandler handler = new ThresholdAlertJobHandler(reader, webhook, null);
        scheduler.registerHandler("THRESHOLD_ALERT", handler);

        ScheduledJobFile job = store.create(
                "alice",
                List.of("ROLE_USER"),
                new JobSchedule(JobSchedule.Kind.FIXED_INTERVAL, 60_000L, "UTC"),
                "THRESHOLD_ALERT",
                payload(),
                true);

        ScheduledJobFile after = scheduler.runNow(job.getId());

        assertEquals("routed + fired", 1, webhook.deliveries);
        assertEquals(JobStatus.OK, after.getLastStatus());
        // Baseline persisted through the store round-trip.
        assertNotNull(after.getPayload().get(ThresholdAlertSpec.KEY_LAST_VALUE));
        assertEquals(150.0, ((Number) after.getPayload().get(ThresholdAlertSpec.KEY_LAST_VALUE)).doubleValue(), 0.0001);
    }

    @Test(timeout = 5000)
    public void malformedThresholdAlertRecordsFailed() {
        JobStore store = new JobStore((String) null);
        scheduler = new JobScheduler(store, null, JobRunner.DIRECT);
        ThresholdAlertJobHandler handler = new ThresholdAlertJobHandler((c, m, f) -> 1.0, new CapturingChannel(), null);
        scheduler.registerHandler("THRESHOLD_ALERT", handler);

        ScheduledJobFile job = store.create(
                "alice",
                List.of("ROLE_USER"),
                new JobSchedule(JobSchedule.Kind.FIXED_INTERVAL, 60_000L, "UTC"),
                "THRESHOLD_ALERT",
                new HashMap<>(), // empty → invalid spec
                true);

        ScheduledJobFile after = scheduler.runNow(job.getId());
        assertEquals(JobStatus.FAILED, after.getLastStatus());
        assertNotNull(after.getLastError());
    }
}
