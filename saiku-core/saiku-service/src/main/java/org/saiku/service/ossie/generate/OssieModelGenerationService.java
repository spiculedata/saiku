/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.generate;

import bi.saiku.ossie.OssieYamlReader;
import bi.saiku.ossie.OssieYamlWriter;
import bi.saiku.ossie.model.OssieDocument;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.saiku.service.schema.generate.apply.OpApplier;
import org.saiku.service.schema.generate.delta.DeltaReconciler;
import org.saiku.service.schema.generate.delta.DeltaReport;
import org.saiku.service.schema.generate.draft.DraftSchema;
import org.saiku.service.schema.generate.enrich.LlmEnricher;
import org.saiku.service.schema.generate.enrich.PiiFilter;
import org.saiku.service.schema.generate.enrich.SuggestionSet;
import org.saiku.service.schema.generate.enrich.ops.SuggestionOp;
import org.saiku.service.schema.generate.infer.SchemaInferrer;
import org.saiku.service.schema.generate.introspect.JdbcIntrospector;
import org.saiku.service.schema.generate.model.DbColumn;
import org.saiku.service.schema.generate.model.DbModel;
import org.saiku.service.schema.generate.model.DbTable;
import org.saiku.service.schema.generate.session.SchemaGenOrchestrator;
import org.saiku.service.schema.generate.writer.GeneratedSidecar;
import org.saiku.service.schema.generate.writer.MondrianSchemaWriter;
import org.saiku.service.schema.ossie.MondrianToOssieConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-click "build me a semantic model from this warehouse" (saiku#1439).
 *
 * <p>Chains the deterministic schema-generation pipeline that already exists in {@code
 * org.saiku.service.schema.generate} — introspect, infer, enrich — through the one hop that was
 * missing: {@link MondrianToOssieConverter} plus validation plus persistence. Nothing here
 * re-implements a stage; each collaborator is the same class the interactive schema-generator
 * uses, so a change to fact/dimension classification or to the enrichment prompt shows up in both
 * surfaces.
 *
 * <pre>
 *   INTROSPECTING  JdbcIntrospector            JDBC metadata → DbModel
 *   INFERRING      SchemaInferrer              DbModel → DraftSchema (rules only)
 *   ENRICHING      LlmEnricher                 DraftSchema + PiiFilter → SuggestionSet
 *   APPLYING       OpApplier                   SuggestionSet → mutated DraftSchema
 *   CONVERTING     MondrianSchemaWriter +      DraftSchema → Mondrian XML → OssieDocument
 *                  MondrianToOssieConverter
 *   VALIDATING     OssieModelValidator +       OssieDocument → findings (reject on any)
 *                  OssieYamlReader round-trip
 *   WRITING        GeneratedModelStore         OssieDocument → <name>.generated.yaml
 *                                                + <name>.generated.rationale.md
 * </pre>
 *
 * <h2>Async by contract</h2>
 *
 * {@link #start(String, String)} publishes a {@link OssieGenerationJob} at {@code PENDING} and
 * returns it immediately; the pipeline runs on the supplied {@link Executor}. A 47-table warehouse
 * takes tens of seconds, which is exactly the duration an HTTP request should not be held open
 * for. Callers poll {@link OssieGenerationJobStore#get(String)}.
 *
 * <h2>Regeneration</h2>
 *
 * When the store finds a previously-generated YAML for the same data source, the fresh draft is
 * reconciled against the baseline sidecar so the job carries a {@link DeltaReport} and the
 * rationale names what changed. Regeneration never deletes: elements the warehouse dropped upstream
 * are reported as {@code REMOVED_UPSTREAM} and left in the emitted model, because silently dropping
 * a dimension an analyst already built a dashboard on is not a decision a generator should make.
 *
 * <h2>Trust boundary</h2>
 *
 * Nothing reaches the repository unvalidated. The converter's output goes through {@link
 * OssieModelValidator} and is then re-parsed with {@link OssieYamlReader} in strict-version mode —
 * a document that can't be read back by the same library that will later serve it is rejected
 * rather than written.
 */
public class OssieModelGenerationService {

    private static final Logger LOG = LoggerFactory.getLogger(OssieModelGenerationService.class);

    private final OssieGenerationJobStore jobStore;
    private final JdbcIntrospector introspector;
    private final SchemaInferrer inferrer;
    private final LlmEnricher enricher;
    private final OpApplier opApplier;
    private final MondrianSchemaWriter mondrianWriter;
    private final GeneratedModelStore modelStore;
    private final SchemaGenOrchestrator.SidecarStore sidecarStore;
    private final Executor executor;
    private final SchemaGenOrchestrator.ConnectionProvider connectionProvider;
    private final Clock clock;
    private final DeltaReconciler deltaReconciler = new DeltaReconciler();

    /**
     * A second, throwaway filter used only to REPORT which columns the enricher's filter withheld.
     * Not injected: the enricher owns the authoritative instance (with any operator-configured
     * extra patterns), and this one only needs the shipped deny list to name the columns in the
     * rationale. Sharing the enricher's filter would be wrong — it is stateful per deployment.
     */
    private final PiiFilter piiReporter = new PiiFilter();

    private final OssieYamlWriter yamlWriter = new OssieYamlWriter();
    private final OssieYamlReader yamlReader = new OssieYamlReader().setStrictVersion(true);

    public OssieModelGenerationService(
            OssieGenerationJobStore jobStore,
            JdbcIntrospector introspector,
            SchemaInferrer inferrer,
            LlmEnricher enricher,
            OpApplier opApplier,
            MondrianSchemaWriter mondrianWriter,
            GeneratedModelStore modelStore,
            SchemaGenOrchestrator.SidecarStore sidecarStore,
            Executor executor,
            SchemaGenOrchestrator.ConnectionProvider connectionProvider) {
        this(
                jobStore,
                introspector,
                inferrer,
                enricher,
                opApplier,
                mondrianWriter,
                modelStore,
                sidecarStore,
                executor,
                connectionProvider,
                Clock.systemUTC());
    }

    /** Full constructor — the {@link Clock} overload exists for deterministic tests. */
    public OssieModelGenerationService(
            OssieGenerationJobStore jobStore,
            JdbcIntrospector introspector,
            SchemaInferrer inferrer,
            LlmEnricher enricher,
            OpApplier opApplier,
            MondrianSchemaWriter mondrianWriter,
            GeneratedModelStore modelStore,
            SchemaGenOrchestrator.SidecarStore sidecarStore,
            Executor executor,
            SchemaGenOrchestrator.ConnectionProvider connectionProvider,
            Clock clock) {
        this.jobStore = Objects.requireNonNull(jobStore, "jobStore");
        this.introspector = Objects.requireNonNull(introspector, "introspector");
        this.inferrer = Objects.requireNonNull(inferrer, "inferrer");
        this.enricher = Objects.requireNonNull(enricher, "enricher");
        this.opApplier = Objects.requireNonNull(opApplier, "opApplier");
        this.mondrianWriter = Objects.requireNonNull(mondrianWriter, "mondrianWriter");
        this.modelStore = Objects.requireNonNull(modelStore, "modelStore");
        this.sidecarStore = sidecarStore == null ? SchemaGenOrchestrator.SidecarStore.empty() : sidecarStore;
        this.executor = Objects.requireNonNull(executor, "executor");
        this.connectionProvider = Objects.requireNonNull(connectionProvider, "connectionProvider");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Start a generation run. Returns the job immediately at {@code PENDING}; the pipeline runs on
     * the executor.
     *
     * @param dataSourceId Saiku data-source id or name
     * @param modelName stem for the two output files; defaults to {@code dataSourceId} when blank
     */
    public OssieGenerationJob start(String dataSourceId, String modelName) {
        Objects.requireNonNull(dataSourceId, "dataSourceId");
        OssieGenerationJob job = jobStore.create(dataSourceId, modelName);
        executor.execute(() -> runPipeline(job));
        return job;
    }

    private void runPipeline(OssieGenerationJob job) {
        try {
            job.setStage(OssieGenerationJob.Stage.INTROSPECTING);
            DbModel model;
            try (Connection conn = connectionProvider.get(job.dataSourceId())) {
                if (conn == null) {
                    throw new SQLException("connection provider returned null for data source " + job.dataSourceId());
                }
                model = introspector.introspect(conn);
            }

            job.setStage(OssieGenerationJob.Stage.INFERRING);
            DraftSchema draft = inferrer.infer(model);
            List<String> piiColumns = piiColumns(model);

            // Regeneration: reconcile before enriching so the report reflects the fresh draft
            // rather than a mutated one.
            Optional<GeneratedSidecar> baseline = sidecarStore.load(job.dataSourceId());
            if (baseline.isPresent() && baseline.get().draft() != null) {
                DeltaReport delta = deltaReconciler.reconcile(baseline.get().draft(), draft);
                job.setDeltaReport(delta);
            }

            job.setStage(OssieGenerationJob.Stage.ENRICHING);
            SuggestionSet suggestions = enricher.enrich(draft, Collections.emptyMap());
            job.setDegraded(suggestions.degraded());

            job.setStage(OssieGenerationJob.Stage.APPLYING);
            for (SuggestionOp op : suggestions.ops()) {
                try {
                    opApplier.apply(draft, op);
                    job.appendAppliedOp(op);
                } catch (RuntimeException ex) {
                    // One bad op (a stale path, an element an earlier op already removed) must not
                    // sink the whole run — the draft is still usable. The rejection is recorded on
                    // the job and surfaced in the rationale, not just logged.
                    job.appendSkippedOp(op, ex.getMessage());
                    LOG.warn(
                            "Skipping enrichment op '{}' for generation job {}: {}",
                            op.targetPath(),
                            job.id(),
                            ex.getMessage());
                }
            }

            job.setStage(OssieGenerationJob.Stage.CONVERTING);
            String mondrianXml = mondrianWriter.write(draft);
            MondrianToOssieConverter converter = new MondrianToOssieConverter();
            OssieDocument doc =
                    converter.convert(new ByteArrayInputStream(mondrianXml.getBytes(StandardCharsets.UTF_8)));
            job.setSkippedCubes(converter.getSkippedCubes());

            job.setStage(OssieGenerationJob.Stage.VALIDATING);
            List<String> problems = OssieModelValidator.validate(doc);
            if (!problems.isEmpty()) {
                throw new IllegalStateException("generated model failed validation: " + String.join("; ", problems));
            }
            String yaml = yamlWriter.writeAsString(doc);
            // Read back with the same library that will serve it. Catches a writer/reader skew
            // the structural validator cannot see (e.g. a dialect expression that serialises to
            // something the model can't deserialise).
            yamlReader.readString(yaml);

            countShape(job, doc);

            job.setStage(OssieGenerationJob.Stage.WRITING);
            // The store's paths are deterministic, so the rationale can name them before the
            // write — one render, one write, no half-updated pair on disk if the run dies midway.
            GeneratedModelStore.WrittenPaths paths = modelStore.pathsFor(job.dataSourceId(), job.modelName());
            job.setYamlPath(paths.yamlPath());
            job.setRationalePath(paths.rationalePath());
            modelStore.write(
                    job.dataSourceId(),
                    job.modelName(),
                    yaml,
                    RationaleWriter.render(job, draft, piiColumns, clock.instant()));

            job.setStage(OssieGenerationJob.Stage.COMPLETED);
        } catch (Exception ex) {
            String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            job.setFailureMessage(msg);
            job.setStage(OssieGenerationJob.Stage.FAILED);
            // Message at WARN, stack at DEBUG: a bad data-source id or an unreachable warehouse is
            // an operator mistake, not a defect, and a full trace per failed poll-cycle buries
            // the real faults in the log.
            LOG.warn(
                    "Ossie model generation failed for job {} (data source {}): {}", job.id(), job.dataSourceId(), msg);
            LOG.debug("Ossie model generation failure detail for job " + job.id(), ex);
        }
    }

    /** Every table-qualified column name in the warehouse the PII deny list matches. */
    private List<String> piiColumns(DbModel model) {
        List<String> out = new java.util.ArrayList<>();
        for (DbTable table : model.tables()) {
            for (DbColumn column : table.columns()) {
                if (piiReporter.isPiiColumn(column.name())) {
                    out.add(table.name() + "." + column.name());
                }
            }
        }
        return out;
    }

    private static void countShape(OssieGenerationJob job, OssieDocument doc) {
        int datasets = 0;
        int fields = 0;
        int metrics = 0;
        int relationships = 0;
        for (var sm : doc.getSemanticModel()) {
            datasets += sm.getDatasets().size();
            fields += sm.getDatasets().stream()
                    .mapToInt(d -> d.getFields().size())
                    .sum();
            metrics += sm.getMetrics().size();
            relationships += sm.getRelationships().size();
        }
        job.setSemanticModelCount(doc.getSemanticModel().size());
        job.setDatasetCount(datasets);
        job.setFieldCount(fields);
        job.setMetricCount(metrics);
        job.setRelationshipCount(relationships);
    }
}
