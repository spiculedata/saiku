/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.rest.resources.schemagen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.olap4j.CellSet;
import org.olap4j.OlapConnection;
import org.olap4j.OlapStatement;
import org.saiku.service.schema.generate.apply.OpApplier;
import org.saiku.service.schema.generate.enrich.LlmEnricher;
import org.saiku.service.schema.generate.enrich.PiiFilter;
import org.saiku.service.schema.generate.enrich.ops.SuggestionOp;
import org.saiku.service.schema.generate.enrich.provider.NoopProvider;
import org.saiku.service.schema.generate.infer.SchemaInferrer;
import org.saiku.service.schema.generate.introspect.JdbcIntrospector;
import org.saiku.service.schema.generate.session.SchemaGenOrchestrator;
import org.saiku.service.schema.generate.session.SchemaGenSessionStore;
import org.saiku.service.schema.generate.writer.MondrianSchemaWriter;

/**
 * End-to-end happy-path integration test for the schema-generation pipeline.
 *
 * <p>Boots a Foodmart-mini star-schema in H2 (one fact + two dim tables), drives the resource
 * through its {@code start → status → draft → applyOp* → save} lifecycle, captures the generated
 * Mondrian XML via a {@link SchemaGeneratorResource.SchemaSink}, and executes a real MDX query
 * against a live Mondrian/olap4j connection configured with the generated schema as
 * {@code CatalogContent}.
 *
 * <p>The {@code Fact Count} measure is reliably emitted by the inferrer's rule layer for every
 * fact table, so we assert on it — it's the most robust smoke signal for "pipeline produced a
 * queryable cube."
 */
public class FoodmartHappyPathIT {

    private static final String JDBC_URL = "jdbc:h2:mem:schemagen-foodmart-it;DB_CLOSE_DELAY=-1";
    private static final String DATA_SOURCE_ID = "foodmart-it";
    private static final int EXPECTED_ROWS = 1500;

    private Connection seedConn;
    private SchemaGeneratorResource resource;
    private RecordingSchemaSink sink;

    @Before
    public void setUp() throws Exception {
        // Ensure drivers are registered before Mondrian tries to resolve them through its own
        // classloader (JdbcDrivers=... is load-or-throw).
        Class.forName("org.h2.Driver");
        Class.forName("mondrian.olap4j.MondrianOlap4jDriver");

        seedConn = DriverManager.getConnection(JDBC_URL, "sa", "");
        try (Statement st = seedConn.createStatement()) {
            st.execute("DROP ALL OBJECTS");
            // Seed the default PUBLIC schema so Mondrian can resolve table names without a schema
            // qualifier — the writer emits bare table names.
            st.execute("CREATE TABLE customer (id INT PRIMARY KEY, name VARCHAR(64))");
            st.execute("CREATE TABLE product  (id INT PRIMARY KEY, name VARCHAR(64))");
            // Sales fact includes an order_date column — the inferrer turns it into a degenerate
            // TIME dim whose Y/Q/M/D attributes bind to CalculatedColumnDef entries on the fact
            // (YEAR/QUARTER/MONTH/DAY expressions). This exercises the fix that retired the
            // phantom shared-Time table.
            st.execute("CREATE TABLE sales ("
                    + " id INT PRIMARY KEY,"
                    + " customer_id INT,"
                    + " product_id INT,"
                    + " order_date DATE,"
                    + " amount DECIMAL(10,2),"
                    + " qty INT,"
                    + " FOREIGN KEY (customer_id) REFERENCES customer(id),"
                    + " FOREIGN KEY (product_id)  REFERENCES product(id))");
            st.execute("INSERT INTO customer VALUES (1, 'alice'), (2, 'bob')");
            st.execute("INSERT INTO product  VALUES (1, 'widget'), (2, 'sprocket')");
            st.execute("INSERT INTO sales"
                    + " SELECT X, 1 + MOD(X,2), 1 + MOD(X,2),"
                    + " DATEADD('DAY', MOD(X, 365), DATE '2024-01-01'),"
                    + " 9.99, 1"
                    + " FROM SYSTEM_RANGE(1, " + EXPECTED_ROWS + ")");
        }

        SchemaGenSessionStore store = new SchemaGenSessionStore();
        JdbcIntrospector introspector =
                new JdbcIntrospector(new JdbcIntrospector.Options().withSchemaPattern("PUBLIC"));
        SchemaInferrer inferrer = new SchemaInferrer();
        LlmEnricher enricher = new LlmEnricher(new NoopProvider(), new PiiFilter());
        Executor synchronous = Runnable::run;
        SchemaGenOrchestrator.ConnectionProvider connProvider = id -> DriverManager.getConnection(JDBC_URL, "sa", "");
        SchemaGenOrchestrator orchestrator =
                new SchemaGenOrchestrator(store, introspector, inferrer, enricher, synchronous, connProvider);

        sink = new RecordingSchemaSink();
        resource = new SchemaGeneratorResource(store, orchestrator, new OpApplier(), new MondrianSchemaWriter(), sink);
    }

    @After
    public void tearDown() throws Exception {
        if (seedConn != null && !seedConn.isClosed()) {
            seedConn.close();
        }
    }

    @Test
    public void startPollSuggestionsSaveAndQueryFactCount() throws Exception {
        // 1. Start.
        Response startResp = resource.start(DATA_SOURCE_ID);
        assertEquals(202, startResp.getStatus());
        StartResponse started = (StartResponse) startResp.getEntity();
        assertNotNull(started.sessionId());

        // 2. Poll status — synchronous executor means it's already READY on return.
        Response statusResp = resource.status(started.sessionId());
        assertEquals(200, statusResp.getStatus());
        StatusResponse status = (StatusResponse) statusResp.getEntity();
        assertEquals("READY", status.stage());
        assertTrue("expected at least one cube", status.cubeCount() >= 1);

        // 3. Draft must include a SALES cube with a Fact Count measure.
        DraftView draft = (DraftView) resource.draft(started.sessionId()).getEntity();
        assertNotNull(draft);
        assertFalse("expected at least one cube", draft.cubes().isEmpty());
        DraftView.CubeView salesCube = findCube(draft, "SALES");
        assertNotNull("SALES cube missing from draft", salesCube);
        boolean hasFactCount = salesCube.measures().stream().anyMatch(m -> "Fact Count".equals(m.name()));
        assertTrue("expected rule-derived Fact Count measure on SALES", hasFactCount);

        // 4. Walk the suggestion set. Applying renames mutates element names, which invalidates
        //    the path of any later op in the batch that targets a descendant of the renamed node
        //    (OpApplier resolves paths by current name). That's a known v1 limitation: stale-path
        //    400s are tolerated here so the happy-path test doesn't depend on op ordering, but we
        //    still exercise the apply path and require at least one op to land so we know the
        //    flow is wired up when suggestions are present.
        SuggestionView suggestions =
                (SuggestionView) resource.suggestions(started.sessionId()).getEntity();
        int applied = 0;
        int offered = 0;
        if (suggestions != null && suggestions.suggestions() != null) {
            for (SuggestionOp op : suggestions.suggestions().ops()) {
                offered++;
                Response opResp = resource.applyOp(started.sessionId(), new OpRequest(op));
                if (opResp.getStatus() == 200) {
                    applied++;
                }
            }
        }
        if (offered > 0) {
            assertTrue("expected at least one suggestion to apply cleanly", applied > 0);
        }

        // 5. Resolve the final cube name (renames may have retitled SALES → Sales) before save,
        //    because the generated XML will carry whichever name is current.
        DraftView finalDraft = (DraftView) resource.draft(started.sessionId()).getEntity();
        assertFalse(finalDraft.cubes().isEmpty());
        String cubeName = finalDraft.cubes().get(0).name();

        // 6. Save — triggers the writer and SchemaSink.
        Response saveResp = resource.save(started.sessionId(), null);
        assertEquals(204, saveResp.getStatus());
        assertEquals(1, sink.writes.size());
        RecordingSchemaSink.Write write = sink.writes.get(0);
        String xml = write.xml;
        assertNotNull(xml);
        assertTrue("xml should be a Mondrian schema", xml.contains("<Schema"));
        assertTrue("xml should reference cube " + cubeName, xml.contains("name=\"" + cubeName + "\""));

        // 7. Boot a Mondrian olap4j connection over the generated schema and run MDX.
        //    The fact-count SQL is executed by Mondrian against the same in-memory H2 DB.
        double factCount = runFactCountMdx(xml, cubeName);
        assertEquals((double) EXPECTED_ROWS, factCount, 0.0001);

        // Sanity-check a measure that reads fact-column values (1500 rows × 9.99).
        double sumAmount = runMeasureMdx(xml, cubeName, "AMOUNT");
        assertEquals(EXPECTED_ROWS * 9.99, sumAmount, 0.01);
    }

    // --- helpers ------------------------------------------------------------

    private static DraftView.CubeView findCube(DraftView v, String name) {
        for (DraftView.CubeView c : v.cubes()) {
            if (name.equals(c.name())) {
                return c;
            }
        }
        return null;
    }

    /**
     * Open a Mondrian olap4j connection with {@code xml} passed inline via {@code CatalogContent}
     * and return the numeric value of {@code [Measures].[Fact Count]} on the first cell.
     */
    private static double runMeasureMdx(String xml, String cubeName, String measureName) throws Exception {
        return runMdx(xml, "SELECT [Measures].[" + measureName + "] ON 0 FROM [" + cubeName + "]");
    }

    private static double runFactCountMdx(String xml, String cubeName) throws Exception {
        return runMdx(xml, "SELECT [Measures].[Fact Count] ON 0 FROM [" + cubeName + "]");
    }

    private static double runMdx(String xml, String mdx) throws Exception {
        // Mondrian CatalogContent is parsed as-is — escape embedded quotes and strip the XML
        // prolog (Mondrian gets upset about <?xml ?> inside a properties value on some versions).
        String catalog = xml;
        if (catalog.startsWith("<?xml")) {
            int end = catalog.indexOf("?>");
            if (end > 0) {
                catalog = catalog.substring(end + 2).trim();
            }
        }

        // MondrianOlap4jDriver accepts the connection string via java.util.Properties, which side-
        // steps any need to URL-encode the multi-line catalog body.
        Properties info = new Properties();
        info.setProperty("Jdbc", JDBC_URL);
        info.setProperty("JdbcDrivers", "org.h2.Driver");
        info.setProperty("JdbcUser", "sa");
        info.setProperty("JdbcPassword", "");
        info.setProperty("CatalogContent", catalog);

        try (Connection raw = DriverManager.getConnection("jdbc:mondrian:", info)) {
            OlapConnection olap = raw.unwrap(OlapConnection.class);
            try (OlapStatement st = olap.createStatement();
                    CellSet cs = st.executeOlapQuery(mdx)) {
                Object v = cs.getCell(0).getValue();
                assertNotNull("cell was null for " + mdx, v);
                return ((Number) v).doubleValue();
            }
        }
    }

    // --- recording sink -----------------------------------------------------

    static final class RecordingSchemaSink implements SchemaGeneratorResource.SchemaSink {
        static final class Write {
            final String dataSourceId;
            final String name;
            final String xml;
            final String sidecarJson;

            Write(String dataSourceId, String name, String xml, String sidecarJson) {
                this.dataSourceId = dataSourceId;
                this.name = name;
                this.xml = xml;
                this.sidecarJson = sidecarJson;
            }
        }

        final List<Write> writes = new ArrayList<>();

        @Override
        public void writeSchema(String dataSourceId, String name, String xml, String sidecarJson) {
            writes.add(new Write(dataSourceId, name, xml, sidecarJson));
        }
    }
}
