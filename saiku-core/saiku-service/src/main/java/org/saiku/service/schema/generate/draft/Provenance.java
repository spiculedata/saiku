/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.generate.draft;

import java.util.Objects;

/**
 * Tracks the origin of a draft schema element.
 *
 * <p>Confidence is expected to be in [0.0, 1.0] but is not enforced yet — callers should
 * supply sensible values. {@code source} and {@code ruleId} must be non-null; {@code ruleId}
 * may be empty.
 */
public record Provenance(Source source, String ruleId, double confidence) {

    public Provenance {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(ruleId, "ruleId");
    }

    public enum Source {
        RULE,
        LLM,
        USER
    }
}
