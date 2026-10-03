/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schedule.delivery;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.dto.resultset.DataCell;

/** CSV rendering + spreadsheet-formula-injection defusal for the scheduled export (saiku#1987). */
public class CellSetCsvWriterTest {

    private static DataCell data(String value) {
        DataCell c = new DataCell();
        c.setFormattedValue(value);
        return c;
    }

    private static CellDataSet grid(AbstractBaseCell[][] headers, AbstractBaseCell[][] body) {
        CellDataSet cds = new CellDataSet(1, 1);
        cds.setCellSetHeaders(headers);
        cds.setCellSetBody(body);
        return cds;
    }

    // ---------------- shape ----------------

    @Test
    public void writesHeadersThenBody() {
        CellDataSet cds = grid(new AbstractBaseCell[][] {{data("Store")}}, new AbstractBaseCell[][] {{data("Berlin")}});
        assertEquals("Store\nBerlin\n", CellSetCsvWriter.toCsvString(cds));
    }

    @Test
    public void emitsAUtf8BomSoExcelOpensNonAsciiExportsCorrectly() {
        byte[] bytes = CellSetCsvWriter.toCsvBytes(
                grid(new AbstractBaseCell[][] {{data("Store")}}, new AbstractBaseCell[][] {{data("Köln")}}));
        assertEquals((byte) 0xEF, bytes[0]);
        assertEquals((byte) 0xBB, bytes[1]);
        assertEquals((byte) 0xBF, bytes[2]);
        assertTrue(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8).contains("Köln"));
    }

    @Test
    public void writesEveryColumnOfEveryRow() {
        CellDataSet cds = grid(
                new AbstractBaseCell[][] {{data("A"), data("B")}},
                new AbstractBaseCell[][] {{data("1"), data("2")}, {data("3"), data("4")}});
        assertEquals("A,B\n1,2\n3,4\n", CellSetCsvWriter.toCsvString(cds));
    }

    @Test
    public void anEmptyOrNullCellSetProducesAnEmptyDocumentNotAnException() {
        assertEquals("", CellSetCsvWriter.toCsvString(null));
        assertEquals("", CellSetCsvWriter.toCsvString(grid(null, null)));
    }

    @Test
    public void aNullCellBecomesAnEmptyField() {
        CellDataSet cds =
                grid(new AbstractBaseCell[][] {{data("A"), null}}, new AbstractBaseCell[][] {{null, data("2")}});
        assertEquals("A,\n,2\n", CellSetCsvWriter.toCsvString(cds));
    }

    @Test
    public void fallsBackToTheRawValueWhenThereIsNoFormattedValue() {
        DataCell c = new DataCell();
        c.setRawValue("42");
        CellDataSet cds = grid(new AbstractBaseCell[][] {{c}}, new AbstractBaseCell[][] {{c}});
        assertEquals("42\n42\n", CellSetCsvWriter.toCsvString(cds));
    }

    // ---------------- RFC-4180 quoting ----------------

    @Test
    public void quotesValuesContainingASeparatorQuoteOrNewline() {
        assertEquals("\"a,b\"", CellSetCsvWriter.escape("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", CellSetCsvWriter.escape("say \"hi\""));
        assertEquals("\"line1\nline2\"", CellSetCsvWriter.escape("line1\nline2"));
        assertEquals("\"carriage\rreturn\"", CellSetCsvWriter.escape("carriage\rreturn"));
    }

    @Test
    public void leavesAPlainValueUnquoted() {
        assertEquals("Berlin", CellSetCsvWriter.escape("Berlin"));
        assertEquals("", CellSetCsvWriter.escape(null));
    }

    // ---------------- formula injection ----------------

    /**
     * An export routinely gets opened in a spreadsheet. A cell whose text starts with {@code =}
     * (or {@code + - @}) is a formula there, so a crafted dimension member could execute on the
     * operator's machine. The single-quote prefix is the standard mitigation.
     */
    @Test
    public void defusesASpreadsheetFormulaInACellValue() {
        assertEquals("'=1+1", CellSetCsvWriter.escape("=1+1"));
        assertEquals("'+1", CellSetCsvWriter.escape("+1"));
        assertEquals("'-2", CellSetCsvWriter.escape("-2"));
        assertEquals("'@SUM(A1)", CellSetCsvWriter.escape("@SUM(A1)"));
    }

    @Test
    public void aFormulaCharacterInTheMiddleIsNotTreatedAsAFormula() {
        assertEquals("a=b", CellSetCsvWriter.escape("a=b"));
        assertEquals("2-1", CellSetCsvWriter.escape("2-1"));
    }

    @Test
    public void aNegativeNumberIsStillDefused() {
        // Defusing a legitimate negative number is a cosmetic cost, and erring that way is correct:
        // the alternative is a formula-injection hole for one slightly uglier cell.
        assertEquals("'-42", CellSetCsvWriter.escape("-42"));
    }
}
