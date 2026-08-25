/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.generate.infer;

/**
 * Result of classifying a single {@link org.saiku.service.schema.generate.model.DbTable} by the
 * {@link TableClassifier}.
 *
 * <p>{@code kind} is the coarse role the table is expected to play in the generated Mondrian
 * schema. {@code reason} is a short human-readable string explaining why the classifier picked
 * that kind — surfaced later in the UI as provenance ("why was this table picked as a fact?")
 * and should read naturally without further formatting.
 */
public record TableClassification(Kind kind, String reason) {

    /** The role a table plays after classification. */
    public enum Kind {
        /** Candidate fact table: many FKs outward, enough rows to be worth aggregating. */
        FACT,
        /** Referenced by at least one fact table's FK — candidate lookup/dimension. */
        DIMENSION,
        /** Neither a fact nor referenced by one — ignored by downstream stages. */
        ORPHAN
    }
}
