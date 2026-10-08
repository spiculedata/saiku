/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.rest.resources.schemagen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.ossie.generate.GeneratedModelStore;
import org.saiku.service.ossie.generate.OssieGenerationJobStore;
import org.saiku.service.ossie.generate.OssieModelGenerationService;
import org.saiku.service.schema.generate.apply.OpApplier;
import org.saiku.service.schema.generate.enrich.LlmEnricher;
import org.saiku.service.schema.generate.enrich.PiiFilter;
import org.saiku.service.schema.generate.enrich.provider.NoopProvider;
import org.saiku.service.schema.generate.infer.SchemaInferrer;
import org.saiku.service.schema.generate.introspect.JdbcIntrospector;
import org.saiku.service.schema.generate.session.SchemaGenOrchestrator;
import org.saiku.service.schema.generate.writer.MondrianSchemaWriter;
import org.saiku.service.user.UserService;

/**
 * End-to-end tests for {@link OssieGenerateResource} — a real H2 warehouse, a real generation
 * service, direct method invocation (no servlet container), and a hand-wired resource.
 *
 * <p>Deliberately not a stubbed service: the 202-then-poll handshake only means something if the
 * job a client polls is the one a real run actually completes, and that is exactly the wiring a
 * mock would paper over.
 */
public class OssieGenerateResourceTest {

    private static final String JDBC_URL = "jdbc:h2:mem:ossie-gen-resource;DB_CLOSE_DELAY=-1";
    private static final String DATA_SOURCE_ID = "test-ds";

    private Connection seedConn;
    private String catalog;
    private OssieGenerateResource resource;
    private RecordingModelStore modelStore;

    @Before
    public void setUp() throws Exception {
        seedConn = DriverManager.getConnection(JDBC_URL, "sa", "");
        catalog = seedConn.getCatalog();
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
                    + " FOREIGN KEY (customer_id) REFERENCES test.customer(id),"
                    + " FOREIGN KEY (product_id)  REFERENCES test.product(id))");
            st.execute("INSERT INTO test.customer VALUES (1, 'alice')");
            st.execute("INSERT INTO test.product  VALUES (1, 'widget')");
            // The classifier needs >= 1000 rows and >= 2 outgoing FKs to call this a fact.
            st.execute(
                    "INSERT INTO test.orders" + " SELECT X, 1, 1, DATE '2024-01-01', 9.99 FROM SYSTEM_RANGE(1, 1500)");
        }
        modelStore = new RecordingModelStore();
        OssieGenerationJobStore jobStore = new OssieGenerationJobStore();
        // Runnable::run keeps the pipeline synchronous so the assertions don't race the executor.
        OssieModelGenerationService service = new OssieModelGenerationService(
                jobStore,
                new JdbcIntrospector(
                        new JdbcIntrospector.Options().withCatalog(catalog).withSchemaPattern("TEST")),
                new SchemaInferrer(),
                new LlmEnricher(new NoopProvider(), new PiiFilter()),
                new OpApplier(),
                new MondrianSchemaWriter(),
                modelStore,
                SchemaGenOrchestrator.SidecarStore.empty(),
                Runnable::run,
                dsId -> DriverManager.getConnection(JDBC_URL, "sa", ""));
        resource = new OssieGenerateResource(service, jobStore);
    }

    @After
    public void tearDown() throws Exception {
        if (seedConn != null) {
            try (Statement st = seedConn.createStatement()) {
                st.execute("DROP ALL OBJECTS");
            }
            seedConn.close();
        }
    }

    // ------------------------------------------------------------------
    // POST
    // ------------------------------------------------------------------

    @Test
    public void startReturns202WithAPollableJobId() {
        try (Response r = resource.start(new OssieGenerateResource.GenerateRequest(DATA_SOURCE_ID, "warehouse"))) {
            assertEquals(202, r.getStatus());

            OssieGenerateResource.GenerateStartResponse body =
                    (OssieGenerateResource.GenerateStartResponse) r.getEntity();
            assertNotNull("a job id is the whole point of the 202", body.jobId());
            assertEquals(DATA_SOURCE_ID, body.dataSourceId());
            assertEquals("warehouse", body.modelName());
        }
    }

    @Test
    public void startWithNoModelNameDefaultsToTheDataSourceId() {
        try (Response r = resource.start(new OssieGenerateResource.GenerateRequest(DATA_SOURCE_ID, null))) {
            assertEquals(202, r.getStatus());
            assertEquals(DATA_SOURCE_ID, ((OssieGenerateResource.GenerateStartResponse) r.getEntity()).modelName());
        }
    }

    @Test
    public void startWithNoBodyIs400() {
        try (Response r = resource.start(null)) {
            assertEquals(400, r.getStatus());
        }
        assertTrue("a bad request must not start a run", modelStore.writes.isEmpty());
    }

    @Test
    public void startWithABlankDataSourceIdIs400() {
        try (Response r = resource.start(new OssieGenerateResource.GenerateRequest("  ", "w"))) {
            assertEquals(400, r.getStatus());
        }
        assertTrue("a bad request must not start a run", modelStore.writes.isEmpty());
    }

    @Test
    public void pollingAStartedJobReachesCompletionAndNamesTheArtefacts() {
        String jobId = startJob("warehouse");

        try (Response r = resource.status(jobId)) {
            assertEquals(200, r.getStatus());
            OssieGenerateResource.GenerateStatusResponse b =
                    (OssieGenerateResource.GenerateStatusResponse) r.getEntity();
            assertEquals("COMPLETED", b.stage());
            assertNull("no failure message on success", b.failureMessage());
            assertEquals("/datasources/warehouse.generated.yaml", b.yamlPath());
            assertEquals("/datasources/warehouse.generated.rationale.md", b.rationalePath());
            assertTrue("semantic models emitted", b.semanticModelCount() >= 1);
            assertTrue("metrics emitted", b.metricCount() >= 1);
            assertTrue("decisions reported", b.appliedSuggestionCount() >= 1);
        }

        assertEquals(1, modelStore.writes.size());
        assertTrue("the model was written", modelStore.writes.get(0).yaml.contains("semantic_model:"));
        assertTrue("so was the rationale", modelStore.writes.get(0).rationale.contains("Generation rationale"));
    }

    @Test
    public void statusForAnUnknownJobIs404() {
        try (Response r = resource.status("no-such-job")) {
            assertEquals(404, r.getStatus());
        }
    }

    @Test
    public void aFailedRunIsReportedRatherThanThrown() {
        // Point the resource at a data source that cannot connect. The POST must still be 202 —
        // the failure lands on the job, which is the whole reason the endpoint is async.
        OssieGenerationJobStore jobStore = new OssieGenerationJobStore();
        OssieModelGenerationService failing = new OssieModelGenerationService(
                jobStore,
                new JdbcIntrospector(new JdbcIntrospector.Options().withSchemaPattern("TEST")),
                new SchemaInferrer(),
                new LlmEnricher(new NoopProvider(), new PiiFilter()),
                new OpApplier(),
                new MondrianSchemaWriter(),
                modelStore,
                null,
                Runnable::run,
                dsId -> {
                    throw new java.sql.SQLException("no such data source " + dsId);
                });
        OssieGenerateResource failingResource = new OssieGenerateResource(failing, jobStore);

        String jobId;
        try (Response r = failingResource.start(new OssieGenerateResource.GenerateRequest("missing-ds", "warehouse"))) {
            assertEquals(202, r.getStatus());
            jobId = ((OssieGenerateResource.GenerateStartResponse) r.getEntity()).jobId();
        }

        try (Response r = failingResource.status(jobId)) {
            OssieGenerateResource.GenerateStatusResponse b =
                    (OssieGenerateResource.GenerateStatusResponse) r.getEntity();
            assertEquals("FAILED", b.stage());
            assertNotNull(b.failureMessage());
            assertTrue(b.failureMessage().contains("no such data source"));
        }
        assertTrue("nothing may be written for a failed run", modelStore.writes.isEmpty());
    }

    // ------------------------------------------------------------------
    // admin guard
    // ------------------------------------------------------------------

    @Test
    public void nonAdminIsForbiddenOnStart() {
        resource.setUserService(new FixedUserService(false));

        try (Response r = resource.start(new OssieGenerateResource.GenerateRequest(DATA_SOURCE_ID, "w"))) {
            assertEquals(403, r.getStatus());
        }
        assertTrue("a forbidden start must not kick off a run", modelStore.writes.isEmpty());
    }

    @Test
    public void nonAdminIsForbiddenOnPoll() {
        String jobId = startJob("warehouse");
        resource.setUserService(new FixedUserService(false));

        try (Response r = resource.status(jobId)) {
            assertEquals(403, r.getStatus());
        }
    }

    @Test
    public void adminIsAllowedThrough() {
        resource.setUserService(new FixedUserService(true));

        try (Response r = resource.start(new OssieGenerateResource.GenerateRequest(DATA_SOURCE_ID, "w"))) {
            assertEquals(202, r.getStatus());
        }
    }

    // ------------------------------------------------------------------

    private String startJob(String modelName) {
        try (Response r = resource.start(new OssieGenerateResource.GenerateRequest(DATA_SOURCE_ID, modelName))) {
            return ((OssieGenerateResource.GenerateStartResponse) r.getEntity()).jobId();
        }
    }

    private static final class FixedUserService extends UserService {
        private final boolean admin;

        FixedUserService(boolean admin) {
            this.admin = admin;
        }

        @Override
        public boolean isAdmin() {
            return admin;
        }
    }

    /** In-memory {@link GeneratedModelStore} — the repository layer isn't under test here. */
    private static final class RecordingModelStore implements GeneratedModelStore {
        record Written(String yaml, String rationale) {}

        final List<Written> writes = new ArrayList<>();

        @Override
        public Optional<String> readExisting(String dataSourceId, String modelName) {
            return Optional.empty();
        }

        @Override
        public WrittenPaths pathsFor(String dataSourceId, String modelName) {
            String stem = (modelName == null || modelName.isBlank()) ? dataSourceId : modelName;
            return new WrittenPaths(
                    "/datasources/" + stem + ".generated.yaml", "/datasources/" + stem + ".generated.rationale.md");
        }

        @Override
        public void write(String dataSourceId, String modelName, String yaml, String rationale) {
            writes.add(new Written(yaml, rationale));
        }
    }
}
