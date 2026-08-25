/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.generate.session;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import org.saiku.service.schema.generate.delta.DeltaReconciler;
import org.saiku.service.schema.generate.delta.DeltaReport;
import org.saiku.service.schema.generate.draft.DraftSchema;
import org.saiku.service.schema.generate.enrich.LlmEnricher;
import org.saiku.service.schema.generate.enrich.SuggestionSet;
import org.saiku.service.schema.generate.enrich.ops.SuggestionOp;
import org.saiku.service.schema.generate.infer.SchemaInferrer;
import org.saiku.service.schema.generate.introspect.JdbcIntrospector;
import org.saiku.service.schema.generate.model.DbModel;
import org.saiku.service.schema.generate.writer.GeneratedSidecar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives the async schema-generation pipeline — introspect → infer → (reconcile?) → enrich — for a
 * single {@link SchemaGenSession}.
 *
 * <p>{@link #start(String)} creates a session in the backing store (initial {@link
 * SchemaGenSession.Stage#PENDING PENDING}) and submits the pipeline to the supplied {@link
 * Executor}. The call returns the session immediately; callers poll {@link
 * SchemaGenSessionStore#get(String)} to observe stage transitions.
 *
 * <p>Stages advance as: PENDING → INTROSPECTING → INFERRING → ENRICHING → READY on success, or
 * transition to {@link SchemaGenSession.Stage#FAILED FAILED} on any exception with a
 * human-readable message on {@link SchemaGenSession#failureMessage()}. Exceptions are never
 * rethrown from the async task; the caller's {@code start()} return is unaffected.
 *
 * <p><strong>Re-run mode.</strong> If a {@link SidecarStore} returns a previously-persisted
 * {@link GeneratedSidecar} for the data source, the orchestrator runs a {@link DeltaReconciler}
 * after inference and attaches the resulting {@link DeltaReport} to the session. The enricher
 * output is then filtered to contain only {@link org.saiku.service.schema.generate.delta.DeltaTag#NEW}
 * suggestions — existing elements, which the user has likely already curated, are not re-enriched.
 *
 * <p>The filter is applied <em>post-enrichment</em> rather than by pruning the draft upstream of
 * the enricher. Pruning a mutable {@link DraftSchema} is fragile (parent/child back-references,
 * shared-dim usages); post-filtering the {@link SuggestionSet} is cheap and keeps the enricher
 * contract identical between first-run and re-run. This does mean the provider still sees the full
 * draft — an optimisation opportunity for future iterations where provider cost matters.
 *
 * <p>Connection acquisition is pluggable via {@link ConnectionProvider} so this class stays
 * agnostic to Saiku's data-source manager; the resource layer binds the provider to the live
 * registry at wiring time. Connections are closed via try-with-resources after introspection.
 *
 * <p>Tests inject {@code Runnable::run} as the executor for deterministic, synchronous execution.
 * Production uses a bounded or cached {@link java.util.concurrent.ExecutorService}.
 */
public class SchemaGenOrchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaGenOrchestrator.class);

    /**
     * Supplies a JDBC {@link Connection} for a Saiku data-source id. Separated from this class so
     * the orchestrator doesn't depend on Saiku's data-source manager — the REST resource binds an
     * implementation at runtime.
     */
    @FunctionalInterface
    public interface ConnectionProvider {
        Connection get(String dataSourceId) throws SQLException;
    }

    /**
     * Loads a previously-persisted {@link GeneratedSidecar} for a data source if one exists.
     * Returning an empty {@link Optional} makes the orchestrator treat this as a first-run (no
     * delta reconciliation). Default implementation is a no-op; production wires this to the
     * datasource repository so the {@code <schemaName>.generated.json} file is read when present.
     */
    @FunctionalInterface
    public interface SidecarStore {
        Optional<GeneratedSidecar> load(String dataSourceId);

        /** Default no-op store used by constructors that don't take an explicit store. */
        static SidecarStore empty() {
            return dataSourceId -> Optional.empty();
        }
    }

    private final SchemaGenSessionStore store;
    private final JdbcIntrospector introspector;
    private final SchemaInferrer inferrer;
    private final LlmEnricher enricher;
    private final Executor executor;
    private final ConnectionProvider connectionProvider;
    private final SidecarStore sidecarStore;
    private final DeltaReconciler deltaReconciler = new DeltaReconciler();

    /** Backwards-compatible ctor: no sidecar store, so every run is a first-run. */
    public SchemaGenOrchestrator(
            SchemaGenSessionStore store,
            JdbcIntrospector introspector,
            SchemaInferrer inferrer,
            LlmEnricher enricher,
            Executor executor,
            ConnectionProvider connectionProvider) {
        this(store, introspector, inferrer, enricher, executor, connectionProvider, SidecarStore.empty());
    }

    public SchemaGenOrchestrator(
            SchemaGenSessionStore store,
            JdbcIntrospector introspector,
            SchemaInferrer inferrer,
            LlmEnricher enricher,
            Executor executor,
            ConnectionProvider connectionProvider,
            SidecarStore sidecarStore) {
        this.store = Objects.requireNonNull(store, "store");
        this.introspector = Objects.requireNonNull(introspector, "introspector");
        this.inferrer = Objects.requireNonNull(inferrer, "inferrer");
        this.enricher = Objects.requireNonNull(enricher, "enricher");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.connectionProvider = Objects.requireNonNull(connectionProvider, "connectionProvider");
        this.sidecarStore = Objects.requireNonNull(sidecarStore, "sidecarStore");
    }

    /**
     * Create a new session for {@code dataSourceId} and submit the pipeline for async execution.
     * Returns the session immediately; callers poll the store for stage transitions.
     */
    public SchemaGenSession start(String dataSourceId) {
        Objects.requireNonNull(dataSourceId, "dataSourceId");
        SchemaGenSession session = store.create(dataSourceId);
        executor.execute(() -> runPipeline(session));
        return session;
    }

    private void runPipeline(SchemaGenSession session) {
        try {
            session.setStage(SchemaGenSession.Stage.INTROSPECTING);
            DbModel model;
            try (Connection conn = connectionProvider.get(session.dataSourceId())) {
                if (conn == null) {
                    throw new SQLException(
                            "connection provider returned null for data source " + session.dataSourceId());
                }
                model = introspector.introspect(conn);
            }

            session.setStage(SchemaGenSession.Stage.INFERRING);
            DraftSchema draft = inferrer.infer(model);
            session.setDraft(draft);

            // Re-run mode: if a prior sidecar exists, reconcile the fresh draft against it so we
            // can narrow enrichment to only the newly-arrived elements.
            DeltaReport deltaReport = null;
            Optional<GeneratedSidecar> baseline = sidecarStore.load(session.dataSourceId());
            if (baseline.isPresent() && baseline.get().draft() != null) {
                deltaReport = deltaReconciler.reconcile(baseline.get().draft(), draft);
                session.setDeltaReport(deltaReport);
            }

            session.setStage(SchemaGenSession.Stage.ENRICHING);
            SuggestionSet suggestions = enricher.enrich(draft, Collections.emptyMap());
            if (deltaReport != null) {
                suggestions = filterToNew(suggestions, deltaReport);
            }
            session.setSuggestions(suggestions);

            session.setStage(SchemaGenSession.Stage.READY);
        } catch (Exception ex) {
            String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            session.setFailureMessage(msg);
            session.setStage(SchemaGenSession.Stage.FAILED);
            LOG.warn(
                    "Schema-gen pipeline failed for session {} (data source {}): {}",
                    session.id(),
                    session.dataSourceId(),
                    msg,
                    ex);
        }
    }

    /**
     * Drop suggestion ops whose {@code targetPath} does not address an element tagged {@code NEW}
     * by the reconciler. Existing-element ops are discarded on the assumption the user has already
     * reviewed those during the first-run; removed-upstream ops would be meaningless.
     *
     * <p>We intentionally match on exact path: a fresh inferred draft uses physical identifiers as
     * element names, which matches the stable-id scheme used by {@link DeltaReconciler}. If a
     * future rule-based enricher emits coarser-grained paths (e.g. a cube-level rename referring to
     * a cube that merely contains a new column), that op will be pruned — which is what we want:
     * the cube itself is EXISTING and the user shouldn't be re-prompted to rename it.
     */
    private static SuggestionSet filterToNew(SuggestionSet in, DeltaReport deltaReport) {
        Set<String> newPaths = new HashSet<>(deltaReport.newPaths());
        SuggestionSet out = new SuggestionSet();
        out.setDegraded(in.degraded());
        for (SuggestionOp op : in.ops()) {
            if (newPaths.contains(op.targetPath())) {
                out.add(op);
            }
        }
        return out;
    }
}
