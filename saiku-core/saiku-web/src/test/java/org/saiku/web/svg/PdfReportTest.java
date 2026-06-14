/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.svg;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.dto.resultset.DataCell;
import org.saiku.olap.dto.resultset.MemberCell;

/**
 * Characterization test for {@code svg/PdfReport} — the PDF renderer the classic
 * {@code QueryResource} export path runs through. Unlike {@code export/PdfReport}
 * (which renders via Apache FOP), this one builds the table DIRECTLY through the
 * OpenPDF (com.lowagie) API — {@code PdfWriter}/{@code PdfPTable}/{@code PdfPCell}
 * — so it is the live path whose runtime behaviour actually depends on the
 * itext&rarr;OpenPDF swap (saiku#1329). It shipped with zero coverage; this locks
 * that a non-empty, well-formed PDF (the {@code %PDF-} signature) is still
 * produced from a CellDataSet, so a future incompatible PDF-library change is
 * caught here instead of silently shipping a broken export.
 */
public class PdfReportTest {

    private static AbstractBaseCell member(String v) {
        AbstractBaseCell c = new MemberCell(false, false);
        c.setFormattedValue(v);
        return c;
    }

    private static AbstractBaseCell data(String v) {
        AbstractBaseCell c = new DataCell(true, false, null);
        c.setFormattedValue(v);
        c.setRawValue(v);
        return c;
    }

    /** A minimal one-dimension table: row-header "Year" + measure "Store Sales",
     *  two data rows — the same shape AiQueryResourceTest feeds the AI surfaces. */
    private static CellDataSet smallCellDataSet() {
        CellDataSet cds = new CellDataSet(2, 1);
        cds.setCellSetHeaders(
                new AbstractBaseCell[][] {new AbstractBaseCell[] {member("Year"), member("Store Sales")}});
        cds.setCellSetBody(new AbstractBaseCell[][] {
            new AbstractBaseCell[] {member("1997"), data("100")},
            new AbstractBaseCell[] {member("1998"), data("200")},
        });
        return cds;
    }

    @Test
    public void rendersAWellFormedPdfFromACellDataSet() {
        byte[] pdf = new PdfReport().pdf(smallCellDataSet(), null);

        assertTrue("export must produce a non-trivial PDF", pdf != null && pdf.length > 100);
        String magic = new String(pdf, 0, Math.min(5, pdf.length), StandardCharsets.US_ASCII);
        assertTrue("output must carry the %PDF- signature, got: " + magic, magic.startsWith("%PDF-"));
    }

    @Test
    public void documentNameDoesNotBreakRendering() {
        // The running-header path (documentName -> HeaderFooter) is a distinct
        // OpenPDF code path; prove it still yields a valid PDF.
        byte[] pdf = new PdfReport().pdf(smallCellDataSet(), null, "My Quarterly Export");

        assertTrue("named export must produce a non-trivial PDF", pdf != null && pdf.length > 100);
        String magic = new String(pdf, 0, Math.min(5, pdf.length), StandardCharsets.US_ASCII);
        assertTrue("output must carry the %PDF- signature, got: " + magic, magic.startsWith("%PDF-"));
    }
}
