/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.Test;

public class EvalResultStoreTest {

    /** Fresh in-memory H2 per test — DB_CLOSE_DELAY keeps it alive across the store's connections. */
    private static EvalResultStore memStore(String name) {
        return new EvalResultStore("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
    }

    private static EvalReport report(String suite, int pass, int fail, int degraded) {
        java.util.List<EvalOutcome> outcomes = new java.util.ArrayList<>();
        for (int i = 0; i < pass; i++) outcomes.add(EvalOutcome.pass("p" + i, 10, "QUERY", "claude-x"));
        for (int i = 0; i < fail; i++)
            outcomes.add(EvalOutcome.fail(
                    "f" + i,
                    20,
                    "QUERY",
                    "claude-x",
                    List.of(new EvalOutcome.Mismatch("rows[0].x", "expected A got B"))));
        for (int i = 0; i < degraded; i++) outcomes.add(EvalOutcome.degraded("d" + i, 5, "boom", "claude-x"));
        return new EvalReport(suite, "desc", outcomes, 123);
    }

    @Test
    public void savesAndReadsBackARun() {
        EvalResultStore store = memStore("roundtrip");
        long id = store.save(report("s1", 3, 1, 0), "conn/cat/sch/Sales", Instant.parse("2026-07-12T10:00:00Z"));
        assertTrue(id > 0);

        List<EvalResultStore.RunSummary> runs = store.recentRuns("s1", 10);
        assertEquals(1, runs.size());
        EvalResultStore.RunSummary r = runs.get(0);
        assertEquals("s1", r.suiteName());
        assertEquals("conn/cat/sch/Sales", r.cubeRef());
        assertEquals(4, r.total());
        assertEquals(3, r.passed());
        assertEquals(1, r.failed());
        assertEquals(0, r.degraded());
        assertEquals(0.75, r.passRate(), 1e-9);
        assertEquals(Instant.parse("2026-07-12T10:00:00Z"), r.startedAt());
    }

    @Test
    public void recentRunsAreNewestFirstAndLimited() {
        EvalResultStore store = memStore("recent");
        store.save(report("s", 1, 0, 0), "c", Instant.parse("2026-07-10T10:00:00Z"));
        store.save(report("s", 2, 0, 0), "c", Instant.parse("2026-07-11T10:00:00Z"));
        store.save(report("s", 3, 0, 0), "c", Instant.parse("2026-07-12T10:00:00Z"));

        List<EvalResultStore.RunSummary> runs = store.recentRuns("s", 2);
        assertEquals(2, runs.size());
        // newest first
        assertEquals(Instant.parse("2026-07-12T10:00:00Z"), runs.get(0).startedAt());
        assertEquals(Instant.parse("2026-07-11T10:00:00Z"), runs.get(1).startedAt());
    }

    @Test
    public void passRateSeriesIsOldestFirst() {
        EvalResultStore store = memStore("trend");
        // 100%, then 50%, then 0% — a degrading model.
        store.save(report("s", 2, 0, 0), "c", Instant.parse("2026-07-10T10:00:00Z"));
        store.save(report("s", 1, 1, 0), "c", Instant.parse("2026-07-11T10:00:00Z"));
        store.save(report("s", 0, 2, 0), "c", Instant.parse("2026-07-12T10:00:00Z"));

        List<EvalResultStore.TrendPoint> series = store.passRateSeries("s", 10);
        assertEquals(3, series.size());
        assertEquals(1.0, series.get(0).passRate(), 1e-9);
        assertEquals(0.5, series.get(1).passRate(), 1e-9);
        assertEquals(0.0, series.get(2).passRate(), 1e-9);
    }

    @Test
    public void isolatesRunsBySuite() {
        EvalResultStore store = memStore("bysuite");
        store.save(report("alpha", 1, 0, 0), "c", Instant.parse("2026-07-12T10:00:00Z"));
        store.save(report("beta", 1, 0, 0), "c", Instant.parse("2026-07-12T10:00:00Z"));
        assertEquals(1, store.recentRuns("alpha", 10).size());
        assertEquals(0, store.recentRuns("gamma", 10).size());
    }
}
