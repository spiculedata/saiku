package org.saiku.service.schema.generate.session;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.Executor;
import org.saiku.service.schema.generate.draft.DraftSchema;
import org.saiku.service.schema.generate.enrich.LlmEnricher;
import org.saiku.service.schema.generate.enrich.SuggestionSet;
import org.saiku.service.schema.generate.infer.SchemaInferrer;
import org.saiku.service.schema.generate.introspect.JdbcIntrospector;
import org.saiku.service.schema.generate.model.DbModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives the async schema-generation pipeline — introspect → infer → enrich — for a single
 * {@link SchemaGenSession}.
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

    private final SchemaGenSessionStore store;
    private final JdbcIntrospector introspector;
    private final SchemaInferrer inferrer;
    private final LlmEnricher enricher;
    private final Executor executor;
    private final ConnectionProvider connectionProvider;

    public SchemaGenOrchestrator(
            SchemaGenSessionStore store,
            JdbcIntrospector introspector,
            SchemaInferrer inferrer,
            LlmEnricher enricher,
            Executor executor,
            ConnectionProvider connectionProvider) {
        this.store = Objects.requireNonNull(store, "store");
        this.introspector = Objects.requireNonNull(introspector, "introspector");
        this.inferrer = Objects.requireNonNull(inferrer, "inferrer");
        this.enricher = Objects.requireNonNull(enricher, "enricher");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.connectionProvider = Objects.requireNonNull(connectionProvider, "connectionProvider");
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

            session.setStage(SchemaGenSession.Stage.ENRICHING);
            SuggestionSet suggestions = enricher.enrich(draft, Collections.emptyMap());
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
}
