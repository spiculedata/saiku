/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Before;
import org.junit.Test;
import org.saiku.olap.query2.OssieQueryModel;

/**
 * Property-based fuzz tests for {@link OssieShelfSqlTranslator}.
 *
 * <p>Load-bearing security invariant: <em>for any hostile string that lands in a
 * {@link OssieQueryModel} field name / dataset name / metric name / filter value, the emitted
 * SQL must not contain unescaped single or double quotes.</em> A regression here would open a
 * SQL-injection path from the {@code /ai/ossie/query} endpoint straight to the underlying
 * warehouse.
 *
 * <p>Testing approach: walk the emitted SQL as a state machine (OUTSIDE / IN_IDENT / IN_LIT),
 * handling {@code ""} and {@code ''} as inline escapes. At end-of-string the walk MUST leave us
 * OUTSIDE — otherwise a quote didn't close and an injection primitive is live. Global quote
 * counting doesn't work here because single quotes are inert inside double-quoted identifiers
 * (and vice versa).
 *
 * <p>Extra invariants:
 * <ul>
 *   <li>Common injection payloads ({@code ; DROP TABLE …}) must not appear OUTSIDE quoted
 *       regions — they can appear inside, since they're escaped there and are inert.</li>
 *   <li>{@link OssieShelfSqlTranslator#translate} either returns SQL or throws a
 *       structured {@code IllegalArgumentException} — never NPE, never bare RuntimeException.</li>
 * </ul>
 */
public class OssieShelfSqlTranslatorFuzzTest {

    private static final long SEED = 0xCA11ABE1L;

    private OssieShelfSqlTranslator translator;

    @Before
    public void setUp() {
        translator = new OssieShelfSqlTranslator();
    }

    // ------------------------------------------------------------
    // Hostile input corpus — the strings most likely to break naive quoting
    // ------------------------------------------------------------

    private static final String[] HOSTILE_STRINGS = {
        "'",
        "''",
        "\"",
        "\"\"",
        "';",
        "\";",
        "'; DROP TABLE users; --",
        "\"; DROP TABLE users; --",
        "' OR 1=1 --",
        "\") OR 1=1 --",
        "abc\\'def",
        "a'b\"c",
        "a\\\"b",
        "-1'",
        "-1\"",
        "%'; SELECT * FROM secrets; --",
        "'; INSERT INTO x VALUES('boom'); --",
        "café",
        "中文",
        "\n",
        "\r",
        "\t",
        "",
        " ",
    };

    // ------------------------------------------------------------
    // 1. Quote balance invariant across full translate() pipeline
    // ------------------------------------------------------------

    @Test
    public void emittedSqlAlwaysHasBalancedQuotesUnderHostileInputs() {
        Random rng = new Random(SEED);
        int successful = 0;
        for (int i = 0; i < 3000; i++) {
            OssieModelDto semantic = randomSemantic(rng);
            OssieQueryModel model = randomModel(rng, semantic);
            String sql;
            try {
                sql = translator.translate(model, semantic);
            } catch (IllegalArgumentException | IllegalStateException expected) {
                // These are structured rejections — acceptable, invariant is preserved (no SQL emitted).
                continue;
            } catch (RuntimeException surprising) {
                fail("translator threw non-IllegalArgumentException on iteration " + i + " model=" + describe(model)
                        + ": " + surprising);
                return;
            }
            successful++;
            assertQuotesBalanced(sql, i, model);
        }
        assertTrue("expected at least some hostile inputs to emit SQL", successful > 0);
    }

    // ------------------------------------------------------------
    // 2. Injection payloads never escape quoted regions
    // ------------------------------------------------------------

    @Test
    public void injectionPayloadsStayInsideQuotedRegionsOrAreRejected() {
        Random rng = new Random(SEED + 1);
        String[] canaries = {"DROP TABLE", "DELETE FROM", "INSERT INTO", "; --"};
        for (int i = 0; i < 2000; i++) {
            OssieModelDto semantic = randomSemantic(rng);
            OssieQueryModel model = randomModel(rng, semantic);
            String sql;
            try {
                sql = translator.translate(model, semantic);
            } catch (IllegalArgumentException | IllegalStateException expected) {
                continue;
            }
            String outsideQuotes = stripQuotedRegions(sql);
            for (String canary : canaries) {
                if (outsideQuotes.contains(canary)) {
                    fail("injection payload '" + canary + "' escaped quoted region on iteration " + i
                            + "\n----- FULL SQL -----\n" + sql
                            + "\n----- OUTSIDE QUOTES -----\n" + outsideQuotes);
                }
            }
        }
    }

    // ------------------------------------------------------------
    // 3. Sanity check the quote-balance detector against a known good example
    // ------------------------------------------------------------

    @Test
    public void quoteBalanceCheckerAcceptsCleanSql() {
        String clean = "SELECT \"a\".\"b\" AS \"a.b\" FROM \"a\" WHERE \"a\".\"c\" = 'x''y'";
        // Should not throw
        assertQuotesBalanced(clean, -1, null);
    }

    @Test
    public void quoteBalanceCheckerCatchesUnbalancedSingle() {
        // Ends inside a single-quoted literal — the classic injection primitive.
        String bad = "SELECT * FROM \"a\" WHERE \"a\".\"c\" = 'unterminated";
        try {
            assertQuotesBalanced(bad, -1, null);
            fail("checker should have flagged unterminated literal");
        } catch (AssertionError expected) {
            // want
        }
    }

    @Test
    public void quoteBalanceCheckerAllowsSingleQuoteInsideDoubleQuotedIdentifier() {
        // Single quotes inside a "..." identifier are inert — this is safe SQL.
        String withInertSingle = "SELECT \"weird'name\" FROM \"a\"";
        assertQuotesBalanced(withInertSingle, -1, null);
    }

    // ------------------------------------------------------------
    // Invariant checkers
    // ------------------------------------------------------------

    /**
     * Walk the emitted SQL as a state machine: at each character we're either OUTSIDE any quoted
     * region, INSIDE a {@code "..."} identifier, or INSIDE a {@code '...'} literal. {@code ""}
     * inside identifier scope and {@code ''} inside literal scope are escaped quote chars, not
     * region terminators. The invariant: at end-of-string we must be OUTSIDE all regions.
     *
     * <p>Counting raw quotes globally is wrong — single quotes inside double-quoted identifiers
     * are inert SQL characters that don't need to balance.
     */
    private static void assertQuotesBalanced(String sql, int iteration, Object context) {
        int state = OUT;
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (state == OUT) {
                if (c == '"') state = IN_IDENT;
                else if (c == '\'') state = IN_LIT;
                i++;
            } else if (state == IN_IDENT) {
                if (c == '"') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '"') i += 2;
                    else {
                        state = OUT;
                        i++;
                    }
                } else i++;
            } else { // IN_LIT
                if (c == '\'') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') i += 2;
                    else {
                        state = OUT;
                        i++;
                    }
                } else i++;
            }
        }
        if (state != OUT) {
            fail("SQL ends inside a "
                    + (state == IN_IDENT ? "double-quoted identifier" : "single-quoted literal")
                    + " on iteration " + iteration + " context=" + context + "\nFULL SQL:\n" + sql);
        }
    }

    private static final int OUT = 0;
    private static final int IN_IDENT = 1;
    private static final int IN_LIT = 2;

    /**
     * Return the SQL text after every quoted region (identifiers in {@code "..."} and literals in
     * {@code '...'}) is replaced with a single space. Injection payloads that landed inside a
     * quoted region are removed; anything remaining is executable SQL, and injection payloads
     * that show up there mean the quoting failed.
     */
    private static String stripQuotedRegions(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '"' || c == '\'') {
                // Skip to the matching close, accounting for `""` and `''` escapes.
                int j = i + 1;
                while (j < sql.length()) {
                    if (sql.charAt(j) == c) {
                        if (j + 1 < sql.length() && sql.charAt(j + 1) == c) {
                            j += 2; // escaped
                        } else {
                            j++;
                            break;
                        }
                    } else {
                        j++;
                    }
                }
                out.append(' ');
                i = j;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------
    // Random model builders
    // ------------------------------------------------------------

    private static OssieModelDto randomSemantic(Random rng) {
        OssieModelDto semantic = new OssieModelDto();
        semantic.setName(hostile(rng));
        // Add a small number of legit metrics — expressions come pre-quoted from the YAML, so
        // we don't fuzz the expression content itself (that would test the YAML parser, not
        // the translator).
        int metricCount = 1 + rng.nextInt(4);
        for (int i = 0; i < metricCount; i++) {
            OssieModelDto.Metric m = new OssieModelDto.Metric();
            m.setName(hostile(rng));
            // Pick a benign expression — SUM(orders.amount) is realistic and doesn't add
            // extra quote content to accidentally validate against.
            m.setExpression("SUM(\"orders\".\"amount\")");
            semantic.getMetrics().add(m);
        }
        return semantic;
    }

    private static OssieQueryModel randomModel(Random rng, OssieModelDto semantic) {
        OssieQueryModel m = new OssieQueryModel();
        m.setConnection(hostile(rng));
        m.setModel(semantic.getName());
        m.setFactDataset(hostile(rng));

        int rowCount = rng.nextInt(4);
        List<OssieQueryModel.FieldRef> rows = new ArrayList<>();
        for (int i = 0; i < rowCount; i++) rows.add(fieldRef(rng));
        m.setRows(rows);

        int colCount = rng.nextInt(3);
        List<OssieQueryModel.FieldRef> cols = new ArrayList<>();
        for (int i = 0; i < colCount; i++) cols.add(fieldRef(rng));
        m.setColumns(cols);

        // Values must reference declared metrics for the translator to resolve them; pull from
        // the semantic model to keep translate() from throwing an "unknown metric" error.
        int valCount = rng.nextInt(semantic.getMetrics().size() + 1);
        List<OssieQueryModel.MetricRef> values = new ArrayList<>();
        for (int i = 0; i < valCount; i++) {
            OssieQueryModel.MetricRef v = new OssieQueryModel.MetricRef();
            OssieModelDto.Metric picked =
                    semantic.getMetrics().get(rng.nextInt(semantic.getMetrics().size()));
            v.setMetric(picked.getName());
            if (rng.nextBoolean()) {
                v.setAggregation(new String[] {"SUM", "AVG", "MIN", "MAX", "COUNT", hostile(rng)}[rng.nextInt(6)]);
            }
            values.add(v);
        }
        m.setValues(values);

        int filterCount = rng.nextInt(4);
        List<OssieQueryModel.FilterExpr> filters = new ArrayList<>();
        for (int i = 0; i < filterCount; i++) filters.add(filterExpr(rng));
        m.setFilters(filters);

        int sortCount = rng.nextInt(3);
        List<OssieQueryModel.SortRef> sorts = new ArrayList<>();
        for (int i = 0; i < sortCount; i++) sorts.add(sortRef(rng));
        m.setSorts(sorts);

        if (rng.nextBoolean()) m.setLimit(rng.nextInt(1000));
        return m;
    }

    private static OssieQueryModel.FieldRef fieldRef(Random rng) {
        OssieQueryModel.FieldRef f = new OssieQueryModel.FieldRef();
        f.setDataset(hostile(rng));
        f.setField(hostile(rng));
        return f;
    }

    private static OssieQueryModel.FilterExpr filterExpr(Random rng) {
        OssieQueryModel.FilterExpr f = new OssieQueryModel.FilterExpr();
        f.setDataset(hostile(rng));
        f.setField(hostile(rng));
        String[] ops = {"=", "!=", "<", "<=", ">", ">=", "IN", "NOT_IN", "LIKE", "IS_NULL", "IS_NOT_NULL", "BETWEEN"};
        f.setOp(ops[rng.nextInt(ops.length)]);
        f.setValue(hostile(rng));
        int valCount = rng.nextInt(4);
        List<String> vals = new ArrayList<>();
        for (int i = 0; i < valCount; i++) vals.add(hostile(rng));
        f.setValues(vals);
        return f;
    }

    private static OssieQueryModel.SortRef sortRef(Random rng) {
        OssieQueryModel.SortRef s = new OssieQueryModel.SortRef();
        s.setDataset(hostile(rng));
        s.setField(hostile(rng));
        if (rng.nextBoolean()) s.setMetric(hostile(rng));
        s.setDirection(rng.nextBoolean() ? "DESC" : "ASC");
        return s;
    }

    private static String hostile(Random rng) {
        return HOSTILE_STRINGS[rng.nextInt(HOSTILE_STRINGS.length)];
    }

    private static String describe(OssieQueryModel model) {
        return "factDataset='" + model.getFactDataset() + "' rowCount="
                + model.getRows().size() + " filterCount=" + model.getFilters().size();
    }
}
