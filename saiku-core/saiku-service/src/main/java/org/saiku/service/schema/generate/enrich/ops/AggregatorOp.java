/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.generate.enrich.ops;

import com.fasterxml.jackson.annotation.JsonTypeName;
import java.util.Objects;
import org.saiku.service.schema.generate.draft.DraftMeasure.Aggregator;

/** Change a measure's aggregator. {@code targetPath} points at the measure. */
@JsonTypeName("aggregator")
public record AggregatorOp(
        String targetPath, Aggregator oldAggregator, Aggregator newAggregator, double confidence, String rationale)
        implements SuggestionOp {

    public AggregatorOp {
        Objects.requireNonNull(targetPath, "targetPath");
        Objects.requireNonNull(rationale, "rationale");
    }
}
