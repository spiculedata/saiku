/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.rest.resources.cubedesigner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.datasources.connection.ISaikuConnection;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.web.rest.resources.cubedesigner.CubeDesignerResource.IntrospectResult;
import org.saiku.web.rest.resources.cubedesigner.CubeDesignerResource.SampleResult;
import org.saiku.web.rest.resources.cubedesigner.CubeDesignerResource.SchemaResult;
import org.saiku.web.rest.resources.cubedesigner.CubeDesignerResource.TableView;
import org.saiku.web.rest.resources.schemagen.DatasourceJdbcConnectionProvider;

/**
 * End-to-end tests for {@link CubeDesignerResource} against a real H2 in-memory DB. The datasource
 * service is mocked to hand back a {@link SaikuDatasource} whose location is the H2 JDBC URL, so the
 * production {@link DatasourceJdbcConnectionProvider} resolves a live connection exactly as it would
 * against a real Saiku datasource. userService is left null → the admin guard no-ops (headless).
 */
public class CubeDesignerResourceTest {

    private static final String JDBC_URL = "jdbc:h2:mem:cube-designer;DB_CLOSE_DELAY=-1";
    private static final String DATA_SOURCE_ID = "test-ds";

    private Connection seedConn;
    private CubeDesignerResource resource;

    @Before
    public void setUp() throws Exception {
        seedConn = DriverManager.getConnection(JDBC_URL, "sa", "");
        try (Statement st = seedConn.createStatement()) {
            st.execute("DROP ALL OBJECTS");
            st.execute("CREATE SCHEMA test");
            st.execute("CREATE TABLE test.customer (id INT PRIMARY KEY, name VARCHAR(64))");
            st.execute("INSERT INTO test.customer VALUES (1, 'alice')");
            st.execute("INSERT INTO test.customer VALUES (2, 'bob')");
        }

        Properties props = new Properties();
        props.setProperty(ISaikuConnection.URL_KEY, JDBC_URL);
        props.setProperty(ISaikuConnection.USERNAME_KEY, "sa");
        props.setProperty(ISaikuConnection.PASSWORD_KEY, "");
        SaikuDatasource ds = new SaikuDatasource(DATA_SOURCE_ID, SaikuDatasource.Type.OLAP, props);

        StubDatasourceService datasourceService = new StubDatasourceService(DATA_SOURCE_ID, ds);
        DatasourceJdbcConnectionProvider provider = new DatasourceJdbcConnectionProvider(datasourceService);
        resource = new CubeDesignerResource(provider, datasourceService);
    }

    /** Hand-rolled stub (house pattern — saiku-web has no Mockito), serving one datasource by id
     *  plus an optional in-memory repository (path → file content) for the schema endpoint. */
    private static final class StubDatasourceService extends DatasourceService {
        private final String id;
        private final SaikuDatasource ds;
        private final Map<String, String> repoFiles;

        StubDatasourceService(String id, SaikuDatasource ds) {
            this(id, ds, Map.of());
        }

        StubDatasourceService(String id, SaikuDatasource ds, Map<String, String> repoFiles) {
            this.id = id;
            this.ds = ds;
            this.repoFiles = repoFiles;
        }

        @Override
        public SaikuDatasource getDatasource(String datasourceName) {
            return id.equals(datasourceName) ? ds : null;
        }

        @Override
        public String getInternalFileData(String path) {
            return repoFiles.get(path);
        }
    }

    /** Build a resource whose single datasource has the given Mondrian location + optional repo. */
    private static CubeDesignerResource resourceFor(String location, Map<String, String> repoFiles) {
        Properties props = new Properties();
        props.setProperty(ISaikuConnection.URL_KEY, location);
        props.setProperty(ISaikuConnection.USERNAME_KEY, "sa");
        props.setProperty(ISaikuConnection.PASSWORD_KEY, "");
        SaikuDatasource ds = new SaikuDatasource(DATA_SOURCE_ID, SaikuDatasource.Type.OLAP, props);
        StubDatasourceService svc = new StubDatasourceService(DATA_SOURCE_ID, ds, repoFiles);
        return new CubeDesignerResource(new DatasourceJdbcConnectionProvider(svc), svc);
    }

    @After
    public void tearDown() throws Exception {
        if (seedConn != null && !seedConn.isClosed()) {
            seedConn.close();
        }
    }

    // -- (1) introspect ---------------------------------------------------------

    @Test
    public void introspect_returnsSeededTableAndColumns() {
        Response r = resource.introspect(DATA_SOURCE_ID);
        assertEquals(200, r.getStatus());
        IntrospectResult body = (IntrospectResult) r.getEntity();
        assertNotNull(body);
        TableView customer = body.tables().stream()
                .filter(t -> "CUSTOMER".equalsIgnoreCase(t.name()))
                .findFirst()
                .orElse(null);
        assertNotNull("CUSTOMER table should be introspected", customer);
        List<String> colNames =
                customer.columns().stream().map(c -> c.name().toUpperCase()).toList();
        assertTrue(colNames.contains("ID"));
        assertTrue(colNames.contains("NAME"));
    }

    // -- (2) sample -------------------------------------------------------------

    @Test
    public void sample_returnsColumnsAndRows() {
        Response r = resource.sample(DATA_SOURCE_ID, "CUSTOMER", "TEST", 10);
        assertEquals(200, r.getStatus());
        SampleResult body = (SampleResult) r.getEntity();
        assertNotNull(body);
        assertEquals(2, body.columns().size()); // ID, NAME
        assertEquals(2, body.rows().size()); // alice, bob
    }

    @Test
    public void sample_capsRowsViaLimit() {
        Response r = resource.sample(DATA_SOURCE_ID, "CUSTOMER", "TEST", 1);
        assertEquals(200, r.getStatus());
        SampleResult body = (SampleResult) r.getEntity();
        assertEquals(1, body.rows().size());
    }

    @Test
    public void sample_acceptsSchemaQualifiedTable() {
        // The designer sends "schema.table"; the endpoint splits it when no schema param is given.
        Response r = resource.sample(DATA_SOURCE_ID, "TEST.CUSTOMER", null, 10);
        assertEquals(200, r.getStatus());
        SampleResult body = (SampleResult) r.getEntity();
        assertEquals(2, body.rows().size());
    }

    @Test
    public void sample_missingTableIs400() {
        Response r = resource.sample(DATA_SOURCE_ID, null, null, 10);
        assertEquals(400, r.getStatus());
    }

    // -- (3) schema (edit mode) -------------------------------------------------

    @Test
    public void schema_readsExternalFileCatalog() throws Exception {
        // The launcher's foodmart/bank pattern: Catalog=file:/abs/path/Schema.xml
        Path tmp = Files.createTempFile("cube-designer-schema", ".xml");
        tmp.toFile().deleteOnExit();
        String xml = "<Schema name=\"FoodMart\"/>";
        Files.writeString(tmp, xml, StandardCharsets.UTF_8);
        String location =
                "jdbc:mondrian:Jdbc=" + JDBC_URL + ";MODE=MySQL;Catalog=file:" + tmp + ";JdbcDrivers=org.h2.Driver";

        Response r = resourceFor(location, Map.of()).schema(DATA_SOURCE_ID);
        assertEquals(200, r.getStatus());
        SchemaResult body = (SchemaResult) r.getEntity();
        assertEquals(xml, body.mondrianXml());
        assertEquals(DATA_SOURCE_ID, body.label());
    }

    @Test
    public void schema_readsRepositoryCatalog() {
        // A UI-created datasource: Catalog=mondrian://Name → /datasources/Name.xml in the repo.
        String xml = "<Schema name=\"Sales\"/>";
        String location = "jdbc:mondrian:Jdbc=" + JDBC_URL + ";Catalog=mondrian://Sales;JdbcDrivers=org.h2.Driver";

        Response r =
                resourceFor(location, Map.of("/datasources/Sales.xml", xml)).schema(DATA_SOURCE_ID);
        assertEquals(200, r.getStatus());
        SchemaResult body = (SchemaResult) r.getEntity();
        assertEquals(xml, body.mondrianXml());
    }

    @Test
    public void schema_noCatalogIs404() {
        // Plain JDBC datasource (no Mondrian wrapper) → new-cube target, host stays blank.
        Response r = resourceFor(JDBC_URL, Map.of()).schema(DATA_SOURCE_ID);
        assertEquals(404, r.getStatus());
    }

    @Test
    public void schema_missingRepositoryFileIs404() {
        String location = "jdbc:mondrian:Jdbc=" + JDBC_URL + ";Catalog=mondrian://Absent;JdbcDrivers=org.h2.Driver";
        Response r = resourceFor(location, Map.of()).schema(DATA_SOURCE_ID);
        assertEquals(404, r.getStatus());
    }

    @Test
    public void schema_unknownDatasourceIs404() {
        Response r = resource.schema("no-such-ds");
        assertEquals(404, r.getStatus());
    }

    // -- (4) convert ------------------------------------------------------------

    @Test
    public void convert_missingXmlIs400() {
        Response r = resource.convert(new CubeDesignerResource.ConvertRequest("  ", DATA_SOURCE_ID));
        assertEquals(400, r.getStatus());
    }

    @Test
    public void convert_unknownDatasourceIs404() {
        Response r = resource.convert(new CubeDesignerResource.ConvertRequest("<Schema name=\"x\"/>", "no-such-ds"));
        assertEquals(404, r.getStatus());
    }
}
