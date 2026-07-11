/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class EvalYamlReaderTest {

    private static final String CUBE_BLOCK =
            "cube:\n  connectionName: unknown_foodmart\n  catalog: FoodMart\n  schema: FoodMart\n  cubeName: Sales\n";

    @Test
    public void parsesGoldenPath() throws Exception {
        String yaml = "name: foodmart-evals\n"
                + "description: Ground-truth cases\n"
                + CUBE_BLOCK
                + "cases:\n"
                + "  - name: sales-by-country\n"
                + "    question: show store sales by country\n"
                + "    expectedIntent: QUERY\n"
                + "    expectedRows:\n"
                + "      - {country: USA, storeSales: 500.0}\n"
                + "      - {country: Canada, storeSales: 100.0}\n"
                + "    tolerance: {relative: 0.001}\n"
                + "    orderMatters: true\n";
        EvalSuite s = EvalYamlReader.read(yaml, "test.yaml");
        assertEquals("foodmart-evals", s.name());
        assertEquals("Ground-truth cases", s.description());
        assertEquals("unknown_foodmart", s.cube().getConnectionName());
        assertEquals(1, s.cases().size());

        EvalCase c = s.cases().get(0);
        assertEquals("sales-by-country", c.name());
        assertEquals("QUERY", c.expectedIntent());
        assertEquals(2, c.expectedRows().size());
        assertEquals(0.001, c.tolerance().relative(), 1e-9);
        assertTrue(c.orderMatters());
    }

    @Test
    public void parsesRefusalCase() throws Exception {
        String yaml = "name: refusals\n"
                + CUBE_BLOCK
                + "cases:\n"
                + "  - name: refuse-off-topic\n"
                + "    question: what's the weather?\n"
                + "    expectedIntent: REFUSED\n"
                + "    expectedRefusalContains: cube\n";
        EvalSuite s = EvalYamlReader.read(yaml, "t.yaml");
        assertEquals("REFUSED", s.cases().get(0).expectedIntent());
        assertEquals("cube", s.cases().get(0).expectedRefusalContains());
        assertNull(s.cases().get(0).expectedRows());
    }

    @Test
    public void parsesInsightCase() throws Exception {
        String yaml = "name: insights\n"
                + CUBE_BLOCK
                + "cases:\n"
                + "  - name: trend-analysis\n"
                + "    question: spot trends\n"
                + "    expectedIntent: INSIGHT\n"
                + "    expectedInsightContains:\n"
                + "      - Store Sales\n"
                + "      - week-on-week\n";
        EvalSuite s = EvalYamlReader.read(yaml, "t.yaml");
        assertEquals(2, s.cases().get(0).expectedInsightContains().size());
    }

    @Test
    public void orderMattersDefaultsToTrue() throws Exception {
        String yaml = "name: t\n" + CUBE_BLOCK + "cases:\n  - name: a\n    question: q\n";
        EvalSuite s = EvalYamlReader.read(yaml, "t.yaml");
        assertTrue(s.cases().get(0).orderMatters());
    }

    @Test
    public void orderMattersCanBeFalse() throws Exception {
        String yaml = "name: t\n" + CUBE_BLOCK + "cases:\n  - name: a\n    question: q\n    orderMatters: false\n";
        EvalSuite s = EvalYamlReader.read(yaml, "t.yaml");
        assertFalse(s.cases().get(0).orderMatters());
    }

    @Test
    public void rejectsMissingName() {
        assertParseCode(CUBE_BLOCK + "cases:\n  - name: a\n    question: q\n", "MISSING_FIELD");
    }

    @Test
    public void rejectsMissingCube() {
        assertParseCode("name: t\ncases:\n  - name: a\n    question: q\n", "MISSING_FIELD");
    }

    @Test
    public void rejectsMissingCases() {
        assertParseCode("name: t\n" + CUBE_BLOCK, "MISSING_FIELD");
    }

    @Test
    public void rejectsEmptyCases() {
        assertParseCode("name: t\n" + CUBE_BLOCK + "cases: []\n", "MISSING_FIELD");
    }

    @Test
    public void rejectsMalformedYaml() {
        assertParseCode("name: t\n cube: {  broken", "MALFORMED_YAML");
    }

    @Test
    public void rejectsNegativeTolerance() {
        assertParseCode(
                "name: t\n" + CUBE_BLOCK + "cases:\n  - name: a\n    question: q\n    tolerance: {relative: -0.1}\n",
                "INVALID_TOLERANCE");
    }

    @Test
    public void historySurvivesParsing() throws Exception {
        String yaml = "name: t\n"
                + CUBE_BLOCK
                + "cases:\n"
                + "  - name: a\n"
                + "    question: follow-up\n"
                + "    history:\n"
                + "      - {role: user, content: original question}\n"
                + "      - {role: assistant, content: original answer}\n";
        EvalSuite s = EvalYamlReader.read(yaml, "t.yaml");
        assertEquals(2, s.cases().get(0).history().size());
        assertEquals("user", s.cases().get(0).history().get(0).get("role"));
    }

    @Test
    public void numericRowValuesParseAsNumbers() throws Exception {
        String yaml = "name: t\n"
                + CUBE_BLOCK
                + "cases:\n"
                + "  - name: a\n"
                + "    question: q\n"
                + "    expectedRows:\n"
                + "      - {n: 42, f: 1.5, s: hello}\n";
        EvalSuite s = EvalYamlReader.read(yaml, "t.yaml");
        var row = s.cases().get(0).expectedRows().get(0);
        assertTrue(row.get("n") instanceof Number);
        assertTrue(row.get("f") instanceof Number);
        assertEquals("hello", row.get("s"));
    }

    @Test
    public void toleranceExactWhenNotSpecified() throws Exception {
        String yaml = "name: t\n" + CUBE_BLOCK + "cases:\n  - name: a\n    question: q\n";
        EvalSuite s = EvalYamlReader.read(yaml, "t.yaml");
        assertNull("no explicit tolerance", s.cases().get(0).tolerance());
        // Runner treats null as EXACT by default (verified in AgentEvalRunnerTest).
    }

    @Test
    public void reportsCarrySuiteDescription() throws Exception {
        String yaml = "name: t\ndescription: my suite\n" + CUBE_BLOCK + "cases:\n  - name: a\n    question: q\n";
        EvalSuite s = EvalYamlReader.read(yaml, "t.yaml");
        assertEquals("my suite", s.description());
    }

    /* ---- helpers ---- */

    private static void assertParseCode(String yaml, String expectedCode) {
        try {
            EvalYamlReader.read(yaml, "t.yaml");
            fail("expected EvalParseException(" + expectedCode + ")");
        } catch (EvalYamlReader.EvalParseException e) {
            assertEquals("code (message was: " + e.getMessage() + ")", expectedCode, e.code());
            assertNotNull(e.source());
        }
    }
}
