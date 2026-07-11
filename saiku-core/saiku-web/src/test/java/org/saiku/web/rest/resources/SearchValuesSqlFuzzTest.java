/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.*;

import java.util.Random;
import org.junit.Test;

/**
 * Property-based fuzz tests for {@link AiOssieResource#buildValuesSearchSql}.
 *
 * <p>The {@code /ai/ossie/values/search} endpoint takes a user-supplied {@code q} parameter
 * and interpolates it into a SQL {@code LIKE} predicate via
 * {@link AiOssieResource#buildValuesSearchSql}. The endpoint uses an inlined literal (rather
 * than a JDBC parameter) because Calcite's parameter binding drops out at plan time under
 * {@code LIKE} with {@code UPPER}/{@code CAST} wrapping, so escape correctness is enforced by
 * this helper alone. This suite verifies that.
 *
 * <p>Load-bearing security invariant: <em>for any hostile string in {@code q}, the emitted SQL
 * always closes its single-quoted literal — the state-machine walker leaves us OUTSIDE at
 * end-of-string.</em> If not, a client can break out of the {@code LIKE} literal and inject.
 *
 * <p>Uses the same state-machine checker as {@code OssieShelfSqlTranslatorFuzzTest}. Fixed seed.
 */
public class SearchValuesSqlFuzzTest {

    private static final long SEED = 0x5EA4C4E5L;

    /**
     * The corpus that stress-tests naive quoting. Same shapes as the translator fuzz but focused
     * on the LIKE literal shape (which sits inside {@code '%…%'}).
     */
    private static final String[] HOSTILE_Q = {
        "'",
        "''",
        "';",
        "'; DROP TABLE users; --",
        "%' OR 1=1 --",
        "%'; DELETE FROM x; --",
        "%')' UNION SELECT * FROM users --",
        "abc\\'def",
        "a'b'c",
        "%",
        "_",
        "\\",
        "%''''",
        "%';--",
        "\n",
        "\r",
        "\t",
        "",
        " ",
        "café",
        "中文",
    };

    @Test
    public void emittedSqlAlwaysHasBalancedQuotesUnderHostileQ() {
        Random rng = new Random(SEED);
        for (int i = 0; i < 5000; i++) {
            String col = pickCol(rng);
            String src = pickSrc(rng);
            String q = HOSTILE_Q[rng.nextInt(HOSTILE_Q.length)];
            int cap = 1 + rng.nextInt(500);
            String sql = AiOssieResource.buildValuesSearchSql(col, src, q, cap);
            assertQuotesBalanced(sql, i, q);
        }
    }

    @Test
    public void emittedSqlNeverLetsInjectionEscapeQuotedRegions() {
        Random rng = new Random(SEED + 1);
        String[] canaries = {"DROP TABLE", "DELETE FROM", "INSERT INTO", "UNION SELECT"};
        for (int i = 0; i < 3000; i++) {
            String q = HOSTILE_Q[rng.nextInt(HOSTILE_Q.length)];
            String sql = AiOssieResource.buildValuesSearchSql(pickCol(rng), pickSrc(rng), q, 1 + rng.nextInt(500));
            String outside = stripQuotedRegions(sql);
            for (String canary : canaries) {
                if (outside.contains(canary)) {
                    fail("injection payload '" + canary + "' escaped quoted region — iteration " + i + " q=" + q
                            + "\nFULL SQL:\n" + sql);
                }
            }
        }
    }

    @Test
    public void nullOrBlankQProducesUnfilteredSelect() {
        String sql = AiOssieResource.buildValuesSearchSql("region", "\"customers\"", null, 20);
        assertEquals("SELECT DISTINCT region FROM \"customers\" LIMIT 20", sql);
        String sqlBlank = AiOssieResource.buildValuesSearchSql("region", "\"customers\"", "   ", 20);
        assertEquals("SELECT DISTINCT region FROM \"customers\" LIMIT 20", sqlBlank);
    }

    @Test
    public void singleQuoteInQIsProperlyEscaped() {
        String sql = AiOssieResource.buildValuesSearchSql("region", "\"customers\"", "O'Neil", 20);
        // Escaped to O''NEIL (uppercased + doubled quote), inside %..% wrapper.
        assertTrue("expected escaped quote: " + sql, sql.contains("'%O''NEIL%'"));
        assertQuotesBalanced(sql, -1, "O'Neil");
    }

    // ------------------------------------------------------------
    // State-machine quote checker — identical logic to OssieShelfSqlTranslatorFuzzTest.
    // Duplicated intentionally so the two fuzz suites stay independent.
    // ------------------------------------------------------------

    private static final int OUT = 0;
    private static final int IN_IDENT = 1;
    private static final int IN_LIT = 2;

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
            } else {
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

    private static String stripQuotedRegions(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < sql.length()) {
                    if (sql.charAt(j) == c) {
                        if (j + 1 < sql.length() && sql.charAt(j + 1) == c) j += 2;
                        else {
                            j++;
                            break;
                        }
                    } else j++;
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
    // Column + source pickers. Column names are validated against the model before this method
    // fires in production, so we bias toward realistic identifiers. Source strings are always
    // pre-quoted ("...") by the caller.
    // ------------------------------------------------------------

    private static String pickCol(Random rng) {
        String[] cols = {"region", "country", "product_family", "id", "name"};
        return cols[rng.nextInt(cols.length)];
    }

    private static String pickSrc(Random rng) {
        String[] srcs = {"\"customers\"", "\"orders\"", "\"product\"", "\"time_by_day\""};
        return srcs[rng.nextInt(srcs.length)];
    }
}
