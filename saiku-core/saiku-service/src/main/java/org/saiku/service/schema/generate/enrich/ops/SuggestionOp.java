package org.saiku.service.schema.generate.enrich.ops;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Sealed root of the enrichment operations model.
 *
 * <p>Each concrete op describes a single, self-contained change the enrichment layer (heuristic
 * rules or LLM) proposes against a {@link org.saiku.service.schema.generate.draft.DraftSchema}
 * element, addressed by a string path such as {@code cubes/Sales/measures/Amount}.
 *
 * <p>Confidence is a documentation-only scalar in {@code [0.0, 1.0]}; we do not enforce the range
 * here so downstream consumers can experiment with out-of-band values without breaking
 * serialisation.
 *
 * <p>Jackson polymorphism: the discriminator field {@code op} is written into every payload. This
 * keeps JSON streams produced by the LLM and by rule-based enrichers interchangeable.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "op")
@JsonSubTypes({
    @JsonSubTypes.Type(value = RenameOp.class, name = "rename"),
    @JsonSubTypes.Type(value = HierarchyOp.class, name = "hierarchy"),
    @JsonSubTypes.Type(value = AggregatorOp.class, name = "aggregator"),
    @JsonSubTypes.Type(value = DegenerateDimOp.class, name = "degenerateDim"),
    @JsonSubTypes.Type(value = IgnoreOp.class, name = "ignore"),
})
public sealed interface SuggestionOp permits RenameOp, HierarchyOp, AggregatorOp, DegenerateDimOp, IgnoreOp {

    /** Path to the element this op addresses, e.g. {@code cubes/Sales/measures/Amount}. */
    String targetPath();

    /** Suggested confidence, nominally in {@code [0.0, 1.0]}. Not enforced. */
    double confidence();

    /** Short human-readable reason the suggester produced this op. */
    String rationale();
}
