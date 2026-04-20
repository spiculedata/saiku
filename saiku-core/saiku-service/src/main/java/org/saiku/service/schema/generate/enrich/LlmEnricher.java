package org.saiku.service.schema.generate.enrich;

import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    public LlmEnricher(LlmProvider primary, PiiFilter piiFilter, int maxRetries) {
        this.primary = Objects.requireNonNull(primary, "primary");
        this.piiFilter = Objects.requireNonNull(piiFilter, "piiFilter");
        this.maxRetries = Math.max(0, maxRetries);
    }

    public LlmEnricher(LlmProvider primary, PiiFilter piiFilter) {
        this(primary, piiFilter, DEFAULT_MAX_RETRIES);
    }

    /** Run the primary provider over the given draft, chunked per cube, with retry + fallback. */
    public SuggestionSet enrich(DraftSchema draft, Map<String, List<String>> columnSamples) {
        Objects.requireNonNull(draft, "draft");
        Map<String, List<String>> safeSamples = piiFilter.filter(columnSamples);

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
