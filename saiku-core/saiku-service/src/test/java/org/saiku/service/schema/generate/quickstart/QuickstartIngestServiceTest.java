/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.generate.quickstart;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class QuickstartIngestServiceTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static InputStream stream(String csv) {
        return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void ingestsCsvIntoAQueryableTable() throws Exception {
        QuickstartIngestService service = new QuickstartIngestService(tmp.getRoot().toPath());

        QuickstartUploadResult result =
                service.ingest("sales", "sales.csv", stream("amount,region\n10,east\n20,west\n"));

        assertEquals("sales", result.tableName());
        assertEquals(2, result.rowCount());
        assertEquals("org.h2.Driver", result.driver());
        assertTrue(result.jdbcUrl().startsWith("jdbc:h2:"));
        assertEquals(2, result.columns().size());
        assertEquals("amount", result.columns().get(0).name());
        assertEquals("LONG", result.columns().get(0).type());

        try (Connection c = DriverManager.getConnection(result.jdbcUrl(), "sa", "");
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM \"sales\"")) {
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1));
        }
    }

    @Test
    public void blankRequestedNameFallsBackToTheUploadedFileName() throws Exception {
        QuickstartIngestService service = new QuickstartIngestService(tmp.getRoot().toPath());

        QuickstartUploadResult result = service.ingest("  ", "Q3 Sales.csv", stream("a\n1\n"));

        assertEquals("Q3_Sales", result.tableName());
    }

    @Test
    public void aSecondUploadWithTheSameNameIsRejected() throws Exception {
        QuickstartIngestService service = new QuickstartIngestService(tmp.getRoot().toPath());
        service.ingest("sales", "sales.csv", stream("a\n1\n"));

        CsvIngestException e = assertThrows(
                CsvIngestException.class, () -> service.ingest("sales", "sales.csv", stream("a\n1\n")));
        assertTrue(e.getMessage().contains("already exists"));
    }

    @Test
    public void malformedCsvLeavesNoDatabaseDirectoryBehind() throws Exception {
        QuickstartIngestService service = new QuickstartIngestService(tmp.getRoot().toPath());

        assertThrows(CsvIngestException.class, () -> service.ingest("bad", "bad.csv", stream("")));

        Path expectedDir = tmp.getRoot().toPath().resolve("data").resolve("quickstart").resolve("bad");
        assertFalse(Files.exists(expectedDir));
    }
}
