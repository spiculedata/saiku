package org.saiku.service.schema.generate.enrich.provider;

/**
 * SPI for schema-enrichment providers.
 *
 * <p>Implementations inspect a {@link org.saiku.service.schema.generate.draft.DraftSchema} and emit
 * a {@link org.saiku.service.schema.generate.enrich.SuggestionSet} of proposed edits. Providers
 * may be purely offline rule-based (see {@link NoopProvider}) or may delegate to a remote LLM.
 *
 * <p>Implementations SHOULD be stateless and thread-safe. They MUST NOT mutate the incoming
 * {@link org.saiku.service.schema.generate.draft.DraftSchema}; mutations happen in a separate
 * op-applier pass so the user can accept or reject individual suggestions.
 *
 * <h2>{@code targetPath} convention</h2>
 *
 * Every {@link org.saiku.service.schema.generate.enrich.ops.SuggestionOp} addresses a draft-schema
 * element via a slash-separated path built from <em>stable identifiers</em> (physical
 * table / column names), <strong>not</strong> user-visible captions. See
 * {@link org.saiku.service.schema.generate.path.SchemaPathResolver} for the canonical grammar and
 * segment rules. Using stable ids means a {@link
 * org.saiku.service.schema.generate.enrich.ops.RenameOp} that mutates a measure's caption does
 * not invalidate the path of any subsequent op targeting the same element.
 *
 * <pre>
 *   cubes/{factTable}
 *   cubes/{factTable}/dimensions/{dimTable}
 *   cubes/{factTable}/dimensions/{dimTable}/hierarchies/{pkCol}
 *   cubes/{factTable}/dimensions/{dimTable}/hierarchies/{pkCol}/levels/{levelCol}
 *   cubes/{factTable}/measures/{measureCol}       // "count_star" when aggregator is COUNT_STAR
 *   sharedDimensions/{dimTable}
 *   sharedDimensions/{dimTable}/hierarchies/{pkCol}
 *   sharedDimensions/{dimTable}/hierarchies/{pkCol}/levels/{levelCol}
 * </pre>
 *
 * When a stable id is null/blank (e.g. a not-yet-bound shared-dim role-play), implementations
 * fall back to the element's current {@code name()} so partially-built drafts remain
 * addressable.
 */
@FunctionalInterface
public interface LlmProvider {

    /**
     * Produce enrichment suggestions for the given request.
     *
     * @param request non-null request payload
     * @return non-null response; if the provider could not reason about the draft it SHOULD return
     *     an empty {@link org.saiku.service.schema.generate.enrich.SuggestionSet} with
     *     {@code degraded == true} rather than throwing
     */
    EnrichResponse enrich(EnrichRequest request);
}
