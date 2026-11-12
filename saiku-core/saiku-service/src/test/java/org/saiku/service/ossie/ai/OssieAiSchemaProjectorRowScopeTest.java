/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.ossie.OssieModelDto;

/**
 * saiku#2324 — schema {@code sampleValues} must honour the caller's {@code saiku.roles} row
 * predicates, and the shared sample cache must not leak one caller's scope to another.
 */
public class OssieAiSchemaProjectorRowScopeTest {

    private Connection jdbc;

    @Before
    public void setUp() throws Exception {
        jdbc = DriverManager.getConnection("jdbc:h2:mem:rowscope" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        try (Statement st = jdbc.createStatement()) {
            st.execute("CREATE TABLE \"geography\" (REGION VARCHAR(20), COUNTRY VARCHAR(20))");
            st.execute("INSERT INTO \"geography\" VALUES ('APAC','Australia'),('EMEA','Germany'),('AMER','USA')");
        }
    }

    @After
    public void tearDown() throws Exception {
        jdbc.close();
    }

    @Test
    public void sampleValuesAreScopedToTheCallersRowPredicate() {
        OssieAiSchemaProjector p = new OssieAiSchemaProjector();

        OssieAiSchema schema = p.project("C", fixture(), jdbc, Set.of("ROLE_APAC"));

        assertEquals(List.of("Australia"), samples(schema, "country"));
        assertEquals(List.of("APAC"), samples(schema, "region"));
    }

    @Test
    public void differentRowScopeDoesNotShareTheSampleCache() {
        OssieAiSchemaProjector p = new OssieAiSchemaProjector();

        // The scoped caller warms the cache first...
        p.project("C", fixture(), jdbc, Set.of("ROLE_APAC"));
        // ...then a caller with a different scope must get its own rows, not the cached ones.
        OssieAiSchema emea = p.project("C", fixture(), jdbc, Set.of("ROLE_EMEA"));
        assertEquals(List.of("Germany"), samples(emea, "country"));

        // And the reverse: the unrestricted caller must not inherit a scoped caller's cache.
        OssieAiSchema open = p.project("C", fixture(), jdbc, Set.of());
        List<String> all = samples(open, "country");
        assertEquals(3, all.size());
        assertTrue(all.containsAll(List.of("Australia", "Germany", "USA")));
    }

    @Test
    public void unrestrictedCallerKeepsWorkingWithTheThreeArgOverload() {
        OssieAiSchema schema = new OssieAiSchemaProjector().project("C", fixture(), jdbc);
        assertEquals(3, samples(schema, "country").size());
    }

    private static List<String> samples(OssieAiSchema schema, String field) {
        return schema.getDatasets().get("geography").getFields().get(field).getSampleValues();
    }

    private static OssieModelDto fixture() {
        OssieModelDto semantic = new OssieModelDto();
        semantic.setName("M");
        OssieModelDto.Dataset ds = new OssieModelDto.Dataset();
        ds.setName("geography");
        ds.setSource("GEOGRAPHY");
        for (String n : List.of("REGION", "COUNTRY")) {
            OssieModelDto.Field f = new OssieModelDto.Field();
            f.setName(n);
            ds.getFields().add(f);
        }
        ds.getRowPredicates().add(new OssieModelDto.RowPredicate("ROLE_APAC", "REGION = 'APAC'"));
        ds.getRowPredicates().add(new OssieModelDto.RowPredicate("ROLE_EMEA", "REGION = 'EMEA'"));
        semantic.getDatasets().add(ds);
        return semantic;
    }
}
