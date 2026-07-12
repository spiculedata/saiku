/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.Test;

public class ScheduledEvalRunnerTest {

    private static final String VALID_SUITE = "name: %s\n"
            + "cube:\n"
            + "  connectionName: conn\n"
            + "  catalog: cat\n"
            + "  schema: sch\n"
            + "  cubeName: Sales\n"
            + "cases:\n"
            + "  - name: sales-by-country\n"
            + "    question: show sales by country\n"
            + "    expectedIntent: QUERY\n"
            + "    expectedRows:\n"
            + "      - {country: USA, storeSales: 500.0}\n";

    private static EvalResultStore memStore(String name) {
        return new EvalResultStore("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
    }

    // Adapter that always answers with the expected row → cases pass; reference execution unused.
    private static final EvalAskAdapter PASSING = (cube, q, h) -> {
        java.util.LinkedHashMap<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("country", "USA");
        row.put("storeSales", 500.0);
        return EvalAskResult.forQuery("claude-x", List.of(row));
    };

    @Test
    public void runsEverySuiteAndPersistsEach() throws Exception {
        Path dir = Files.createTempDirectory("evals-run");
        Files.writeString(dir.resolve("a.eval.yaml"), String.format(VALID_SUITE, "suite-a"), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("b.eval.yaml"), String.format(VALID_SUITE, "suite-b"), StandardCharsets.UTF_8);
        // A non-suite file in the same dir must be ignored.
        Files.writeString(dir.resolve("notes.txt"), "ignore me", StandardCharsets.UTF_8);

        EvalResultStore store = memStore("sweep");
        ScheduledEvalRunner runner =
                new ScheduledEvalRunner(dir, PASSING, store, () -> Instant.parse("2026-07-12T09:00:00Z"));

        ScheduledEvalRunner.SweepSummary summary = runner.runAll();

        assertEquals(2, summary.results().size());
        assertEquals(0, summary.skipped().size());
        // Both suites' runs are queryable in the store.
        assertEquals(1, store.recentRuns("suite-a", 10).size());
        assertEquals(1, store.recentRuns("suite-b", 10).size());
        EvalResultStore.RunSummary a = store.recentRuns("suite-a", 10).get(0);
        assertEquals(1, a.passed());
        assertEquals("conn/cat/sch/Sales", a.cubeRef());
        assertEquals(Instant.parse("2026-07-12T09:00:00Z"), a.startedAt());
    }

    @Test
    public void malformedSuiteIsSkippedNotFatal() throws Exception {
        Path dir = Files.createTempDirectory("evals-bad");
        Files.writeString(dir.resolve("good.eval.yaml"), String.format(VALID_SUITE, "good"), StandardCharsets.UTF_8);
        Files.writeString(
                dir.resolve("broken.eval.yaml"), "name: broken\n# no cube, no cases\n", StandardCharsets.UTF_8);

        EvalResultStore store = memStore("badsweep");
        ScheduledEvalRunner runner =
                new ScheduledEvalRunner(dir, PASSING, store, () -> Instant.parse("2026-07-12T09:00:00Z"));

        ScheduledEvalRunner.SweepSummary summary = runner.runAll();

        assertEquals("good suite still runs", 1, summary.results().size());
        assertEquals(1, summary.skipped().size());
        assertTrue(summary.skipped().get(0).reason().contains("MISSING_FIELD"));
    }

    @Test
    public void missingDirYieldsEmptySweep() throws Exception {
        Path dir = Files.createTempDirectory("evals-parent").resolve("does-not-exist");
        ScheduledEvalRunner runner = new ScheduledEvalRunner(dir, PASSING, memStore("empty"));
        ScheduledEvalRunner.SweepSummary summary = runner.runAll();
        assertEquals(0, summary.results().size());
        assertEquals(0, summary.skipped().size());
    }
}
