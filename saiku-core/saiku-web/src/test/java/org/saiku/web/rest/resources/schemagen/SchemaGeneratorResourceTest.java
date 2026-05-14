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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.schema.generate.apply.OpApplier;
import org.saiku.service.schema.generate.enrich.LlmEnricher;
import org.saiku.service.schema.generate.enrich.PiiFilter;
import org.saiku.service.schema.generate.enrich.SuggestionSet;
import org.saiku.service.schema.generate.enrich.ops.RenameOp;
import org.saiku.service.schema.generate.enrich.ops.SuggestionOp;
import org.saiku.service.schema.generate.enrich.provider.NoopProvider;
import org.saiku.service.schema.generate.infer.SchemaInferrer;
import org.saiku.service.schema.generate.introspect.JdbcIntrospector;
import org.saiku.service.schema.generate.session.SchemaGenOrchestrator;
import org.saiku.service.schema.generate.session.SchemaGenSession;
import org.saiku.service.schema.generate.session.SchemaGenSessionStore;
import org.saiku.service.schema.generate.writer.GeneratedSidecar;
import org.saiku.service.schema.generate.writer.GeneratedSidecarIo;
import org.saiku.service.schema.generate.writer.MondrianSchemaWriter;

/**
 * End-to-end tests for {@link SchemaGeneratorResource}. Uses a real H2 in-memory DB via the
 * orchestrator's {@link SchemaGenOrchestrator.ConnectionProvider} and direct-method invocation (no
 * servlet container) against a hand-wired resource.
 */
public class SchemaGeneratorResourceTest {

    private static final String JDBC_URL = "jdbc:h2:mem:schemagen-resource;DB_CLOSE_DELAY=-1";
    private static final String DATA_SOURCE_ID = "test-ds";

    private Connection seedConn;
    private SchemaGeneratorResource resource;
    private RecordingSchemaSink sink;

    @Before
    public void setUp() throws Exception {
        seedConn = DriverManager.getConnection(JDBC_URL, "sa", "");
        try (Statement st = seedConn.createStatement()) {
            st.execute("DROP ALL OBJECTS");
            st.execute("CREATE SCHEMA test");
            st.execute("CREATE TABLE test.customer (id INT PRIMARY KEY, name VARCHAR(64))");
            st.execute("CREATE TABLE test.product  (id INT PRIMARY KEY, name VARCHAR(64))");
            st.execute("CREATE TABLE test.orders ("
                    + " id INT PRIMARY KEY,"
                    + " customer_id INT,"
                    + " product_id INT,"
                    + " order_date DATE,"
                    + " amount DECIMAL(10,2),"
                    + " qty INT,"
                    + " FOREIGN KEY (customer_id) REFERENCES test.customer(id),"
                    + " FOREIGN KEY (product_id)  REFERENCES test.product(id))");
            st.execute("INSERT INTO test.customer VALUES (1, 'alice')");
            st.execute("INSERT INTO test.product  VALUES (1, 'widget')");
            st.execute("INSERT INTO test.orders"
                    + " SELECT X, 1, 1, DATE '2024-01-01', 9.99, 1 FROM SYSTEM_RANGE(1, 1500)");
        }

        SchemaGenSessionStore store = new SchemaGenSessionStore();
        JdbcIntrospector introspector = new JdbcIntrospector(new JdbcIntrospector.Options().withSchemaPattern("TEST"));
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

    // -- 1. POST /start returns 202 + sessionId ---------------------------------

    @Test
    public void start_returns202WithSessionAndStage() {
        Response r = resource.start(DATA_SOURCE_ID);
        assertEquals(202, r.getStatus());
        StartResponse body = (StartResponse) r.getEntity();
        assertNotNull(body);
        assertNotNull(body.sessionId());
        assertEquals(DATA_SOURCE_ID, body.dataSourceId());
        assertNotNull(body.stage());
    }

    // -- 2. GET /status after the sync pipeline ran returns READY ---------------

    @Test
    public void status_returnsReadyAfterPipeline() {
        StartResponse started = (StartResponse) resource.start(DATA_SOURCE_ID).getEntity();

        Response r = resource.status(started.sessionId());
        assertEquals(200, r.getStatus());
        StatusResponse body = (StatusResponse) r.getEntity();
        assertEquals(started.sessionId(), body.sessionId());
        assertEquals(SchemaGenSession.Stage.READY.name(), body.stage());
        assertNull(body.failureMessage());
        assertTrue("expected at least one cube", body.cubeCount() >= 1);
    }

    // -- 3. GET /draft returns a populated tree ---------------------------------

    @Test
    public void draft_returnsDraftView() {
        StartResponse started = (StartResponse) resource.start(DATA_SOURCE_ID).getEntity();

        Response r = resource.draft(started.sessionId());
        assertEquals(200, r.getStatus());
        DraftView v = (DraftView) r.getEntity();
        assertNotNull(v);
        assertNotNull(v.schemaName());
        assertFalse("expected at least one cube", v.cubes().isEmpty());
    }

    // -- 4. POST /ops applies a RenameOp and returns updated DraftView ----------

    @Test
    public void ops_rename_isAppliedAndReflectedInDraft() {
        StartResponse started = (StartResponse) resource.start(DATA_SOURCE_ID).getEntity();
        DraftView before = (DraftView) resource.draft(started.sessionId()).getEntity();
        assertFalse(before.cubes().isEmpty());
        String cubeName = before.cubes().get(0).name();

        SuggestionOp op =
                new RenameOp("cubes/" + cubeName, cubeName, "Renamed Cube", /* description */ null, 1.0, "test");

        Response r = resource.applyOp(started.sessionId(), new OpRequest(op));
        assertEquals(200, r.getStatus());
        DraftView after = (DraftView) r.getEntity();
        boolean found = false;
        for (DraftView.CubeView c : after.cubes()) {
            if ("Renamed Cube".equals(c.name())) {
                found = true;
                break;
            }
        }
        assertTrue("rename should be reflected in returned draft", found);
    }

    // -- 5. POST /save → 204, writer invoked, stage SAVED ------------------------

    @Test
    public void save_writesSchemaAndMarksSessionSaved() {
        StartResponse started = (StartResponse) resource.start(DATA_SOURCE_ID).getEntity();

        Response r = resource.save(started.sessionId(), null);
        assertEquals(204, r.getStatus());

        assertEquals(1, sink.writes.size());
        RecordingSchemaSink.Write w = sink.writes.get(0);
        assertTrue("xml should look like a Mondrian schema", w.xml.contains("<Schema"));
        assertNotNull(w.name);
        assertNotNull("sidecar JSON should be emitted on save", w.sidecarJson);
        assertFalse("sidecar JSON should not be empty", w.sidecarJson.isEmpty());
        // Sidecar should parse back through the canonical reader and carry the draft tree.
        GeneratedSidecar parsed = GeneratedSidecarIo.read(w.sidecarJson);
        assertNotNull(parsed.draft());
        assertEquals(w.name, parsed.schemaName());
        assertNotNull(parsed.opLog());
    }

    // -- 5b. Save after applying an op records that op in the sidecar opLog -----

    @Test
    public void save_sidecarCapturesAppliedOps() {
        StartResponse started = (StartResponse) resource.start(DATA_SOURCE_ID).getEntity();
        DraftView before = (DraftView) resource.draft(started.sessionId()).getEntity();
        String cubeName = before.cubes().get(0).name();
        SuggestionOp op = new RenameOp("cubes/" + cubeName, cubeName, "Renamed Cube", null, 1.0, "test");
        resource.applyOp(started.sessionId(), new OpRequest(op));

        Response r = resource.save(started.sessionId(), null);
        assertEquals(204, r.getStatus());

        RecordingSchemaSink.Write w = sink.writes.get(0);
        GeneratedSidecar parsed = GeneratedSidecarIo.read(w.sidecarJson);
        assertEquals(1, parsed.opLog().size());
        assertTrue(parsed.opLog().get(0) instanceof RenameOp);
    }

    // -- 6. GET /draft with unknown session returns 404 -------------------------

    @Test
    public void draft_unknownSession_returns404() {
        Response r = resource.draft("no-such-session");
        assertEquals(404, r.getStatus());
    }

    // -- 7. GET /suggestions returns a SuggestionView ---------------------------

    @Test
    public void suggestions_returnView() {
        StartResponse started = (StartResponse) resource.start(DATA_SOURCE_ID).getEntity();
        Response r = resource.suggestions(started.sessionId());
        assertEquals(200, r.getStatus());
        SuggestionView v = (SuggestionView) r.getEntity();
        assertNotNull(v);
        assertNotNull(v.suggestions());
        // NoopProvider produces an empty, non-degraded set
        assertFalse(v.suggestions().degraded());
    }

    // -- helpers ---------------------------------------------------------------

    /** Captures schema-XML + sidecar writes for assertions. */
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

    /** Suppresses unused-field warnings for {@link SuggestionSet}. */
    @SuppressWarnings("unused")
    private static void pin(SuggestionSet ignored) {}
}
