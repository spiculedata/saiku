package org.saiku.service.schema.generate.enrich.ops;

import com.fasterxml.jackson.annotation.JsonTypeName;
import java.util.Objects;

/**
 * Promote a fact-table column to a degenerate dimension on a cube. {@code targetPath} addresses
 * the cube; {@code factColumn} is the source column; {@code dimName} is the desired dimension
 * caption.
 */
@JsonTypeName("degenerateDim")
public record DegenerateDimOp(String targetPath, String factColumn, String dimName, double confidence, String rationale)
        implements SuggestionOp {

    public DegenerateDimOp {
        Objects.requireNonNull(targetPath, "targetPath");
        Objects.requireNonNull(rationale, "rationale");
    }
}
