/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.generate.quickstart;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.Test;

public class CsvTableLoaderTest {

    private Connection newInMemoryConnection() throws Exception {
        // A unique DB name per test so parallel/sequential runs never share state.
        String url = "jdbc:h2:mem:quickstart-loader-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        return DriverManager.getConnection(url, "sa", "");
    }

    @Test
    public void createsTableAndLoadsRows() throws Exception {
        CsvTable table = CsvTable.parse("amount,label\n10,foo\n20,bar\n30,baz\n");
        try (Connection c = newInMemoryConnection()) {
            int rowCount = CsvTableLoader.load(table, "sales", c);
            assertEquals(3, rowCount);

            try (Statement st = c.createStatement();
                    ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM \"sales\"")) {
                assertTrue(rs.next());
                assertEquals(3, rs.getInt(1));
            }
            try (Statement st = c.createStatement();
                    ResultSet rs = st.executeQuery("SELECT \"amount\", \"label\" FROM \"sales\" ORDER BY \"ID\"")) {
                assertTrue(rs.next());
                assertEquals(10L, rs.getLong(1));
                assertEquals("foo", rs.getString(2));
            }
        }
    }

    @Test
    public void blankCellsAreLoadedAsNull() throws Exception {
        // Two columns, not one — see CsvTableTest.blankCellsDoNotDowngradeTheColumnType for why a
        // single-column blank line isn't the right way to exercise a blank cell.
        CsvTable table = CsvTable.parse("n,x\n5,a\n,b\n7,c\n");
        try (Connection c = newInMemoryConnection()) {
            CsvTableLoader.load(table, "t", c);
            try (Statement st = c.createStatement();
                    ResultSet rs = st.executeQuery("SELECT \"n\" FROM \"t\" ORDER BY \"ID\"")) {
                assertTrue(rs.next());
                assertEquals(5L, rs.getLong(1));
                assertTrue(rs.next());
                rs.getLong(1);
                assertTrue(rs.wasNull());
                assertTrue(rs.next());
                assertEquals(7L, rs.getLong(1));
            }
        }
    }

    @Test
    public void columnNamesWithSpacesAndQuotesAreSafeIdentifiers() throws Exception {
        // The column header is attacker-controlled text. CsvTable.parse sanitises it first
        // ("weird name" -> "weird_name"), and the loader double-quotes every identifier as the
        // second line of defence, so even a table name that needs quoting ("odd table") loads.
        CsvTable table = CsvTable.parse("weird name,another\n1,2\n");
        try (Connection c = newInMemoryConnection()) {
            CsvTableLoader.load(table, "odd table", c);
            DatabaseMetaData meta = c.getMetaData();
            try (ResultSet rs = meta.getColumns(null, null, "odd table", null)) {
                boolean sawSanitised = false;
                boolean sawRawHeader = false;
                while (rs.next()) {
                    String name = rs.getString("COLUMN_NAME");
                    sawSanitised |= "weird_name".equals(name);
                    sawRawHeader |= "weird name".equals(name);
                }
                assertTrue("the sanitised header is the column name", sawSanitised);
                assertFalse("the raw header text never reaches the schema", sawRawHeader);
            }
        }
    }

    @Test
    public void aSourceColumnNamedIdDoesNotCollideWithTheSurrogatePrimaryKey() throws Exception {
        // CsvTable.parse renames a header "id" to "id_2" (QuickstartNames) precisely so this
        // loads without a duplicate-column CREATE TABLE error.
        CsvTable table = CsvTable.parse("id,amount\n7,10\n");
        try (Connection c = newInMemoryConnection()) {
            int rowCount = CsvTableLoader.load(table, "t", c);
            assertEquals(1, rowCount);
            try (Statement st = c.createStatement();
                    ResultSet rs = st.executeQuery("SELECT \"ID\", \"id_2\", \"amount\" FROM \"t\"")) {
                assertTrue(rs.next());
                assertEquals(1L, rs.getLong("ID")); // the surrogate PK, auto-incremented
                assertEquals(7L, rs.getLong("id_2")); // the source CSV's own "id" column
                assertEquals(10L, rs.getLong("amount"));
            }
        }
    }

    @Test
    public void tableHasNoForeignKeysOfItsOwn() throws Exception {
        // Sanity check for TableClassifier's lone-table rule: a loaded CSV table must not
        // accidentally declare an FK to itself or anything else.
        CsvTable table = CsvTable.parse("a\n1\n");
        try (Connection c = newInMemoryConnection()) {
            CsvTableLoader.load(table, "lone", c);
            DatabaseMetaData meta = c.getMetaData();
            try (ResultSet rs = meta.getImportedKeys(null, null, "lone")) {
                assertFalse(rs.next());
            }
        }
    }
}
