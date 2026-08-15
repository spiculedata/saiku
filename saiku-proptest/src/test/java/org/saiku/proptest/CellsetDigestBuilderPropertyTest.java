/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.proptest;

import static dev.hegel.Generators.fromRegex;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.List;
import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.dto.resultset.MemberCell;
import org.saiku.service.olap.ai.ask.CellsetDigestBuilder;

/**
 * Property-based tests for {@link CellsetDigestBuilder}, which renders an executed cellset as the
 * markdown table sent to the LLM.
 *
 * <p>The digest is the LLM's ONLY view of the numbers it is about to reason over, so its integrity
 * is a correctness property, not a formatting nicety. Two failure modes matter:
 *
 * <ul>
 *   <li><b>Table corruption.</b> Markdown columns are delimited by {@code |}. A cell value
 *       containing a raw pipe silently shifts every value after it into the wrong column — the LLM
 *       then reports a real number under the wrong heading, with no error anywhere.
 *   <li><b>Silent truncation.</b> If more rows are dropped than announced, the model reasons over a
 *       subset while believing it has the whole set, and states conclusions with false confidence.
 * </ul>
 *
 * <p>Cellsets are ragged in practice (header rows and body rows differ in length), which is exactly
 * the shape hand-written examples tend not to cover.
 */
class CellsetDigestBuilderPropertyTest {

    private static AbstractBaseCell cell(String formatted) {
        MemberCell c = new MemberCell();
        c.setFormattedValue(formatted);
        return c;
    }

    /** A ragged grid guaranteed to carry at least one column, so digest() is non-empty. */
    private static AbstractBaseCell[][] nonEmptyGrid(TestCase tc, String label, int rows, int maxWidth) {
        AbstractBaseCell[][] out = grid(tc, label, rows, Math.max(1, maxWidth));
        // grid() may draw width 0 for every row; force one cell so a column exists.
        if (out.length > 0) {
            out[0] = new AbstractBaseCell[] {cell(tc.draw(fromRegex("[a-z]{1,6}"), label + ".seed"))};
        }
        return out;
    }

    /** Build a possibly-ragged grid; a null row or null cell is a legitimate shape. */
    private static AbstractBaseCell[][] grid(TestCase tc, String label, int rows, int maxWidth) {
        AbstractBaseCell[][] out = new AbstractBaseCell[rows][];
        for (int r = 0; r < rows; r++) {
            int width = tc.draw(integers().min(0).max(maxWidth), label + ".width" + r);
            AbstractBaseCell[] row = new AbstractBaseCell[width];
            for (int c = 0; c < width; c++) {
                row[c] = cell(tc.draw(fromRegex("[a-zA-Z0-9 |._-]{0,10}"), label + ".v" + r + "_" + c));
            }
            out[r] = row;
        }
        return out;
    }

    private static CellDataSet dataSet(AbstractBaseCell[][] headers, AbstractBaseCell[][] body) {
        CellDataSet cds = new CellDataSet();
        cds.setCellSetHeaders(headers);
        cds.setCellSetBody(body);
        return cds;
    }

    /** Table rows are the lines starting with "|" that are not the "---" separator. */
    private static List<String> tableRows(String digest) {
        List<String> out = new ArrayList<>();
        for (String line : digest.split("\n", -1)) {
            if (line.startsWith("|") && !line.contains("---")) {
                out.add(line);
            }
        }
        return out;
    }

    /** Cell count for a rendered markdown row. */
    private static int cellCount(String row) {
        // "| a | b | c |" -> strip the outer pipes, then split on the inner ones.
        String inner = row.substring(1, row.length() - 1);
        return inner.split("\\|", -1).length;
    }

    /**
     * TABLE INTEGRITY. Every rendered row must carry the same number of cells, or the LLM reads
     * values under the wrong column heading.
     */
    @HegelTest
    void everyRenderedRowHasTheSameCellCount(TestCase tc) {
        int headerRows = tc.draw(integers().min(0).max(3), "headerRows");
        int bodyRows = tc.draw(integers().min(1).max(6), "bodyRows");
        int maxWidth = tc.draw(integers().min(1).max(5), "maxWidth");
        int maxRows = tc.draw(integers().min(1).max(8), "maxRows");

        String digest = CellsetDigestBuilder.digest(
                dataSet(grid(tc, "h", headerRows, maxWidth), grid(tc, "b", bodyRows, maxWidth)), maxRows);
        tc.note(digest);

        List<String> rows = tableRows(digest);
        if (rows.isEmpty()) {
            return;
        }
        int expected = cellCount(rows.get(0));
        for (String row : rows) {
            assertEquals(expected, cellCount(row), "ragged markdown table:\n" + digest);
        }
    }

    /**
     * A pipe inside a value must never reach the output — it would be read as a column delimiter
     * and shift every subsequent value one column left.
     */
    @HegelTest
    void aPipeInsideAValueNeverCorruptsTheTable(TestCase tc) {
        String left = tc.draw(fromRegex("[a-z]{0,6}"), "left");
        String right = tc.draw(fromRegex("[a-z]{0,6}"), "right");
        int pipes = tc.draw(integers().min(1).max(3), "pipes");

        String value = left + "|".repeat(pipes) + right;
        AbstractBaseCell[][] body = {{cell(value), cell("safe")}};

        String digest = CellsetDigestBuilder.digest(dataSet(new AbstractBaseCell[0][], body), 10);
        tc.note(digest);

        List<String> rows = tableRows(digest);
        assertEquals(1, rows.size(), "expected one body row:\n" + digest);
        assertEquals(2, cellCount(rows.get(0)), "a pipe in a value split the row:\n" + digest);
    }

    /** Never renders more data rows than the cap allows. */
    @HegelTest
    void neverRendersMoreDataRowsThanTheCap(TestCase tc) {
        int bodyRows = tc.draw(integers().min(1).max(12), "bodyRows");
        int maxRows = tc.draw(integers().min(1).max(12), "maxRows");
        int width = tc.draw(integers().min(1).max(3), "width");

        String digest = CellsetDigestBuilder.digest(
                dataSet(new AbstractBaseCell[0][], grid(tc, "b", bodyRows, width)), maxRows);

        assertTrue(
                tableRows(digest).size() <= maxRows,
                "rendered " + tableRows(digest).size() + " rows for a cap of " + maxRows + ":\n" + digest);
    }

    /**
     * Truncation is announced exactly when it happens. Announcing when nothing was dropped is
     * merely noisy; NOT announcing when rows were dropped means the model reasons over a subset
     * while believing it has everything.
     */
    @HegelTest
    void truncationIsAnnouncedExactlyWhenItHappens(TestCase tc) {
        int bodyRows = tc.draw(integers().min(1).max(12), "bodyRows");
        int maxRows = tc.draw(integers().min(1).max(12), "maxRows");
        int width = tc.draw(integers().min(1).max(3), "width");

        String digest = CellsetDigestBuilder.digest(
                dataSet(new AbstractBaseCell[0][], nonEmptyGrid(tc, "b", bodyRows, width)), maxRows);
        tc.note(digest);

        boolean announced = digest.contains("Showing first");
        boolean actuallyTruncated = tableRows(digest).size() < bodyRows;

        assertEquals(actuallyTruncated, announced, "truncation notice disagrees with what was rendered:\n" + digest);
    }

    /** The declared row count is the TRUE total, not the rendered subset — the model needs both. */
    @HegelTest
    void theDeclaredRowCountIsTheFullTotal(TestCase tc) {
        int bodyRows = tc.draw(integers().min(1).max(12), "bodyRows");
        int maxRows = tc.draw(integers().min(1).max(6), "maxRows");

        String digest = CellsetDigestBuilder.digest(
                dataSet(new AbstractBaseCell[0][], nonEmptyGrid(tc, "b", bodyRows, 2)), maxRows);

        assertTrue(
                digest.startsWith("Cellset: " + bodyRows + " data rows"),
                "declared count is not the full total:\n" + digest);
    }

    /** Nothing useful to send yields the empty string rather than a stub table. */
    @HegelTest
    void anEmptyCellsetYieldsAnEmptyDigest(TestCase tc) {
        int which = tc.draw(integers().min(0).max(2), "which");

        String digest =
                switch (which) {
                    case 0 -> CellsetDigestBuilder.digest(null, 10);
                    case 1 -> CellsetDigestBuilder.digest(
                            dataSet(new AbstractBaseCell[0][], new AbstractBaseCell[0][]), 10);
                        // A body whose rows are all zero-width carries no columns.
                    default -> CellsetDigestBuilder.digest(
                            dataSet(new AbstractBaseCell[0][], new AbstractBaseCell[][] {new AbstractBaseCell[0]}), 10);
                };

        assertEquals("", digest, "expected an empty digest");
    }

    /** Output never ends in whitespace — it is concatenated straight into a prompt. */
    @HegelTest
    void theDigestNeverEndsInWhitespace(TestCase tc) {
        int bodyRows = tc.draw(integers().min(1).max(5), "bodyRows");
        int width = tc.draw(integers().min(1).max(3), "width");

        String digest =
                CellsetDigestBuilder.digest(dataSet(new AbstractBaseCell[0][], grid(tc, "b", bodyRows, width)), 10);

        assertFalse(digest.isEmpty() && !digest.equals(""), "unexpected shape");
        assertEquals(digest.stripTrailing(), digest, "digest ended in whitespace");
    }

    /**
     * Total over ragged and null-bearing grids — real cellsets have header rows and body rows of
     * different widths, and a null row is a legitimate shape.
     */
    @HegelTest
    void digestIsTotalOverRaggedAndNullBearingGrids(TestCase tc) {
        int headerRows = tc.draw(integers().min(0).max(3), "headerRows");
        int bodyRows = tc.draw(integers().min(1).max(5), "bodyRows");
        int maxRows = tc.draw(sampledFrom(List.of(-5, -1, 0, 1, 3, 100)), "maxRows");

        AbstractBaseCell[][] headers = grid(tc, "h", headerRows, 4);
        AbstractBaseCell[][] body = grid(tc, "b", bodyRows, 4);
        // Punch holes: a null row, and a null cell inside a row.
        if (body.length > 0 && tc.draw(dev.hegel.Generators.booleans(), "nullRow")) {
            body[0] = null;
        }
        if (body.length > 1 && body[1] != null && body[1].length > 0) {
            body[1][0] = null;
        }

        assertDoesNotThrow(() -> CellsetDigestBuilder.digest(dataSet(headers, body), maxRows));
    }
}
