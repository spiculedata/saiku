/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.generate.quickstart;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class CsvTableTest {

    @Test
    public void infersLongDoubleAndStringColumns() {
        CsvTable table = CsvTable.parse("amount,ratio,label\n" + "10,1.5,foo\n" + "20,2.25,bar\n");

        List<CsvTable.Column> columns = table.columns();
        assertEquals(3, columns.size());
        assertEquals("amount", columns.get(0).name());
        assertEquals(CsvTable.ColumnType.LONG, columns.get(0).type());
        assertEquals("ratio", columns.get(1).name());
        assertEquals(CsvTable.ColumnType.DOUBLE, columns.get(1).type());
        assertEquals("label", columns.get(2).name());
        assertEquals(CsvTable.ColumnType.STRING, columns.get(2).type());
        assertEquals(2, table.rows().size());
    }

    @Test
    public void wholeLongColumnStaysLongEvenWhenADoubleAppearsLater() {
        // Regression guard for order-dependent inference: "5" then "2.5" must not classify the
        // column as LONG just because the first value happened to parse as one.
        CsvTable table = CsvTable.parse("n\n5\n2.5\n");
        assertEquals(CsvTable.ColumnType.DOUBLE, table.columns().get(0).type());
    }

    @Test
    public void mixedNumericAndTextWidensToString() {
        // saiku#1117 test-plan: "Bad CSV (mixed types) surfaces a usable error" — a genuinely
        // mixed column doesn't fail the whole upload, it just isn't inferred as numeric.
        CsvTable table = CsvTable.parse("n\n5\nnot-a-number\n");
        assertEquals(CsvTable.ColumnType.STRING, table.columns().get(0).type());
    }

    @Test
    public void blankCellsDoNotDowngradeTheColumnType() {
        // Two columns, not one — a bare blank LINE is a different thing from a blank CELL (see
        // parseRecords' class doc: a lone blank line is dropped as a spacer, not read as a
        // one-column row of nulls). This exercises a blank cell inside an otherwise-populated row.
        CsvTable table = CsvTable.parse("n,x\n5,a\n,b\n7,c\n");
        assertEquals(CsvTable.ColumnType.LONG, table.columns().get(0).type());
        assertEquals(3, table.rows().size());
        assertEquals("", table.rows().get(1).get(0));
    }

    @Test
    public void entirelyBlankColumnIsString() {
        CsvTable table = CsvTable.parse("a,n\nx,\ny,\n");
        assertEquals(CsvTable.ColumnType.STRING, table.columns().get(1).type());
    }

    @Test
    public void isoDateColumnIsDetected() {
        CsvTable table = CsvTable.parse("sale_date,amount\n2024-01-15,10\n2024-02-20,20\n");
        assertEquals(CsvTable.ColumnType.DATE, table.columns().get(0).type());
    }

    @Test
    public void nonIsoDateFallsBackToString() {
        CsvTable table = CsvTable.parse("d\n01/15/2024\n02/20/2024\n");
        assertEquals(CsvTable.ColumnType.STRING, table.columns().get(0).type());
    }

    @Test
    public void quotedFieldsWithCommasAndEscapedQuotesParseCorrectly() {
        CsvTable table = CsvTable.parse("name,note\n\"Acme, Inc.\",\"she said \"\"hi\"\"\"\nBeta,plain\n");
        assertEquals(2, table.rows().size());
        assertEquals("Acme, Inc.", table.rows().get(0).get(0));
        assertEquals("she said \"hi\"", table.rows().get(0).get(1));
        assertEquals("Beta", table.rows().get(1).get(0));
    }

    @Test
    public void crlfLineEndingsAreAccepted() {
        CsvTable table = CsvTable.parse("a,b\r\n1,2\r\n3,4\r\n");
        assertEquals(2, table.rows().size());
        assertEquals("1", table.rows().get(0).get(0));
        assertEquals("4", table.rows().get(1).get(1));
    }

    @Test
    public void trailingBlankLineIsIgnored() {
        CsvTable table = CsvTable.parse("a\n1\n2\n\n");
        assertEquals(2, table.rows().size());
    }

    @Test
    public void blankHeaderCellFallsBackToPositionalName() {
        CsvTable table = CsvTable.parse("a,,c\n1,2,3\n");
        assertEquals("a", table.columns().get(0).name());
        assertEquals("column_2", table.columns().get(1).name());
        assertEquals("c", table.columns().get(2).name());
    }

    @Test
    public void duplicateHeaderNamesAreDeduplicated() {
        CsvTable table = CsvTable.parse("amount,amount\n1,2\n");
        assertEquals("amount", table.columns().get(0).name());
        assertEquals("amount_2", table.columns().get(1).name());
    }

    @Test
    public void emptyFileIsRejected() {
        CsvIngestException e = assertThrows(CsvIngestException.class, () -> CsvTable.parse(""));
        assertTrue(e.getMessage().toLowerCase().contains("empty"));
    }

    @Test
    public void headerOnlyFileIsRejected() {
        CsvIngestException e = assertThrows(CsvIngestException.class, () -> CsvTable.parse("a,b,c\n"));
        assertTrue(e.getMessage().toLowerCase().contains("no data"));
    }

    @Test
    public void raggedRowIsRejectedWithARowNumber() {
        CsvIngestException e = assertThrows(CsvIngestException.class, () -> CsvTable.parse("a,b,c\n1,2,3\n4,5\n"));
        assertTrue(e.getMessage().contains("row 3"));
    }
}
