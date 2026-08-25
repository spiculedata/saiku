/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.generate.enrich.provider;

import java.util.Map;
import org.saiku.service.schema.generate.enrich.SuggestionSet;

/**
 * Output from an {@link LlmProvider}.
 *
 * <p>Thin wrapper around a {@link SuggestionSet} with an optional {@code metadata} bag for
 * provider-specific information such as elapsed time, model identifier, or token counts. The
 * wrapper exists so the provider contract stays stable when future providers want to surface
 * diagnostics without changing the {@code SuggestionSet} shape.
 */
public record EnrichResponse(SuggestionSet suggestions, Map<String, Object> metadata) {

    public EnrichResponse {
        if (suggestions == null) {
            throw new NullPointerException("suggestions");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** Convenience constructor with no metadata. */
    public EnrichResponse(SuggestionSet suggestions) {
        this(suggestions, Map.of());
    }
}
