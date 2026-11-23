/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.generate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.schema.generate.apply.OpApplier;
import org.saiku.service.schema.generate.draft.DraftSchema;
import org.saiku.service.schema.generate.enrich.LlmEnricher;
import org.saiku.service.schema.generate.enrich.PiiFilter;
import org.saiku.service.schema.generate.enrich.provider.LlmProvider;
import org.saiku.service.schema.generate.enrich.provider.NoopProvider;
import org.saiku.service.schema.generate.infer.SchemaInferrer;
import org.saiku.service.schema.generate.introspect.JdbcIntrospector;
import org.saiku.service.schema.generate.model.DbModel;
import org.saiku.service.schema.generate.session.SchemaGenOrchestrator;
import org.saiku.service.schema.generate.writer.GeneratedSidecar;
import org.saiku.service.schema.generate.writer.GeneratedSidecarIo;
import org.saiku.service.schema.generate.writer.MondrianSchemaWriter;

/**
 * End-to-end test for {@link OssieModelGenerationService} against an in-memory H2 warehouse with
 * a {@link NoopProvider} enrichment backend.
 *
 * <p>The point of the test is the <em>chain</em>, not any single stage: introspect → infer →
 * enrich → apply → Mondrian → Ossie YAML → validate → two files on disk. Every one of those hops
 * already had its own test; what could break and hadn't been exercised is the seam between them
 * (a draft that converts cleanly but whose suggestions don't survive the op-applier, an emitted
 * YAML the strict reader rejects, a rationale that renders nulls).
 */
public class OssieModelGenerationServiceTest {

    private static final String URL = "jdbc:h2:mem:ossie-gen;DB_CLOSE_DELAY=-1";
    private static final Instant FIXED_NOW = Instant.parse("2026-03-04T10:15:30Z");

    private Connection seedConn;
    private RecordingModelStore store;
    private String catalog;

    @Before
    public void setUp() throws Exception {
        seedConn = DriverManager.getConnection(URL, "sa", "");
        try (Statement st = seedConn.createStatement()) {
            st.execute("DROP ALL OBJECTS");
            st.execute("CREATE SCHEMA test");
            st.execute("CREATE TABLE test.customer (id INT PRIMARY KEY, name VARCHAR(64), email VARCHAR(128))");
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
            st.execute("INSERT INTO test.customer VALUES (1, 'alice', 'alice@example.com')");
            st.execute("INSERT INTO test.product  VALUES (1, 'widget')");
            // The classifier requires >= 1000 rows AND >= 2 outgoing FKs for FACT status.
            st.execute("INSERT INTO test.orders"
                    + " SELECT X, 1, 1, DATE '2024-01-01', 9.99, 1 FROM SYSTEM_RANGE(1, 1500)");
        }
        catalog = seedConn.getCatalog();
        store = new RecordingModelStore();
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
    // happy path
    // ------------------------------------------------------------------

    @Test
    public void generatesOssieYamlAndRationaleFromTheWarehouse() {
        OssieGenerationJob job = service(Runnable::run).start("ds-1", "warehouse");

        assertEquals(
                "pipeline ran to completion, got: " + job.failureMessage(),
                OssieGenerationJob.Stage.COMPLETED,
                job.stage());
        assertEquals("no failure message on success", null, job.failureMessage());
        assertEquals("ds-1", job.dataSourceId());
        assertEquals("warehouse", job.modelName());

        // Both artefacts landed, under the names the issue specifies.
        assertEquals(1, store.writes.size());
        RecordingModelStore.Written w = store.writes.get(0);
        assertEquals("/datasources/warehouse.generated.yaml", w.yamlPath);
        assertEquals("/datasources/warehouse.generated.rationale.md", w.rationalePath);
        assertFalse("emitted YAML must not be empty", w.yaml.trim().isEmpty());
        assertTrue("emitted YAML must be an Ossie document: " + w.yaml, w.yaml.contains("semantic_model:"));
        assertTrue("emitted YAML must carry datasets", w.yaml.contains("datasets:"));
    }

    @Test
    public void jobReportsTheEmittedShape() {
        OssieGenerationJob job = service(Runnable::run).start("ds-1", "warehouse");

        assertTrue("at least one semantic model", job.semanticModelCount() >= 1);
        assertTrue("datasets inferred", job.datasetCount() >= 1);
        assertTrue("fields inferred", job.fieldCount() >= 1);
        assertTrue("measures inferred as metrics", job.metricCount() >= 1);
        assertEquals("artefact paths recorded on the job", "/datasources/warehouse.generated.yaml", job.yamlPath());
    }

    @Test
    public void jobIsRetrievableFromTheStoreAfterCompletion() {
        OssieGenerationJobStore jobStore = new OssieGenerationJobStore();
        OssieGenerationJob job =
                service(jobStore, Runnable::run, null, h2Connections()).start("ds-1", "warehouse");

        Optional<OssieGenerationJob> found = jobStore.get(job.id());
        assertTrue("job must be pollable by id", found.isPresent());
        assertEquals(OssieGenerationJob.Stage.COMPLETED, found.get().stage());
    }

    // ------------------------------------------------------------------
    // rationale
    // ------------------------------------------------------------------

    @Test
    public void rationaleCarriesOneEntryPerAppliedDecision() {
        OssieGenerationJob job = service(Runnable::run).start("ds-1", "warehouse");
        String rationale = store.writes.get(0).rationale;

        assertFalse(
                "the offline enricher must have produced decisions",
                job.appliedOps().isEmpty());
        assertEquals(
                "one rationale entry per applied op",
                job.appliedOps().size(),
                countOccurrences(rationale, "- **Target:** `"));

        assertTrue(rationale.contains("# Generation rationale — warehouse"));
        assertTrue("names the source data source", rationale.contains("`ds-1`"));
        assertTrue("names the emitted model", rationale.contains("/datasources/warehouse.generated.yaml"));
        // A null in the audit document is a defect; the header must be fully populated.
        assertFalse("no null paths in the rationale header", rationale.contains("**Emitted model:** `null`"));
    }

    @Test
    public void rationaleListsThePiiColumnsWithheldFromTheVendor() {
        service(Runnable::run).start("ds-1", "warehouse");
        String rationale = store.writes.get(0).rationale;

        // customer.email is on the deny list; order_date and amount are not.
        assertTrue(
                "PII section must name the email column — rationale was:\n" + rationale,
                rationale.contains("## PII")
                        && rationale.toUpperCase(java.util.Locale.ROOT).contains("CUSTOMER.EMAIL"));
    }

    @Test
    public void rationaleDocumentsTheDeterministicInference() {
        service(Runnable::run).start("ds-1", "warehouse");
        String rationale = store.writes.get(0).rationale;

        assertTrue("deterministic section present", rationale.contains("## Deterministic inference"));
        // H2 folds unquoted identifiers to UPPER CASE, so match case-insensitively.
        String upper = rationale.toUpperCase(java.util.Locale.ROOT);
        assertTrue("fact table named", upper.contains("ORDERS"));
        assertTrue("dimension named", upper.contains("CUSTOMER"));
    }

    @Test
    public void rationaleIsDeterministicForAFixedClock() {
        String first = renderOnce();
        String second = renderOnce();
        assertEquals("same input + same clock must render byte-identical text", first, second);
    }

    private String renderOnce() {
        service(Runnable::run).start("ds-1", "warehouse");
        return store.writes.get(0).rationale;
    }

    // ------------------------------------------------------------------
    // regeneration
    // ------------------------------------------------------------------

    @Test
    public void regenerationOverAnExistingModelReportsADelta() throws Exception {
        DraftSchema baseline = baselineDraft();
        GeneratedSidecar sidecar = GeneratedSidecarIo.build(baseline, List.of(), "warehouse", "test");

        // Upstream gains a column between runs.
        try (Statement st = seedConn.createStatement()) {
            st.execute("ALTER TABLE test.orders ADD COLUMN discount DECIMAL(10,2) DEFAULT 0");
        }

        OssieGenerationJobStore jobStore = new OssieGenerationJobStore();
        OssieModelGenerationService svc =
                service(jobStore, Runnable::run, dsId -> Optional.of(sidecar), h2Connections());
        OssieGenerationJob job = svc.start("ds-1", "warehouse");

        assertEquals(OssieGenerationJob.Stage.COMPLETED, job.stage());
        assertNotNull("a regeneration must carry a delta report", job.deltaReport());
        assertTrue(
                "the new discount measure must be tagged NEW: "
                        + job.deltaReport().newPaths(),
                job.deltaReport().newPaths().stream()
                        .anyMatch(p -> p.toUpperCase(java.util.Locale.ROOT).endsWith("/MEASURES/DISCOUNT")));
        assertTrue(
                "the rationale must carry a 'changes since' section",
                store.writes.get(0).rationale.contains("## Changes since the previous generation"));
    }

    @Test
    public void firstRunCarriesNoDeltaSection() {
        service(Runnable::run).start("ds-1", "warehouse");
        assertFalse(
                "no baseline means no diff section",
                store.writes.get(0).rationale.contains("## Changes since the previous generation"));
    }

    @Test
    public void anUnapplicableSuggestionIsRejectedAndAdmittedNotDropped() {
        // A provider that proposes one good rename and one op addressing a path that cannot
        // resolve. The run must still complete, and the rationale must SAY the second decision
        // was not applied — an audit document that quietly omits a decision is indistinguishable
        // from one where the decision was honoured.
        LlmProvider halfBroken = request -> {
            var set = new org.saiku.service.schema.generate.enrich.SuggestionSet();
            set.add(new org.saiku.service.schema.generate.enrich.ops.RenameOp(
                    "cubes/ORDERS", "ORDERS", "Orders", null, 0.9, "title-cased"));
            set.add(new org.saiku.service.schema.generate.enrich.ops.IgnoreOp(
                    "cubes/NOPE/measures/GHOST", 0.4, "drop a column that isn't there"));
            return new org.saiku.service.schema.generate.enrich.provider.EnrichResponse(set);
        };
        OssieModelGenerationService svc =
                service(new OssieGenerationJobStore(), Runnable::run, null, h2Connections(), halfBroken);

        OssieGenerationJob job = svc.start("ds-1", "warehouse");

        assertEquals(OssieGenerationJob.Stage.COMPLETED, job.stage());
        assertEquals("the good op applied", 1, job.appliedOps().size());
        assertEquals("the bad op was rejected, not applied", 1, job.skippedOps().size());
        assertTrue(
                "the rejection must carry the cause: " + job.skippedOps().get(0).reason(),
                job.skippedOps().get(0).reason().contains("NOPE"));

        String rationale = store.writes.get(0).rationale;
        assertTrue("rationale must admit the rejected decision", rationale.contains("Rejected decisions"));
        assertTrue("and name it", rationale.contains("cubes/NOPE/measures/GHOST"));
    }

    // ------------------------------------------------------------------
    // failure paths
    // ------------------------------------------------------------------

    @Test
    public void connectionFailureIsReportedOnTheJobNotThrown() {
        OssieModelGenerationService svc = service(Runnable::run, null, dsId -> {
            throw new SQLException("boom: no such data source " + dsId);
        });

        OssieGenerationJob job = svc.start("ds-missing", "warehouse");

        assertEquals(OssieGenerationJob.Stage.FAILED, job.stage());
        assertNotNull("failure message populated", job.failureMessage());
        assertTrue(
                "failure message names the cause: " + job.failureMessage(),
                job.failureMessage().toLowerCase().contains("boom"));
        assertTrue("nothing may be written on a failed run", store.writes.isEmpty());
    }

    @Test
    public void anUnmappableWarehouseFailsValidationRatherThanWritingAnEmptyModel() throws Exception {
        // No tables at all: the converter produces zero semantic models, which the validator
        // rejects. Writing an empty Ossie document would be a silent data-loss outcome.
        try (Statement st = seedConn.createStatement()) {
            st.execute("DROP ALL OBJECTS");
            st.execute("CREATE SCHEMA test");
        }

        OssieGenerationJob job = service(Runnable::run).start("ds-1", "warehouse");

        assertEquals(OssieGenerationJob.Stage.FAILED, job.stage());
        assertTrue(
                "failure must mention validation: " + job.failureMessage(),
                job.failureMessage().contains("zero semantic models"));
        assertTrue("nothing may be written when validation rejects", store.writes.isEmpty());
    }

    @Test
    public void aNullConnectionIsAFailureNotANpe() {
        OssieModelGenerationService svc = service(Runnable::run, null, dsId -> null);
        OssieGenerationJob job = svc.start("ds-1", "warehouse");

        assertEquals(OssieGenerationJob.Stage.FAILED, job.stage());
        assertTrue(job.failureMessage().contains("returned null"));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private OssieModelGenerationService service(java.util.concurrent.Executor executor) {
        return service(executor, (SchemaGenOrchestrator.SidecarStore) null);
    }

    private OssieModelGenerationService service(
            java.util.concurrent.Executor executor, SchemaGenOrchestrator.SidecarStore sidecarStore) {
        return service(new OssieGenerationJobStore(), executor, sidecarStore, h2Connections());
    }

    private OssieModelGenerationService service(
            java.util.concurrent.Executor executor,
            SchemaGenOrchestrator.SidecarStore sidecarStore,
            SchemaGenOrchestrator.ConnectionProvider connections) {
        return service(new OssieGenerationJobStore(), executor, sidecarStore, connections);
    }

    private OssieModelGenerationService service(
            OssieGenerationJobStore jobStore,
            java.util.concurrent.Executor executor,
            SchemaGenOrchestrator.SidecarStore sidecarStore,
            SchemaGenOrchestrator.ConnectionProvider connections) {
        return service(jobStore, executor, sidecarStore, connections, new NoopProvider());
    }

    private OssieModelGenerationService service(
            OssieGenerationJobStore jobStore,
            java.util.concurrent.Executor executor,
            SchemaGenOrchestrator.SidecarStore sidecarStore,
            SchemaGenOrchestrator.ConnectionProvider connections,
            LlmProvider provider) {
        return new OssieModelGenerationService(
                jobStore,
                new JdbcIntrospector(
                        new JdbcIntrospector.Options().withCatalog(catalog).withSchemaPattern("TEST")),
                new SchemaInferrer(),
                new LlmEnricher(provider, new PiiFilter()),
                new OpApplier(),
                new MondrianSchemaWriter(),
                store,
                sidecarStore,
                executor,
                connections,
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    private SchemaGenOrchestrator.ConnectionProvider h2Connections() {
        return dsId -> DriverManager.getConnection(URL, "sa", "");
    }

    /** A draft from the pre-ALTER schema shape, to serve as the regeneration baseline. */
    private DraftSchema baselineDraft() throws SQLException {
        DbModel model = new JdbcIntrospector(
                        new JdbcIntrospector.Options().withCatalog(catalog).withSchemaPattern("TEST"))
                .introspect(seedConn);
        return new SchemaInferrer().infer(model);
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        int i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    /** In-memory {@link GeneratedModelStore} — no repository layer needed. */
    private static final class RecordingModelStore implements GeneratedModelStore {

        record Written(String yamlPath, String rationalePath, String yaml, String rationale) {}

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
            WrittenPaths p = pathsFor(dataSourceId, modelName);
            writes.add(new Written(p.yamlPath(), p.rationalePath(), yaml, rationale));
        }
    }
}
