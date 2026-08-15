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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiFilterSelection;
import org.saiku.service.schedule.ScheduledJobFile;

/**
 * Unit tests for {@link ThresholdAlertJobHandler} (saiku#1098) — breach/no-breach, each comparison,
 * cooldown suppression, channel selection, owner-context (no re-impersonation), and baseline tracking.
 * No live cube / mail / http: the value reader is an in-memory fake and the channels are capturing
 * stubs.
 */
public class ThresholdAlertJobHandlerTest {

    /** A clock whose "now" the test advances explicitly. */
    private static final class MutableClock extends Clock {
        private long millis;

        MutableClock(long start) {
            this.millis = start;
        }

        void advance(long d) {
            millis += d;
        }

        @Override
        public long millis() {
            return millis;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId z) {
            return this;
        }
    }

    /** A fake reader returning a fixed value (or a sequence), recording the last args it saw. */
    private static final class FakeReader implements MeasureValueReader {
        final List<Double> sequence = new ArrayList<>();
        int idx = 0;
        AiCubeRef lastCube;
        String lastMeasure;
        List<AiFilterSelection> lastFilters;

        FakeReader(double... values) {
            for (double v : values) {
                sequence.add(v);
            }
        }

        @Override
        public double readMeasure(AiCubeRef cube, String measure, List<AiFilterSelection> filters) {
            this.lastCube = cube;
            this.lastMeasure = measure;
            this.lastFilters = filters;
            double v = sequence.get(Math.min(idx, sequence.size() - 1));
            idx++;
            return v;
        }
    }

    /** A channel stub that captures the event it was asked to deliver. */
    private static final class CapturingChannel implements AlertChannel {
        int deliveries = 0;
        AlertEvent lastEvent;
        AlertChannelConfig lastConfig;
        Exception toThrow;

        @Override
        public void deliver(AlertEvent event, AlertChannelConfig config) throws Exception {
            if (toThrow != null) {
                throw toThrow;
            }
            deliveries++;
            lastEvent = event;
            lastConfig = config;
        }
    }

    private static Map<String, Object> webhookPayload(String comparison, double threshold) {
        return webhookPayload(comparison, threshold, 0L);
    }

    private static Map<String, Object> webhookPayload(String comparison, double threshold, long cooldownMillis) {
        Map<String, Object> channel = new LinkedHashMap<>();
        channel.put("type", "WEBHOOK");
        channel.put("url", "https://93.184.216.34/alert");
        Map<String, Object> p = new HashMap<>();
        p.put("cube", "conn/cat/schema/Sales");
        p.put("measure", "Unit Sales");
        p.put("comparison", comparison);
        p.put("threshold", threshold);
        p.put("cooldownMillis", cooldownMillis);
        p.put("channel", channel);
        return p;
    }

    private static ScheduledJobFile jobWith(Map<String, Object> payload) {
        ScheduledJobFile j = new ScheduledJobFile();
        j.setId("AAAAAAAAAAAAAAAA");
        j.setType("THRESHOLD_ALERT");
        j.setOwnerUsername("alice");
        j.setPayload(payload);
        return j;
    }

    private static ThresholdAlertJobHandler handler(
            MeasureValueReader reader, CapturingChannel webhook, CapturingChannel email, Clock clock) {
        return new ThresholdAlertJobHandler(reader, webhook, email, clock);
    }

    // ---------------- breach / no-breach ----------------

    @Test
    public void gt_firesOnBreach() throws Exception {
        FakeReader reader = new FakeReader(150.0);
        CapturingChannel webhook = new CapturingChannel();
        ScheduledJobFile job = jobWith(webhookPayload("GT", 100.0));

        handler(reader, webhook, null, new MutableClock(1000L)).handle(job);

        assertEquals(1, webhook.deliveries);
        assertEquals(150.0, webhook.lastEvent.value(), 0.0001);
        assertEquals(100.0, webhook.lastEvent.threshold(), 0.0001);
        assertEquals(AlertComparison.GT, webhook.lastEvent.comparison());
        // Baseline + fire time recorded on the payload.
        assertEquals(150.0, ((Number) job.getPayload().get(ThresholdAlertSpec.KEY_LAST_VALUE)).doubleValue(), 0.0001);
        assertEquals(1000L, ((Number) job.getPayload().get(ThresholdAlertSpec.KEY_LAST_FIRED_AT)).longValue());
    }

    @Test
    public void gt_doesNotFireWhenNotBreached() throws Exception {
        FakeReader reader = new FakeReader(50.0);
        CapturingChannel webhook = new CapturingChannel();
        ScheduledJobFile job = jobWith(webhookPayload("GT", 100.0));

        handler(reader, webhook, null, new MutableClock(1000L)).handle(job);

        assertEquals(0, webhook.deliveries);
        // Baseline still recorded, but no fire time.
        assertEquals(50.0, ((Number) job.getPayload().get(ThresholdAlertSpec.KEY_LAST_VALUE)).doubleValue(), 0.0001);
        assertNull(job.getPayload().get(ThresholdAlertSpec.KEY_LAST_FIRED_AT));
    }

    @Test
    public void lt_firesWhenBelow() throws Exception {
        FakeReader reader = new FakeReader(20.0);
        CapturingChannel webhook = new CapturingChannel();
        handler(reader, webhook, null, new MutableClock(1L)).handle(jobWith(webhookPayload("LT", 100.0)));
        assertEquals(1, webhook.deliveries);
    }

    @Test
    public void lt_doesNotFireWhenAbove() throws Exception {
        FakeReader reader = new FakeReader(200.0);
        CapturingChannel webhook = new CapturingChannel();
        handler(reader, webhook, null, new MutableClock(1L)).handle(jobWith(webhookPayload("LT", 100.0)));
        assertEquals(0, webhook.deliveries);
    }

    // ---------------- change comparisons: first run is baseline-only ----------------

    @Test
    public void absChange_firstRunIsBaselineOnly_thenFiresOnBigMove() throws Exception {
        FakeReader reader = new FakeReader(100.0, 130.0); // move of 30
        CapturingChannel webhook = new CapturingChannel();
        MutableClock clock = new MutableClock(1000L);
        ThresholdAlertJobHandler h = handler(reader, webhook, null, clock);
        ScheduledJobFile job = jobWith(webhookPayload("ABS_CHANGE", 25.0));

        h.handle(job); // baseline 100, no previous → no fire
        assertEquals(0, webhook.deliveries);

        clock.advance(1000L);
        h.handle(job); // 130 vs 100 = 30 >= 25 → fire
        assertEquals(1, webhook.deliveries);
        assertEquals(100.0, webhook.lastEvent.previousValue(), 0.0001);
        assertEquals(130.0, webhook.lastEvent.value(), 0.0001);
    }

    @Test
    public void absChange_smallMoveDoesNotFire() throws Exception {
        FakeReader reader = new FakeReader(100.0, 110.0); // move of 10 < 25
        CapturingChannel webhook = new CapturingChannel();
        ThresholdAlertJobHandler h = handler(reader, webhook, null, new MutableClock(1L));
        ScheduledJobFile job = jobWith(webhookPayload("ABS_CHANGE", 25.0));
        h.handle(job);
        h.handle(job);
        assertEquals(0, webhook.deliveries);
    }

    @Test
    public void pctChange_firesOnPercentMove() throws Exception {
        FakeReader reader = new FakeReader(100.0, 120.0); // +20%
        CapturingChannel webhook = new CapturingChannel();
        ThresholdAlertJobHandler h = handler(reader, webhook, null, new MutableClock(1L));
        ScheduledJobFile job = jobWith(webhookPayload("PCT_CHANGE", 10.0)); // threshold 10%
        h.handle(job); // baseline
        h.handle(job); // +20% >= 10% → fire
        assertEquals(1, webhook.deliveries);
    }

    @Test
    public void pctChange_belowThresholdDoesNotFire() throws Exception {
        FakeReader reader = new FakeReader(100.0, 105.0); // +5%
        CapturingChannel webhook = new CapturingChannel();
        ThresholdAlertJobHandler h = handler(reader, webhook, null, new MutableClock(1L));
        ScheduledJobFile job = jobWith(webhookPayload("PCT_CHANGE", 10.0));
        h.handle(job);
        h.handle(job);
        assertEquals(0, webhook.deliveries);
    }

    // ---------------- cooldown ----------------

    @Test
    public void cooldown_suppressesRefireWithinWindow() throws Exception {
        FakeReader reader = new FakeReader(150.0); // always breaches GT 100
        CapturingChannel webhook = new CapturingChannel();
        MutableClock clock = new MutableClock(1000L);
        ThresholdAlertJobHandler h = handler(reader, webhook, null, clock);
        ScheduledJobFile job = jobWith(webhookPayload("GT", 100.0, 60_000L));

        h.handle(job); // fires at t=1000
        assertEquals(1, webhook.deliveries);

        clock.advance(30_000L); // still within 60s cooldown
        h.handle(job);
        assertEquals("suppressed within cooldown", 1, webhook.deliveries);

        clock.advance(31_000L); // now 61s past the fire → cooldown elapsed
        h.handle(job);
        assertEquals("fires again after cooldown", 2, webhook.deliveries);
    }

    @Test
    public void noCooldown_firesEveryBreach() throws Exception {
        FakeReader reader = new FakeReader(150.0);
        CapturingChannel webhook = new CapturingChannel();
        MutableClock clock = new MutableClock(1000L);
        ThresholdAlertJobHandler h = handler(reader, webhook, null, clock);
        ScheduledJobFile job = jobWith(webhookPayload("GT", 100.0, 0L));
        h.handle(job);
        clock.advance(1L);
        h.handle(job);
        assertEquals(2, webhook.deliveries);
    }

    @Test
    public void failedDeliveryDoesNotStartCooldown() throws Exception {
        FakeReader reader = new FakeReader(150.0);
        CapturingChannel webhook = new CapturingChannel();
        webhook.toThrow = new AlertDeliveryException("boom");
        MutableClock clock = new MutableClock(1000L);
        ThresholdAlertJobHandler h = handler(reader, webhook, null, clock);
        ScheduledJobFile job = jobWith(webhookPayload("GT", 100.0, 60_000L));

        try {
            h.handle(job);
            fail("expected delivery to propagate");
        } catch (AlertDeliveryException expected) {
            // engine records FAILED
        }
        // No fire time stamped, so a later run can retry.
        assertNull(job.getPayload().get(ThresholdAlertSpec.KEY_LAST_FIRED_AT));
    }

    // ---------------- channel selection ----------------

    @Test
    public void emailSelfChannelSelectedByConfig() throws Exception {
        FakeReader reader = new FakeReader(150.0);
        CapturingChannel webhook = new CapturingChannel();
        CapturingChannel email = new CapturingChannel();
        Map<String, Object> payload = webhookPayload("GT", 100.0);
        Map<String, Object> channel = new LinkedHashMap<>();
        channel.put("type", "EMAIL_SELF");
        payload.put("channel", channel);

        handler(reader, webhook, email, new MutableClock(1L)).handle(jobWith(payload));

        assertEquals(0, webhook.deliveries);
        assertEquals(1, email.deliveries);
        assertEquals(AlertChannelConfig.Type.EMAIL_SELF, email.lastConfig.getType());
    }

    // ---------------- owner-context + args passthrough ----------------

    @Test
    public void readerReceivesCubeMeasureAndFilters_underCurrentContext() throws Exception {
        // Establish an identity marker; the handler must NOT swap it (no re-impersonation).
        final AtomicReference<String> ctx = new AtomicReference<>("owner-alice");
        MeasureValueReader reader = (cube, measure, filters) -> {
            assertEquals("handler must not re-impersonate", "owner-alice", ctx.get());
            return 150.0;
        };
        CapturingChannel webhook = new CapturingChannel();
        Map<String, Object> payload = webhookPayload("GT", 100.0);
        List<Map<String, Object>> filters = new ArrayList<>();
        Map<String, Object> f = new HashMap<>();
        f.put("dimension", "Time");
        f.put("hierarchy", "Time");
        f.put("level", "Year");
        f.put("members", List.of("[Time].[2024]"));
        filters.add(f);
        payload.put("filters", filters);

        handler(reader, webhook, null, new MutableClock(1L)).handle(jobWith(payload));
        assertEquals(1, webhook.deliveries);
    }

    @Test
    public void filtersParsedAndPassedToReader() throws Exception {
        FakeReader reader = new FakeReader(150.0);
        CapturingChannel webhook = new CapturingChannel();
        Map<String, Object> payload = webhookPayload("GT", 100.0);
        List<Map<String, Object>> filters = new ArrayList<>();
        Map<String, Object> f = new HashMap<>();
        f.put("dimension", "Store");
        f.put("level", "Country");
        f.put("members", List.of("[Store].[USA]"));
        filters.add(f);
        payload.put("filters", filters);

        handler(reader, webhook, null, new MutableClock(1L)).handle(jobWith(payload));

        assertEquals(1, reader.lastFilters.size());
        assertEquals("Store", reader.lastFilters.get(0).getDimension());
        assertEquals("Country", reader.lastFilters.get(0).getLevel());
        assertEquals("[Store].[USA]", reader.lastFilters.get(0).getMembers().get(0));
    }

    // ---------------- validation ----------------

    @Test(expected = IllegalArgumentException.class)
    public void malformedPayload_throws() throws Exception {
        handler(new FakeReader(1.0), new CapturingChannel(), null, new MutableClock(1L))
                .handle(jobWith(new HashMap<>()));
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullJob_throws() throws Exception {
        handler(new FakeReader(1.0), new CapturingChannel(), null, new MutableClock(1L))
                .handle(null);
    }
}
