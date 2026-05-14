package org.saiku.service.schema.generate.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.schema.generate.draft.DraftSchema;
import org.saiku.service.schema.generate.enrich.LlmEnricher;
import org.saiku.service.schema.generate.enrich.PiiFilter;
import org.saiku.service.schema.generate.enrich.ops.SuggestionOp;
import org.saiku.service.schema.generate.enrich.provider.NoopProvider;
import org.saiku.service.schema.generate.infer.SchemaInferrer;
import org.saiku.service.schema.generate.introspect.JdbcIntrospector;
import org.saiku.service.schema.generate.model.DbModel;
import org.saiku.service.schema.generate.writer.GeneratedSidecar;
import org.saiku.service.schema.generate.writer.GeneratedSidecarIo;

/**
 * Integration test for {@link SchemaGenOrchestrator}. Runs the real introspect -> infer -> enrich
 * pipeline against an in-memory H2 schema with a {@link NoopProvider} LLM backend.
 */
public class SchemaGenOrchestratorTest {

    private static final String URL = "jdbc:h2:mem:schemagen-orch;DB_CLOSE_DELAY=-1";

    private Connection seedConn;

    @Before
    public void setUp() throws Exception {
        seedConn = DriverManager.getConnection(URL, "sa", "");
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
            // classifier requires >= 1000 rows for fact status
            st.execute("INSERT INTO test.orders"
                    + " SELECT X, 1, 1, DATE '2024-01-01', 9.99, 1 FROM SYSTEM_RANGE(1, 1500)");
        }
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

    private SchemaGenOrchestrator.ConnectionProvider h2Connections() {
        return dsId -> DriverManager.getConnection(URL, "sa", "");
    }

    private JdbcIntrospector introspector() throws SQLException {
        return new JdbcIntrospector(new JdbcIntrospector.Options()
                .withCatalog(seedConn.getCatalog())
                .withSchemaPattern("TEST"));
    }

    private LlmEnricher enricher() {
        return new LlmEnricher(new NoopProvider(), new PiiFilter());
    }

    @Test
    public void runsPipelineSynchronouslyWithDirectExecutor() throws Exception {
        SchemaGenSessionStore store = new SchemaGenSessionStore();
        SchemaGenOrchestrator orch = new SchemaGenOrchestrator(
                store, introspector(), new SchemaInferrer(), enricher(), Runnable::run, h2Connections());

        SchemaGenSession session = orch.start("ds-1");

        assertNotNull(session);
        assertEquals(SchemaGenSession.Stage.READY, session.stage());
        assertNotNull("draft populated", session.draft());
        assertFalse("at least one cube inferred", session.draft().cubes().isEmpty());
        assertNotNull("suggestions populated", session.suggestions());
        assertNull("no failure message on success", session.failureMessage());

        Optional<SchemaGenSession> fromStore = store.get(session.id());
        assertTrue(fromStore.isPresent());
        assertEquals(SchemaGenSession.Stage.READY, fromStore.get().stage());
    }

    @Test
    public void runsPipelineOnBackgroundExecutorAndReachesReady() throws Exception {
        SchemaGenSessionStore store = new SchemaGenSessionStore();
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "schemagen-test-exec");
            t.setDaemon(true);
            return t;
        });
        try {
            SchemaGenOrchestrator orch = new SchemaGenOrchestrator(
                    store, introspector(), new SchemaInferrer(), enricher(), exec, h2Connections());

            SchemaGenSession session = orch.start("ds-1");

            SchemaGenSession.Stage finalStage = waitForTerminal(store, session.id(), 5_000);
            assertEquals(SchemaGenSession.Stage.READY, finalStage);
            SchemaGenSession done = store.get(session.id()).orElseThrow();
            assertNotNull(done.draft());
            assertFalse(done.draft().cubes().isEmpty());
            assertNotNull(done.suggestions());
        } finally {
            exec.shutdownNow();
            exec.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    public void failsGracefullyWhenConnectionProviderThrows() throws Exception {
        SchemaGenSessionStore store = new SchemaGenSessionStore();
        SchemaGenOrchestrator.ConnectionProvider boom = dsId -> {
            throw new SQLException("boom: no such data source " + dsId);
        };

        SchemaGenOrchestrator orch =
                new SchemaGenOrchestrator(store, introspector(), new SchemaInferrer(), enricher(), Runnable::run, boom);

        SchemaGenSession session = orch.start("ds-missing");

        assertEquals(SchemaGenSession.Stage.FAILED, session.stage());
        assertNotNull("failure message populated", session.failureMessage());
        assertTrue(
                "failure message mentions the cause: " + session.failureMessage(),
                session.failureMessage().toLowerCase().contains("boom"));
    }

    @Test
    public void rerunWithSidecarRestrictsEnrichmentToNewElements() throws Exception {
        // 1. Build baseline DraftSchema from an earlier DB shape (orders WITHOUT the `discount` column).
        DraftSchema baselineDraft;
        try (Connection baselineConn =
                DriverManager.getConnection("jdbc:h2:mem:schemagen-orch-baseline;DB_CLOSE_DELAY=-1", "sa", "")) {
            try (Statement st = baselineConn.createStatement()) {
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
            JdbcIntrospector baselineIntrospector = new JdbcIntrospector(new JdbcIntrospector.Options()
                    .withCatalog(baselineConn.getCatalog())
                    .withSchemaPattern("TEST"));
            DbModel baselineModel = baselineIntrospector.introspect(baselineConn);
            baselineDraft = new SchemaInferrer().infer(baselineModel);
        }

        GeneratedSidecar sidecar = GeneratedSidecarIo.build(baselineDraft, List.of(), "Main", "test");

        // 2. Mutate the primary H2 DB so `orders` gains a new `discount` column.
        try (Statement st = seedConn.createStatement()) {
            st.execute("ALTER TABLE test.orders ADD COLUMN discount DECIMAL(10,2) DEFAULT 0");
        }

        // 3. Run orchestrator in re-run mode with a SidecarStore returning the baseline.
        SchemaGenSessionStore store = new SchemaGenSessionStore();
        SchemaGenOrchestrator.SidecarStore sidecarStore = dsId -> Optional.of(sidecar);
        SchemaGenOrchestrator orch = new SchemaGenOrchestrator(
                store, introspector(), new SchemaInferrer(), enricher(), Runnable::run, h2Connections(), sidecarStore);

        SchemaGenSession session = orch.start("ds-1");

        assertEquals(SchemaGenSession.Stage.READY, session.stage());
        assertNotNull("deltaReport must be populated on re-run", session.deltaReport());
        assertTrue(
                "new discount measure path must be tagged NEW: "
                        + session.deltaReport().newPaths(),
                session.deltaReport().newPaths().stream()
                        .anyMatch(p -> p.toLowerCase().endsWith("/measures/discount")));
        assertFalse(
                "existing measure amount should be EXISTING",
                session.deltaReport().existingPaths().stream()
                        .noneMatch(p -> p.toLowerCase().endsWith("/measures/amount")));

        // 4. Every suggestion op must address a NEW element (filter applied post-enrichment).
        assertNotNull(session.suggestions());
        java.util.Set<String> newPaths =
                new java.util.HashSet<>(session.deltaReport().newPaths());
        for (SuggestionOp op : session.suggestions().ops()) {
            assertTrue(
                    "op on non-NEW path slipped through filter: "
                            + op.getClass().getSimpleName()
                            + " @ "
                            + op.targetPath(),
                    newPaths.contains(op.targetPath()));
        }
        assertFalse(
                "expected at least one suggestion on the new column",
                session.suggestions().ops().isEmpty());
    }

    private static SchemaGenSession.Stage waitForTerminal(SchemaGenSessionStore store, String id, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            Optional<SchemaGenSession> s = store.get(id);
            if (s.isPresent()) {
                SchemaGenSession.Stage stage = s.get().stage();
                if (stage == SchemaGenSession.Stage.READY || stage == SchemaGenSession.Stage.FAILED) {
                    return stage;
                }
            }
            Thread.sleep(25);
        }
        fail("timed out waiting for terminal stage on session " + id);
        return null;
    }
}
