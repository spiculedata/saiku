/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.generate.enrich;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.saiku.service.olap.ai.AiDataKind;
import org.saiku.service.olap.ai.AiPolicyGuard;
import org.saiku.service.schema.generate.draft.DraftCube;
import org.saiku.service.schema.generate.draft.DraftDimension;
import org.saiku.service.schema.generate.draft.DraftSchema;
import org.saiku.service.schema.generate.enrich.ops.SuggestionOp;
import org.saiku.service.schema.generate.enrich.provider.EnrichRequest;
import org.saiku.service.schema.generate.enrich.provider.EnrichResponse;
import org.saiku.service.schema.generate.enrich.provider.LlmProvider;
import org.saiku.service.schema.generate.enrich.provider.NoopProvider;

/**
 * Orchestrates chunked, retried calls to an {@link LlmProvider} for schema-enrichment suggestions.
 *
 * <h2>Chunking</h2>
 *
 * One request per cube — the draft sent to the provider carries a single cube plus its dimensions.
 * A separate request is issued for shared dimensions (if any). This keeps provider payloads small
 * and limits blast-radius when a single cube's payload is malformed.
 *
 * <h2>Retries &amp; fallback</h2>
 *
 * Each chunk is attempted {@code maxRetries + 1} times. A "failure" is either a thrown exception
 * or a {@code null} {@link EnrichResponse} / {@code null} {@link SuggestionSet}. On final failure
 * the chunk is retried once against an internal {@link NoopProvider}; the accumulated
 * {@link SuggestionSet#degraded()} is flipped to {@code true} so callers can surface a warning.
 *
 * <h2>PII handling</h2>
 *
 * Column samples are routed through {@link PiiFilter} before every provider call. Primary and
 * fallback calls use the same filtered view — never raw samples.
 */
public final class LlmEnricher {

    private static final int DEFAULT_MAX_RETRIES = 2;

    private final LlmProvider primary;
    private final PiiFilter piiFilter;
    private final int maxRetries;
    private final LlmProvider fallback = new NoopProvider();

    /**
     * saiku#1554. Dedicated LLM-egress guard, layered ON TOP of {@link #piiFilter} (defense-in-depth,
     * consistent posture with the MDX {@code /ai/ask} and Ossie {@code /ai/ossie/ask} paths). The
     * column samples sent to the vendor are raw warehouse values; when the active egress posture
     * doesn't permit that tier, ALL samples are withheld so only the draft schema METADATA
     * (table / column names, aggregators) is sent. Injected via setter so existing constructor wiring
     * stays unchanged. FAIL-CLOSED: a null (unwired) guard denies egress.
     *
     * <p><b>Classification — flagged for SEC review.</b> Column samples map to
     * {@link AiDataKind#SAMPLE_MEMBERS} on a strict reading, but {@link PiiFilter} here is a
     * deny-LIST (it only strips known-pattern columns — {@code ssn}, {@code email}, … — and can miss
     * an un-listed sensitive column such as a free-text note). Because that guarantee is weaker than
     * the annotation-based saiku#902 filter the {@code SAMPLE_MEMBERS} tier assumes, we gate at the
     * more conservative {@link AiDataKind#AGGREGATED_RESULT_VALUES}: at the safe default posture
     * ({@code schema-only}) samples are stripped and only metadata egresses; an operator opts in with
     * {@code SAIKU_AI_LLM_EGRESS=aggregated}. Kept consistent with the Ossie ask path. SEC may
     * downgrade to {@code SAMPLE_MEMBERS} if the deny-list is considered sufficient.
     */
    private AiPolicyGuard egressGuard;

    public LlmEnricher(LlmProvider primary, PiiFilter piiFilter, int maxRetries) {
        this.primary = Objects.requireNonNull(primary, "primary");
        this.piiFilter = Objects.requireNonNull(piiFilter, "piiFilter");
        this.maxRetries = Math.max(0, maxRetries);
    }

    public LlmEnricher(LlmProvider primary, PiiFilter piiFilter) {
        this(primary, piiFilter, DEFAULT_MAX_RETRIES);
    }

    /** Spring setter — wired to {@code aiLlmEgressGuard} in {@code saiku-beans.xml} (saiku#1554). */
    public void setEgressGuard(AiPolicyGuard egressGuard) {
        this.egressGuard = egressGuard;
    }

    /** The dedicated LLM-egress guard, or {@code null} if not wired (treated as egress-denied). */
    public AiPolicyGuard egressGuard() {
        return egressGuard;
    }

    /**
     * Whether raw column sample VALUES may egress to the LLM vendor under the active egress posture.
     * FAIL-CLOSED: a null (unwired) guard, or a posture that doesn't permit the classified tier,
     * returns {@code false}. See {@link #egressGuard} for the {@link AiDataKind} rationale.
     */
    private boolean egressPermitsColumnSamples() {
        return egressGuard != null && egressGuard.canSend(AiDataKind.AGGREGATED_RESULT_VALUES);
    }

    /** Run the primary provider over the given draft, chunked per cube, with retry + fallback. */
    public SuggestionSet enrich(DraftSchema draft, Map<String, List<String>> columnSamples) {
        Objects.requireNonNull(draft, "draft");
        Map<String, List<String>> safeSamples = piiFilter.filter(columnSamples);
        // saiku#1554: LLM-egress gate ON TOP of the PiiFilter (defense-in-depth). When the egress
        // posture doesn't permit sample-value egress, withhold ALL column samples — only the draft
        // schema metadata (names, aggregators) reaches the vendor. Fail-closed: unwired guard denies.
        if (!egressPermitsColumnSamples()) {
            safeSamples = Map.of();
        }

        SuggestionSet accumulated = new SuggestionSet();

        for (DraftCube cube : draft.cubes()) {
            DraftSchema chunk = schemaForCube(draft, cube);
            runChunk(chunk, safeSamples, accumulated);
        }

        if (!draft.sharedDimensions().isEmpty()) {
            DraftSchema chunk = schemaForSharedDims(draft);
            runChunk(chunk, safeSamples, accumulated);
        }

        return accumulated;
    }

    private void runChunk(DraftSchema chunk, Map<String, List<String>> samples, SuggestionSet accumulated) {
        EnrichRequest req = new EnrichRequest(chunk, samples, Integer.MAX_VALUE);

        int attempts = maxRetries + 1;
        for (int i = 0; i < attempts; i++) {
            SuggestionSet s = tryCall(primary, req);
            if (s != null) {
                mergeInto(accumulated, s);
                return;
            }
        }

        // Primary exhausted — fall back to NoopProvider for this chunk and mark degraded.
        SuggestionSet s = tryCall(fallback, req);
        if (s != null) {
            mergeInto(accumulated, s);
        }
        accumulated.setDegraded(true);
    }

    private static SuggestionSet tryCall(LlmProvider provider, EnrichRequest req) {
        try {
            EnrichResponse resp = provider.enrich(req);
            if (resp == null) {
                return null;
            }
            return resp.suggestions();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static void mergeInto(SuggestionSet dst, SuggestionSet src) {
        for (SuggestionOp op : src.ops()) {
            dst.add(op);
        }
        if (src.degraded()) {
            dst.setDegraded(true);
        }
    }

    /**
     * Build a lightweight draft carrying only {@code cube} (plus any shared dimensions it
     * references via foreign-key joins). Caller-visible state on the original draft is untouched —
     * the chunk reuses the same cube/dimension instances, so ops that address them by path still
     * resolve when the full draft is reconstructed upstream.
     */
    private static DraftSchema schemaForCube(DraftSchema full, DraftCube cube) {
        DraftSchema chunk = new DraftSchema(full.name());
        chunk.cubes().add(cube);
        // Intentionally omit shared dimensions — they are enriched in a separate chunk so we don't
        // emit duplicate shared-dim ops, one per cube request. If a future provider needs shared-
        // dim context for role-played usages, revisit here.
        return chunk;
    }

    private static DraftSchema schemaForSharedDims(DraftSchema full) {
        DraftSchema chunk = new DraftSchema(full.name());
        for (DraftDimension d : full.sharedDimensions()) {
            chunk.sharedDimensions().add(d);
        }
        return chunk;
    }
}
