/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import static org.junit.Assert.assertEquals;

import java.util.Collections;
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
}
