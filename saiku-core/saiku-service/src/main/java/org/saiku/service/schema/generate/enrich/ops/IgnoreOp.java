package org.saiku.service.schema.generate.enrich.ops;

import com.fasterxml.jackson.annotation.JsonTypeName;
import java.util.Objects;

/** Propose dropping an element (cube, dim, hierarchy, level, or measure) identified by path. */
@JsonTypeName("ignore")
public record IgnoreOp(String targetPath, double confidence, String rationale) implements SuggestionOp {

    public IgnoreOp {
        Objects.requireNonNull(targetPath, "targetPath");
        Objects.requireNonNull(rationale, "rationale");
    }
}
