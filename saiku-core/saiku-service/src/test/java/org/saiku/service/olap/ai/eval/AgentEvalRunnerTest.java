/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.saiku.service.olap.ai.AiCubeRef;

public class AgentEvalRunnerTest {

    private static final AiCubeRef CUBE = new AiCubeRef("conn", "cat", "sch", "Sales");

    @Test
    public void passingSuiteReportsAllGreen() {
        EvalCase c = new EvalCase(
                "sales-by-country",
                "show sales by country",
                List.of(),
                "QUERY",
                null,
                List.of(map("country", "USA", "storeSales", 500.0)),
                true,
                null,
                EvalTolerance.EXACT);
        EvalSuite suite = new EvalSuite("t", "test", CUBE, List.of(c));

        EvalAskAdapter adapter = (cube, question, history) ->
                EvalAskResult.forQuery("stub-model", List.of(map("country", "USA", "storeSales", 500.0)));

        EvalReport report = new AgentEvalRunner(adapter).run(suite);
        assertTrue(report.allPassed());
        assertEquals(1, report.passedCount());
        assertEquals(0, report.failedCount());
        assertEquals(EvalOutcome.Status.PASS, report.outcomes().get(0).status());
    }

    @Test
    public void intentMismatchFailsFastWithoutDeeperChecks() {
        // A case expecting REFUSED but getting QUERY shouldn't cascade into a "no refusal reason"
        // failure — the intent mismatch is enough to explain the failure.
        EvalCase c = new EvalCase(
                "refuse-off-topic",
                "what's the weather?",
                List.of(),
                "REFUSED",
                "cube",
                null,
                true,
                null,
                EvalTolerance.EXACT);
        EvalSuite suite = new EvalSuite("t", null, CUBE, List.of(c));

        EvalAskAdapter adapter =
                (cube, question, history) -> EvalAskResult.forQuery("stub-model", List.of(map("weather", "sunny")));

        EvalReport report = new AgentEvalRunner(adapter).run(suite);
        assertFalse(report.allPassed());
        assertEquals(EvalOutcome.Status.FAIL, report.outcomes().get(0).status());
        assertEquals(1, report.outcomes().get(0).mismatches().size());
        assertEquals("intent", report.outcomes().get(0).mismatches().get(0).path());
    }

    @Test
    public void refusalReasonSubstringChecked() {
        EvalCase c = new EvalCase(
                "refuse-off-topic",
                "what's the weather?",
                List.of(),
                "REFUSED",
                "not about this cube",
                null,
                true,
                null,
                EvalTolerance.EXACT);
        EvalSuite suite = new EvalSuite("t", null, CUBE, List.of(c));

        EvalAskAdapter adapter = (cube, question, history) ->
                EvalAskResult.forRefusal("stub-model", "OFF_TOPIC: unrelated to the cube's data");

        EvalReport report = new AgentEvalRunner(adapter).run(suite);
        assertFalse("refusal reason doesn't contain the expected substring", report.allPassed());
        assertEquals(
                "refusalReason", report.outcomes().get(0).mismatches().get(0).path());
    }

    @Test
    public void degradedAdapterProducesDegradedOutcome() {
        EvalCase c = new EvalCase("any", "q", List.of(), null, null, null, true, null, EvalTolerance.EXACT);
        EvalSuite suite = new EvalSuite("t", null, CUBE, List.of(c));

        EvalAskAdapter adapter =
                (cube, question, history) -> EvalAskResult.forDegraded("stub-model", "HTTP 503: upstream unavailable");

        EvalReport report = new AgentEvalRunner(adapter).run(suite);
        assertEquals(1, report.degradedCount());
        assertEquals(0, report.passedCount());
        assertFalse(report.allPassed());
    }

    @Test
    public void adapterThrowingIsHandledAsDegraded() {
        // A wire-level exception (network, JSON parse, whatever) must translate to a degraded
        // outcome — the runner shouldn't fail the whole suite because one case's adapter blew up.
        EvalCase c = new EvalCase("any", "q", List.of(), null, null, null, true, null, EvalTolerance.EXACT);
        EvalSuite suite = new EvalSuite("t", null, CUBE, List.of(c));

        EvalAskAdapter adapter = (cube, question, history) -> {
            throw new RuntimeException("kaboom");
        };

        EvalReport report = new AgentEvalRunner(adapter).run(suite);
        assertEquals(1, report.degradedCount());
    }

    @Test
    public void insightSubstringMatchWorks() {
        EvalCase c = new EvalCase(
                "insight-check",
                "spot trends",
                List.of(),
                "INSIGHT",
                null,
                null,
                true,
                List.of("Store Sales", "up 12%"),
                EvalTolerance.EXACT);
        EvalSuite suite = new EvalSuite("t", null, CUBE, List.of(c));

        EvalAskAdapter adapter = (cube, question, history) ->
                EvalAskResult.forInsight("stub-model", "Store Sales are up 12% week-on-week.");

        EvalReport report = new AgentEvalRunner(adapter).run(suite);
        assertTrue(report.allPassed());
    }

    @Test
    public void insightMissingSubstringIsMismatch() {
        EvalCase c = new EvalCase(
                "insight-check",
                "spot trends",
                List.of(),
                "INSIGHT",
                null,
                null,
                true,
                List.of("Store Sales", "up 20%"),
                EvalTolerance.EXACT);
        EvalSuite suite = new EvalSuite("t", null, CUBE, List.of(c));

        EvalAskAdapter adapter = (cube, question, history) ->
                EvalAskResult.forInsight("stub-model", "Store Sales are up 12% week-on-week.");

        EvalReport report = new AgentEvalRunner(adapter).run(suite);
        assertFalse(report.allPassed());
        // The intent match passes; only the "up 20%" substring should surface.
        assertEquals(1, report.outcomes().get(0).mismatches().size());
        assertTrue(report.outcomes().get(0).mismatches().get(0).message().contains("up 20%"));
    }

    @Test
    public void oneLineSummaryFormatsCorrectly() {
        // 2 pass, 1 fail, 1 degraded suite.
        List<EvalCase> cases = List.of(
                caseWithExpected("a", "QUERY"),
                caseWithExpected("b", "QUERY"),
                caseWithExpected("c", "QUERY"),
                caseWithExpected("d", "QUERY"));
        EvalSuite suite = new EvalSuite("t", null, CUBE, cases);

        java.util.Iterator<EvalAskResult> replies = List.of(
                        EvalAskResult.forQuery("m", List.of()),
                        EvalAskResult.forQuery("m", List.of()),
                        EvalAskResult.forInsight("m", "wrong intent"),
                        EvalAskResult.forDegraded("m", "boom"))
                .iterator();
        EvalAskAdapter adapter = (cube, question, history) -> replies.next();

        EvalReport report = new AgentEvalRunner(adapter).run(suite);
        assertEquals("2/4 passed, 1 failed, 1 degraded, 0 skipped", report.oneLine());
    }

    /* ---- helpers ---- */

    private static EvalCase caseWithExpected(String name, String intent) {
        return new EvalCase(name, "q", List.of(), intent, null, null, true, null, EvalTolerance.EXACT);
    }

    private static Map<String, Object> map(Object... kv) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }
}
