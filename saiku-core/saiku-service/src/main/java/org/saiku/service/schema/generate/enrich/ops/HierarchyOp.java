/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.generate.enrich.ops;

import com.fasterxml.jackson.annotation.JsonTypeName;
import java.util.List;
import java.util.Objects;

/**
 * Propose a multi-level hierarchy on a dimension. {@code targetPath} addresses the dimension;
 * {@code levelColumns} lists source columns in coarsest-to-finest order.
 */
@JsonTypeName("hierarchy")
public record HierarchyOp(
        String targetPath, String hierarchyName, List<String> levelColumns, double confidence, String rationale)
        implements SuggestionOp {

    public HierarchyOp {
        Objects.requireNonNull(targetPath, "targetPath");
        Objects.requireNonNull(rationale, "rationale");
        levelColumns = levelColumns == null ? List.of() : List.copyOf(levelColumns);
    }
}
