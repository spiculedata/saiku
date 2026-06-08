/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.util.export.excel;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.Test;
import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.dto.resultset.DataCell;
import org.saiku.olap.dto.resultset.MemberCell;

/**
 * Excel formula-injection (CWE-1236) <b>wiring</b> coverage — the Excel sibling of
 * {@code CsvExporterWiringTest} (saiku#1274 / #1271). The CSV export was hardened to neutralise
 * spreadsheet-formula payloads (a leading {@code = + - @}), but the Excel export of the same cube
 * data was not. This drives the real {@link ExcelWorksheetBuilder#build()} with a malicious cell,
 * reads the produced workbook back, and asserts the value is defanged in the file — so a refactor
 * that drops the {@code formulaSafe(...)} wrap at an emission site goes RED, not only one that
 * broke the shared neutraliser.
 */
public class ExcelWorksheetBuilderWiringTest {

    private static MemberCell corner() {
        return new MemberCell(); // rawValue null → top-left corner cell
    }

    private static DataCell data(String formattedValue) {
        DataCell c = new DataCell();
        c.setFormattedValue(formattedValue);
        return c;
    }

    /** Build a minimal one-cell workbook and return every STRING cell value it contains. */
    private static List<String> buildAndReadStringCells(String bodyValue) throws Exception {
        CellDataSet cds = new CellDataSet();
        cds.setCellSetHeaders(new AbstractBaseCell[][] {{corner()}});
        cds.setCellSetBody(new AbstractBaseCell[][] {{data(bodyValue)}});

        byte[] xlsx = new ExcelWorksheetBuilder(cds, Collections.emptyList(), new ExcelBuilderOptions()).build();

        List<String> values = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            for (Sheet sheet : wb) {
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        if (cell.getCellType() == CellType.STRING) {
                            values.add(cell.getStringCellValue());
                        }
                    }
                }
            }
        }
        return values;
    }

    @Test
    public void buildNeutralisesAMaliciousCubeCell() throws Exception {
        List<String> cells = buildAndReadStringCells("=cmd|'/C calc'!A0");
        assertTrue(
                "malicious cell must be defanged (quote-prefixed) in the .xlsx: " + cells,
                cells.contains("'=cmd|'/C calc'!A0"));
        assertFalse(
                "the raw, executable formula must not survive into the .xlsx: " + cells,
                cells.contains("=cmd|'/C calc'!A0"));
    }

    @Test
    public void buildLeavesBenignTextUnquoted() throws Exception {
        List<String> cells = buildAndReadStringCells("Beer");
        assertTrue("benign text must still export: " + cells, cells.contains("Beer"));
        assertFalse("benign text must not gain a formula-guard prefix: " + cells, cells.contains("'Beer"));
    }
}
