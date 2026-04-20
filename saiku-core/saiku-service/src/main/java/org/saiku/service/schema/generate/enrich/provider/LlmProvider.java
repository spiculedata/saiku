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
 * element via a slash-separated path. All providers and the op-applier MUST use this convention:
 *
 * <pre>
 *   cubes/{cubeName}
 *   cubes/{cubeName}/dimensions/{dimName}
 *   cubes/{cubeName}/dimensions/{dimName}/hierarchies/{hierName}
 *   cubes/{cubeName}/dimensions/{dimName}/hierarchies/{hierName}/levels/{levelName}
 *   cubes/{cubeName}/measures/{measureName}
 *   sharedDimensions/{dimName}
 *   sharedDimensions/{dimName}/hierarchies/{hierName}
 *   sharedDimensions/{dimName}/hierarchies/{hierName}/levels/{levelName}
 * </pre>
 *
 * Names are the element's current {@code name()} — never the (possibly proposed) new caption.
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
