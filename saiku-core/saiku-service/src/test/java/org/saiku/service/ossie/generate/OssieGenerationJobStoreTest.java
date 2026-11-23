/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.generate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.Test;

/**
 * Unit tests for {@link OssieGenerationJobStore} — TTL, sliding expiry, and the {@code PENDING}
 * publish that makes an immediate poll a hit rather than a 404.
 */
public class OssieGenerationJobStoreTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    /** Clock the test advances by hand — no sleeping, no flake. */
    private static final class MutableClock extends Clock {
        private Instant now = T0;

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    @Test
    public void createPublishesAPendingJobImmediately() {
        MutableClock clock = new MutableClock();
        OssieGenerationJobStore store = new OssieGenerationJobStore(Duration.ofMinutes(30), clock);

        OssieGenerationJob job = store.create("ds-1", "warehouse");

        assertEquals(OssieGenerationJob.Stage.PENDING, job.stage());
        // The whole reason POST can return 202 + a job id: the id is already pollable.
        assertTrue(store.get(job.id()).isPresent());
    }

    @Test
    public void blankModelNameFallsBackToTheDataSourceId() {
        OssieGenerationJobStore store = new OssieGenerationJobStore(Duration.ofMinutes(30), new MutableClock());

        assertEquals("ds-7", store.create("ds-7", null).modelName());
        assertEquals("ds-7", store.create("ds-7", "   ").modelName());
    }

    @Test
    public void unknownIdIsEmpty() {
        OssieGenerationJobStore store = new OssieGenerationJobStore();
        assertFalse(store.get("nope").isPresent());
        assertFalse(store.get(null).isPresent());
    }

    @Test
    public void idleJobExpires() {
        MutableClock clock = new MutableClock();
        OssieGenerationJobStore store = new OssieGenerationJobStore(Duration.ofMinutes(30), clock);
        OssieGenerationJob job = store.create("ds-1", "warehouse");

        clock.advance(Duration.ofMinutes(31));

        assertFalse("an idle job past its TTL must be gone", store.get(job.id()).isPresent());
        assertEquals(0, store.size());
    }

    @Test
    public void pollingKeepsAJobAlive() {
        MutableClock clock = new MutableClock();
        OssieGenerationJobStore store = new OssieGenerationJobStore(Duration.ofMinutes(30), clock);
        OssieGenerationJob job = store.create("ds-1", "warehouse");

        // Poll every 20 minutes for two hours. A generation run polled at a sane cadence must
        // never have its job evicted mid-flight — this is the sliding-TTL guarantee.
        for (int i = 0; i < 6; i++) {
            clock.advance(Duration.ofMinutes(20));
            assertTrue(
                    "poll " + i + " must still find the job",
                    store.get(job.id()).isPresent());
        }
        assertEquals(1, store.size());
    }

    @Test
    public void evictExpiredDropsOnlyTheIdleOnes() {
        MutableClock clock = new MutableClock();
        OssieGenerationJobStore store = new OssieGenerationJobStore(Duration.ofMinutes(30), clock);
        OssieGenerationJob stale = store.create("ds-stale", "a");
        OssieGenerationJob fresh = store.create("ds-fresh", "b");

        clock.advance(Duration.ofMinutes(20));
        store.get(fresh.id()); // touch only the fresh one
        clock.advance(Duration.ofMinutes(20));

        store.evictExpired();

        assertEquals(1, store.size());
        assertFalse(store.get(stale.id()).isPresent());
        assertTrue(store.get(fresh.id()).isPresent());
    }

    @Test
    public void removeDropsTheJob() {
        OssieGenerationJobStore store = new OssieGenerationJobStore();
        OssieGenerationJob job = store.create("ds-1", "warehouse");

        store.remove(job.id());

        assertEquals(0, store.size());
        assertFalse(store.get(job.id()).isPresent());
    }

    @Test
    public void removeToleratesNull() {
        OssieGenerationJobStore store = new OssieGenerationJobStore();
        store.remove(null);
        assertEquals(0, store.size());
    }

    @Test
    public void jobStartsCleanAndDefaultsAreNull() {
        MutableClock clock = new MutableClock();
        OssieGenerationJob job = new OssieGenerationJobStore(Duration.ofMinutes(30), clock).create("ds-1", "w");

        assertNotNull(job.id());
        assertEquals(T0, job.createdAt());
        assertNull(job.failureMessage());
        assertNull(job.yamlPath());
        assertNull(job.rationalePath());
        assertNull("no baseline on a first run", job.deltaReport());
        assertFalse(job.degraded());
        assertTrue(job.appliedOps().isEmpty());
        assertTrue(job.skippedCubes().isEmpty());
    }

    @Test
    public void appendAppliedOpTouchesTheAccessTime() {
        MutableClock clock = new MutableClock();
        OssieGenerationJobStore store = new OssieGenerationJobStore(Duration.ofMinutes(30), clock);
        OssieGenerationJob job = store.create("ds-1", "w");

        clock.advance(Duration.ofMinutes(25));
        job.appendAppliedOp(
                new org.saiku.service.schema.generate.enrich.ops.IgnoreOp("cubes/ORDERS", 0.5, "not a real cube"));

        assertEquals(1, job.appliedOps().size());
        assertEquals("appending an op must count as activity", T0.plusSeconds(25 * 60), job.lastAccessedAt());
    }

    @Test
    public void skippedCubesIsDefensivelyCopied() {
        OssieGenerationJob job = new OssieGenerationJobStore().create("ds-1", "w");

        job.setSkippedCubes(java.util.List.of("A"));
        java.util.List<String> live = new java.util.ArrayList<>(job.skippedCubes());
        live.add("B");

        assertEquals(
                "the job must not expose a mutable view", 1, job.skippedCubes().size());
    }

    @Test
    public void nullSkippedCubesBecomesEmpty() {
        OssieGenerationJob job = new OssieGenerationJobStore().create("ds-1", "w");
        job.setSkippedCubes(null);
        assertEquals(java.util.List.of(), job.skippedCubes());
    }

    @Test
    public void terminalStageIsReachableAndCarriesTheReason() {
        OssieGenerationJob job = new OssieGenerationJobStore().create("ds-1", "w");

        job.setFailureMessage("boom");
        job.setStage(OssieGenerationJob.Stage.FAILED);

        assertEquals(OssieGenerationJob.Stage.FAILED, job.stage());
        assertEquals("boom", job.failureMessage());
        Optional<OssieGenerationJob> found = new OssieGenerationJobStore().get("other");
        assertFalse(found.isPresent());
    }
}
