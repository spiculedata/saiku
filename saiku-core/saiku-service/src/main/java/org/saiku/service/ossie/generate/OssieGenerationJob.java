/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.generate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.saiku.service.schema.generate.delta.DeltaReport;
import org.saiku.service.schema.generate.enrich.ops.SuggestionOp;

/**
 * In-memory state for one end-to-end "generate an Ossie model from this warehouse" run
 * (saiku#1439).
 *
 * <p>Stages advance as:
 *
 * <pre>
 *   PENDING → INTROSPECTING → INFERRING → ENRICHING → APPLYING → CONVERTING
 *          → VALIDATING → WRITING → COMPLETED
 * </pre>
 *
 * with {@link #FAILED} reachable from any stage; {@link #failureMessage()} then carries the
 * human-readable reason. Exceptions are never propagated out of the async task — the job is the
 * error channel.
 *
 * <p>Mutable by design, mirroring {@link org.saiku.service.schema.generate.session.SchemaGenSession}.
 * Not thread-safe on its own; the {@link OssieGenerationJobStore} guarantees happens-before
 * between the async writer and a polling reader by publishing the job into a
 * {@link java.util.concurrent.ConcurrentHashMap} only after the stage transitions it depends on
 * are complete for that stage.
 */
public class OssieGenerationJob {

    /** High-level pipeline state. */
    public enum Stage {
        /** Created, nothing started. */
        PENDING,
        /** JDBC metadata walk. */
        INTROSPECTING,
        /** Rule-based fact/dimension/measure inference. */
        INFERRING,
        /** LLM enrichment in flight. */
        ENRICHING,
        /** Suggestions being applied to the draft. */
        APPLYING,
        /** Draft → Mondrian XML → {@code OssieDocument}. */
        CONVERTING,
        /** Round-trip + structural validation of the emitted YAML. */
        VALIDATING,
        /** Persisting {@code <name>.generated.yaml} and the rationale markdown. */
        WRITING,
        /** Terminal success. */
        COMPLETED,
        /** Terminal failure — see {@link #failureMessage()}. */
        FAILED
    }

    private final String id;
    private final String dataSourceId;
    private final String modelName;
    private final Instant createdAt;
    private final Clock clock;
    private final List<SuggestionOp> appliedOps = new ArrayList<>();
    private final List<SkippedOp> skippedOps = new ArrayList<>();

    private Stage stage;
    private Instant lastAccessedAt;
    private String failureMessage;
    private String yamlPath;
    private String rationalePath;
    private int semanticModelCount;
    private int datasetCount;
    private int fieldCount;
    private int metricCount;
    private int relationshipCount;
    private boolean degraded;
    private List<String> skippedCubes = List.of();
    private DeltaReport deltaReport;

    OssieGenerationJob(String id, String dataSourceId, String modelName, Clock clock) {
        this.id = Objects.requireNonNull(id, "id");
        this.dataSourceId = Objects.requireNonNull(dataSourceId, "dataSourceId");
        this.modelName = Objects.requireNonNull(modelName, "modelName");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.createdAt = clock.instant();
        this.lastAccessedAt = this.createdAt;
        this.stage = Stage.PENDING;
    }

    public String id() {
        return id;
    }

    public String dataSourceId() {
        return dataSourceId;
    }

    /**
     * The stem both artefacts are named from — {@code <modelName>.generated.yaml} and {@code
     * <modelName>.generated.rationale.md}. Defaults to the data-source id when the caller doesn't
     * name one.
     */
    public String modelName() {
        return modelName;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant lastAccessedAt() {
        return lastAccessedAt;
    }

    /** Package-private: the store bumps this on every successful {@code get()}. */
    void touch() {
        this.lastAccessedAt = clock.instant();
    }

    public Stage stage() {
        return stage;
    }

    public void setStage(Stage stage) {
        this.stage = Objects.requireNonNull(stage, "stage");
    }

    public String failureMessage() {
        return failureMessage;
    }

    public void setFailureMessage(String failureMessage) {
        this.failureMessage = failureMessage;
    }

    /** Repository path of the emitted Ossie YAML. {@code null} until {@link Stage#WRITING}. */
    public String yamlPath() {
        return yamlPath;
    }

    public void setYamlPath(String yamlPath) {
        this.yamlPath = yamlPath;
    }

    /** Repository path of the emitted rationale markdown. */
    public String rationalePath() {
        return rationalePath;
    }

    public void setRationalePath(String rationalePath) {
        this.rationalePath = rationalePath;
    }

    public int semanticModelCount() {
        return semanticModelCount;
    }

    public void setSemanticModelCount(int semanticModelCount) {
        this.semanticModelCount = semanticModelCount;
    }

    public int datasetCount() {
        return datasetCount;
    }

    public void setDatasetCount(int datasetCount) {
        this.datasetCount = datasetCount;
    }

    public int fieldCount() {
        return fieldCount;
    }

    public void setFieldCount(int fieldCount) {
        this.fieldCount = fieldCount;
    }

    public int metricCount() {
        return metricCount;
    }

    public void setMetricCount(int metricCount) {
        this.metricCount = metricCount;
    }

    public int relationshipCount() {
        return relationshipCount;
    }

    public void setRelationshipCount(int relationshipCount) {
        this.relationshipCount = relationshipCount;
    }

    /**
     * Whether the enrichment layer fell back to offline rules for at least one chunk. A degraded
     * run is still written — the rationale records it, and callers can warn.
     */
    public boolean degraded() {
        return degraded;
    }

    public void setDegraded(boolean degraded) {
        this.degraded = degraded;
    }

    /** Cubes the Mondrian→Ossie converter recognised but could not map (Mondrian 4 shape). */
    public List<String> skippedCubes() {
        return skippedCubes;
    }

    public void setSkippedCubes(List<String> skippedCubes) {
        this.skippedCubes = skippedCubes == null ? List.of() : List.copyOf(skippedCubes);
    }

    /**
     * Delta against a previously-generated draft, when the sidecar store found a baseline.
     * {@code null} on a first run.
     */
    public DeltaReport deltaReport() {
        return deltaReport;
    }

    public void setDeltaReport(DeltaReport deltaReport) {
        this.deltaReport = deltaReport;
    }

    /** Suggestions actually applied to the draft, in application order. */
    public List<SuggestionOp> appliedOps() {
        return appliedOps;
    }

    void appendAppliedOp(SuggestionOp op) {
        appliedOps.add(Objects.requireNonNull(op, "op"));
        touch();
    }

    /**
     * Suggestions the enricher proposed that could NOT be applied — a stale path, or an element
     * an earlier op already removed. Recorded rather than dropped: an audit document that silently
     * omits a decision is worse than one that admits it failed, because the reader cannot then
     * tell the difference between "the model is right" and "the model is right as far as the
     * generator got".
     */
    public List<SkippedOp> skippedOps() {
        return skippedOps;
    }

    void appendSkippedOp(SuggestionOp op, String reason) {
        skippedOps.add(new SkippedOp(
                Objects.requireNonNull(op, "op"), reason == null ? op.getClass().getSimpleName() : reason));
        touch();
    }

    /**
     * A proposal the generator could not apply, and why.
     *
     * @param op the rejected suggestion
     * @param reason human-readable cause, from the op-applier's exception message
     */
    public record SkippedOp(SuggestionOp op, String reason) {}
}
