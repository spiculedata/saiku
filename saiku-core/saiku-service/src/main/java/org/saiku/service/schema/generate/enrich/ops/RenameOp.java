package org.saiku.service.schema.generate.enrich.ops;

import com.fasterxml.jackson.annotation.JsonTypeName;
import java.util.Objects;

/**
 * Set a friendlier caption (and optional description) on any named element — cube, dimension,
 * hierarchy, level, or measure. {@code description} may be {@code null}.
 */
@JsonTypeName("rename")
public record RenameOp(
        String targetPath,
        String oldCaption,
        String newCaption,
        String description,
        double confidence,
        String rationale)
        implements SuggestionOp {

    public RenameOp {
        Objects.requireNonNull(targetPath, "targetPath");
        Objects.requireNonNull(rationale, "rationale");
    }
}
