/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.ossie.OssieModelDto;

/**
 * saiku#2319 — runs {@code runValuesSearch} through a real Calcite connection whose schema is a
 * {@code JdbcSchema} over H2 (the same push-down path an Ossie warehouse uses), proving the
 * search term is bound rather than inlined: hostile input matches nothing, breaks nothing, and
 * a legitimate term still finds its rows.
 */
public class SearchValuesBindTest {

    private Connection warehouse;
    private Connection calcite;

    @Before
    public void setUp() throws Exception {
        // Held open for the test: the in-memory H2 database lives as long as this connection.
        String db = "valuesbind" + System.nanoTime();
        warehouse = DriverManager.getConnection("jdbc:h2:mem:" + db, "sa", "");
        try (Statement st = warehouse.createStatement()) {
            st.execute("CREATE TABLE \"payer\" (NAME VARCHAR(60))");
            st.execute("INSERT INTO \"payer\" VALUES ('Acme'),('O''Neil'),('back\\slash'),('Zed')");
        }
        String model = "{version:'1.0',defaultSchema:'W',schemas:[{name:'W',type:'jdbc'," + "jdbcUrl:'jdbc:h2:mem:" + db
                + "',jdbcDriver:'org.h2.Driver',jdbcUser:'sa',jdbcPassword:''}]}";
        calcite = DriverManager.getConnection("jdbc:calcite:model=inline:" + model + ";caseSensitive=false");
    }

    @After
    public void tearDown() throws Exception {
        calcite.close();
        warehouse.close();
    }

    @Test
    public void legitimateTermFindsMatchingRowsCaseInsensitively() throws Exception {
        assertEquals(List.of("Acme"), search("acm"));
        assertEquals(List.of("O'Neil"), search("o'ne"));
    }

    @Test
    public void backslashQuoteBreakoutPayloadIsDataNotSql() throws Exception {
        assertTrue(search("\\' OR 1=1 --").isEmpty());
        assertTrue(search("x%' UNION SELECT NAME FROM \"payer\" --").isEmpty());
        assertTrue(search("'; DROP TABLE \"payer\"; --").isEmpty());
        // Table still intact afterwards.
        assertEquals(4, search(null).size());
    }

    private List<String> search(String q) throws Exception {
        OssieModelDto.Dataset ds = new OssieModelDto.Dataset();
        ds.setName("payer");
        OssieModelDto.Field field = new OssieModelDto.Field();
        field.setName("NAME");
        return AiOssieResource.runValuesSearch(calcite, ds, field, q, 50);
    }
}
