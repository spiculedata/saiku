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
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.schema.generate.enrich.LlmEnricher;
import org.saiku.service.schema.generate.enrich.PiiFilter;
import org.saiku.service.schema.generate.enrich.provider.NoopProvider;
import org.saiku.service.schema.generate.infer.SchemaInferrer;
import org.saiku.service.schema.generate.introspect.JdbcIntrospector;

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
