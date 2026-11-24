/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.objects;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.util.Properties;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.saiku.datasources.datasource.SaikuDatasource;

/**
 * Verifies the JSON ↔ SaikuDatasource round-trip for OSSIE-typed datasources. This is the
 * wire contract the admin UI depends on: an OSSIE datasource POSTed by the UI must land as
 * a {@code SaikuDatasource.Type.OSSIE} with the right property bag, and GETting an existing
 * OSSIE datasource must produce the JSON the UI knows how to render.
 */
public class DataSourceMapperOssieTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File modelDir;
    private String previousExtraRoots;

    @Before
    public void allowTestModelDirectory() throws IOException {
        // saiku#2534: ossieYaml must sit inside a permitted model root; tests point the
        // operator extra-roots property at a scratch directory.
        modelDir = tmp.newFolder("semantic-models");
        previousExtraRoots = System.getProperty("saiku.ossie.model.allowedRoots");
        System.setProperty("saiku.ossie.model.allowedRoots", modelDir.getAbsolutePath());
    }

    @After
    public void restoreExtraRoots() {
        if (previousExtraRoots == null) {
            System.clearProperty("saiku.ossie.model.allowedRoots");
        } else {
            System.setProperty("saiku.ossie.model.allowedRoots", previousExtraRoots);
        }
    }

    private final ObjectMapper json = new ObjectMapper();

    @Test
    public void jsonPostBuildsOssieDatasource() throws Exception {
        String yamlPath = new File(modelDir, "pharma.ossie.yaml").getAbsolutePath();
        String body = "{"
                + "\"connectionname\":\"SALES\","
                + "\"connectiontype\":\"OSSIE\","
                + "\"ossieYaml\":\"" + yamlPath + "\","
                + "\"jdbcurl\":\"jdbc:postgresql://localhost:5432/warehouse\","
                + "\"schema\":\"SALES\","
                + "\"username\":\"admin\","
                + "\"password\":\"secret\"}";
        DataSourceMapper mapped = json.readValue(body, DataSourceMapper.class);
        SaikuDatasource ds = mapped.toSaikuDataSource();

        assertEquals("SALES", ds.getName());
        assertEquals(SaikuDatasource.Type.OSSIE, ds.getType());
        Properties p = ds.getProperties();
        assertEquals(yamlPath, p.getProperty("ossieYaml"));
        assertEquals("jdbc:postgresql://localhost:5432/warehouse", p.getProperty("location"));
        assertEquals("SALES", p.getProperty("schema"));
        assertEquals("admin", p.getProperty("username"));
        assertEquals("secret", p.getProperty("password"));
        assertNotNull("mapper must assign a UUID when id is absent", p.getProperty("id"));
    }

    /**
     * saiku#2534: the mapper is where a POSTed model path is confined — an outside path must
     * be refused with the same IllegalArgumentException the resource maps to a typed 400.
     */
    @Test
    public void ossieYamlOutsideModelRootsRejected() throws Exception {
        String body = "{"
                + "\"connectionname\":\"PROBE\","
                + "\"connectiontype\":\"OSSIE\","
                + "\"ossieYaml\":\"/etc/hostname\","
                + "\"schema\":\"X\"}";
        DataSourceMapper mapped = json.readValue(body, DataSourceMapper.class);
        try {
            mapped.toSaikuDataSource();
            fail("expected the model path to be refused");
        } catch (IllegalArgumentException e) {
            assertTrue(
                    "message should name the refused path — got: " + e.getMessage(),
                    e.getMessage().contains("/etc/hostname"));
        }
    }

    @Test
    public void ossieDatasourceSerialisesBackToJson() throws Exception {
        Properties p = new Properties();
        p.setProperty("ossieYaml", "/opt/saiku/ossie/pharma.yaml");
        p.setProperty("location", "jdbc:h2:tcp://localhost:9092/warehouse");
        p.setProperty("schema", "SALES");
        p.setProperty("username", "admin");
        p.setProperty("password", "hidden");
        p.setProperty("id", "abc-123");
        SaikuDatasource ds = new SaikuDatasource("SALES", SaikuDatasource.Type.OSSIE, p);
        DataSourceMapper mapper = new DataSourceMapper(ds);
        String out = json.writeValueAsString(mapper);

        // Field-by-field: assert the JSON captures the OSSIE-specific bits so the admin
        // UI can populate its form with the right values.
        assertTrue(out.contains("\"connectiontype\":\"OSSIE\""));
        assertTrue(out.contains("\"ossieYaml\":\"/opt/saiku/ossie/pharma.yaml\""));
        assertTrue(out.contains("\"jdbcurl\":\"jdbc:h2:tcp://localhost:9092/warehouse\""));
        assertTrue(out.contains("\"schema\":\"SALES\""));
        // WRITE_ONLY password never round-trips out on GET — must be absent from the
        // serialized JSON so the credential never leaks back to the client.
        assertTrue("password must not appear in serialized JSON (WRITE_ONLY)", !out.contains("\"password\":"));
    }

    @Test
    public void ossieMissingYamlPathRejected() throws Exception {
        String body = "{\"connectionname\":\"SALES\",\"connectiontype\":\"OSSIE\"}";
        DataSourceMapper mapped = json.readValue(body, DataSourceMapper.class);
        try {
            mapped.toSaikuDataSource();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(
                    "message should mention ossieYaml — got: " + e.getMessage(),
                    e.getMessage().contains("ossieYaml"));
        }
    }

    @Test
    public void ossieHonoursJdbcUrlValidator() throws Exception {
        // JdbcUrlValidator rejects H2 INIT/RUNSCRIPT/ALIAS URLs. The Ossie branch must run
        // the same guard on its warehouse URL — else an admin-typed URL could smuggle in an
        // RCE payload.
        String yamlPath = new File(modelDir, "bad.ossie.yaml").getAbsolutePath();
        String body = "{" + "\"connectionname\":\"BAD\"," + "\"connectiontype\":\"OSSIE\","
                + "\"ossieYaml\":\"" + yamlPath + "\","
                + "\"jdbcurl\":\"jdbc:h2:mem:x;INIT=CREATE ALIAS shell AS $$...$$\""
                + "}";
        DataSourceMapper mapped = json.readValue(body, DataSourceMapper.class);
        try {
            mapped.toSaikuDataSource();
            fail("expected validator to reject H2 INIT payload");
        } catch (RuntimeException e) {
            // JdbcUrlValidator throws its own exception; we just assert something was raised.
            assertNotNull(e.getMessage());
        }
    }

    @Test
    public void ossieRoundTripsEqualityFieldByField() throws Exception {
        String yamlPath = new File(modelDir, "round.ossie.yaml").getAbsolutePath();
        Properties p = new Properties();
        p.setProperty("ossieYaml", yamlPath);
        p.setProperty("location", "jdbc:h2:mem:round");
        p.setProperty("schema", "SEM");
        p.setProperty("username", "u");
        p.setProperty("password", "p");
        p.setProperty("id", "id-1");
        SaikuDatasource seed = new SaikuDatasource("SEM", SaikuDatasource.Type.OSSIE, p);
        DataSourceMapper mapper = new DataSourceMapper(seed);
        String out = json.writeValueAsString(mapper);
        // Password is WRITE_ONLY so it's stripped from GET responses; re-add it as the
        // client would on an update POST so the round-trip preserves the value.
        String reinjected = out.replaceFirst("\\{", "{\"password\":\"p\",");
        DataSourceMapper roundTripped = json.readValue(reinjected, DataSourceMapper.class);
        SaikuDatasource redirected = roundTripped.toSaikuDataSource();
        assertEquals(seed.getName(), redirected.getName());
        assertEquals(seed.getType(), redirected.getType());
        assertEquals(yamlPath, redirected.getProperties().getProperty("ossieYaml"));
        assertEquals("jdbc:h2:mem:round", redirected.getProperties().getProperty("location"));
        assertEquals("SEM", redirected.getProperties().getProperty("schema"));
        assertEquals("u", redirected.getProperties().getProperty("username"));
        assertEquals("p", redirected.getProperties().getProperty("password"));
        assertNull(
                "advanced=false is set for the OSSIE branch to distinguish from CSV/advanced flow",
                redirected.getProperties().getProperty("csv"));
    }
}
