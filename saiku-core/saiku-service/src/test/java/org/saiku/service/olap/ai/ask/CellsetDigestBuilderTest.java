/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.Set;
import org.junit.Test;
import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.dto.resultset.DataCell;
import org.saiku.olap.dto.resultset.MemberCell;

/** Coverage for {@link CellsetDigestBuilder}, mirroring saiku-ui/src/lib/api/cellsetDigest.ts. */
public class CellsetDigestBuilderTest {

    private static MemberCell header(String value) {
        MemberCell cell = new MemberCell(false, false);
        cell.setFormattedValue(value);
        return cell;
    }

    private static DataCell data(String value) {
        DataCell cell = new DataCell(false, false, Collections.emptyList());
        cell.setFormattedValue(value);
        return cell;
    }

    @Test
    public void basicDigestMatchesClientFormat() {
        CellDataSet cds = new CellDataSet();
        cds.setCellSetHeaders(new AbstractBaseCell[][] {{header("Tier"), header("Balance")}});
        cds.setCellSetBody(new AbstractBaseCell[][] {
            {data("Small"), data("1,500")}, {data("Medium"), data("4,500")}, {data("Large"), data("7,000")}
        });

        String expected = "Cellset: 3 data rows × 2 columns.\n"
                + "\n"
                + "| Tier | Balance |\n"
                + "| --- | --- |\n"
                + "| Small | 1,500 |\n"
                + "| Medium | 4,500 |\n"
                + "| Large | 7,000 |";

        assertEquals(expected, CellsetDigestBuilder.digest(cds, 50));
    }

    @Test
    public void truncatesToMaxRowsAndNotesIt() {
        CellDataSet cds = new CellDataSet();
        cds.setCellSetHeaders(new AbstractBaseCell[][] {{header("Tier"), header("Balance")}});
        cds.setCellSetBody(new AbstractBaseCell[][] {
            {data("Small"), data("1,500")}, {data("Medium"), data("4,500")}, {data("Large"), data("7,000")}
        });

        String expected = "Cellset: 3 data rows × 2 columns.\n"
                + "(Showing first 2 of 3 rows.)\n"
                + "\n"
                + "| Tier | Balance |\n"
                + "| --- | --- |\n"
                + "| Small | 1,500 |\n"
                + "| Medium | 4,500 |";

        assertEquals(expected, CellsetDigestBuilder.digest(cds, 2));
    }

    @Test
    public void scrubsPipesAndCollapsesWhitespace() {
        CellDataSet cds = new CellDataSet();
        cds.setCellSetHeaders(new AbstractBaseCell[][] {{header("Col1"), header("Col2")}});
        cds.setCellSetBody(new AbstractBaseCell[][] {{data("a|b"), data("  x   y ")}});

        String expected = "Cellset: 1 data rows × 2 columns.\n"
                + "\n"
                + "| Col1 | Col2 |\n"
                + "| --- | --- |\n"
                + "| a/b | x y |";

        assertEquals(expected, CellsetDigestBuilder.digest(cds, 50));
    }

    @Test
    public void nullCellDataSetReturnsEmptyString() {
        assertEquals("", CellsetDigestBuilder.digest(null, 50));
    }

    @Test
    public void nullBodyReturnsEmptyString() {
        CellDataSet cds = new CellDataSet();
        cds.setCellSetHeaders(new AbstractBaseCell[][] {{header("Tier")}});
        cds.setCellSetBody(null);

        assertEquals("", CellsetDigestBuilder.digest(cds, 50));
    }

    @Test
    public void emptyBodyReturnsEmptyString() {
        CellDataSet cds = new CellDataSet();
        cds.setCellSetHeaders(new AbstractBaseCell[][] {{header("Tier")}});
        cds.setCellSetBody(new AbstractBaseCell[0][]);

        assertEquals("", CellsetDigestBuilder.digest(cds, 50));
    }

    @Test
    public void noHeaderRowsOmitsSeparator() {
        CellDataSet cds = new CellDataSet();
        cds.setCellSetHeaders(new AbstractBaseCell[0][]);
        cds.setCellSetBody(new AbstractBaseCell[][] {{data("Small"), data("1,500")}});

        String expected = "Cellset: 1 data rows × 2 columns.\n" + "\n" + "| Small | 1,500 |";

        assertEquals(expected, CellsetDigestBuilder.digest(cds, 50));
    }

    @Test
    public void raggedRowsRenderMissingCellsAsEmpty() {
        CellDataSet cds = new CellDataSet();
        cds.setCellSetHeaders(new AbstractBaseCell[][] {{header("Tier"), header("Balance"), header("Extra")}});
        cds.setCellSetBody(new AbstractBaseCell[][] {{data("Small")}});

        String expected = "Cellset: 1 data rows × 3 columns.\n"
                + "\n"
                + "| Tier | Balance | Extra |\n"
                + "| --- | --- | --- |\n"
                + "| Small |  |  |";

        assertEquals(expected, CellsetDigestBuilder.digest(cds, 50));
    }

    // ---------------------------------------------------------------------
    // saiku#1918 (17a) — digest redaction. The digest is the last hop before a
    // server-executed cellset crosses to the LLM as text, so it re-checks the PII
    // posture of the query that produced it rather than trusting that every path
    // into it went through the converter.
    // ---------------------------------------------------------------------

    @Test
    public void redactRowHeaderBlanksColumnZeroAndLeavesMeasuresIntact() {
        CellDataSet cds = new CellDataSet();
        cds.setCellSetHeaders(new AbstractBaseCell[][] {{header("Customer"), header("Store Sales")}});
        cds.setCellSetBody(
                new AbstractBaseCell[][] {{data("Wanda Maximoff"), data("1,500")}, {data("Vision"), data("4,500")}});

        String out = CellsetDigestBuilder.digest(cds, 50, CellsetDigestBuilder.DigestPolicy.redactRowHeader());

        assertTrue(out, out.contains("| " + CellsetDigestBuilder.REDACTED + " | 1,500 |"));
        // The aggregate is the whole point of the report — redacting it would make the digest
        // useless rather than safe. Only the per-person axis is suppressed.
        assertTrue(out, out.contains("4,500"));
        assertFalse("per-person caption must not survive", out.contains("Wanda Maximoff"));
        assertFalse("a second caption in the same column must not survive either", out.contains("Vision"));
    }

    @Test
    public void redactColumnHeadersBlanksOnlyTheNamedColumn() {
        CellDataSet cds = new CellDataSet();
        cds.setCellSetHeaders(new AbstractBaseCell[][] {{header("Country"), header("Customer Email")}});
        cds.setCellSetBody(new AbstractBaseCell[][] {
            {data("USA"), data("wanda@example.test")}, {data("UK"), data("vision@example.test")}
        });

        String out = CellsetDigestBuilder.digest(
                cds, 50, CellsetDigestBuilder.DigestPolicy.redactColumnHeaders(Set.of("Customer Email")));

        assertFalse(out, out.contains("wanda@example.test"));
        assertFalse(out, out.contains("vision@example.test"));
        // The non-PII column is untouched, and so is the row axis (that level isn't PII here).
        assertTrue(out, out.contains("USA"));
    }

    @Test
    public void columnHeaderRedactionIsCaseInsensitive() {
        CellDataSet cds = new CellDataSet();
        cds.setCellSetHeaders(new AbstractBaseCell[][] {{header("Country"), header("Customer Email")}});
        cds.setCellSetBody(new AbstractBaseCell[][] {{data("USA"), data("wanda@example.test")}});

        String out = CellsetDigestBuilder.digest(
                cds, 50, CellsetDigestBuilder.DigestPolicy.redactColumnHeaders(Set.of("customer email")));

        assertFalse(out, out.contains("wanda@example.test"));
    }

    @Test
    public void defaultDigestIsUnchangedByTheRedactionOverload() {
        // Regression guard: NONE must be byte-identical to the historical two-arg call, because the
        // digest format is a contract with saiku-ui/src/lib/api/cellsetDigest.ts and with every
        // prompt already tuned against it.
        CellDataSet cds = new CellDataSet();
        cds.setCellSetHeaders(new AbstractBaseCell[][] {{header("Tier"), header("Balance")}});
        cds.setCellSetBody(new AbstractBaseCell[][] {{data("Small"), data("1,500")}});

        assertEquals(
                CellsetDigestBuilder.digest(cds, 50),
                CellsetDigestBuilder.digest(cds, 50, CellsetDigestBuilder.DigestPolicy.NONE));
        assertEquals(CellsetDigestBuilder.digest(cds, 50), CellsetDigestBuilder.digest(cds, 50, null));
    }
}
