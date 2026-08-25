/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.generate.enrich;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import org.saiku.service.schema.generate.enrich.ops.SuggestionOp;

/**
 * Aggregate of {@link SuggestionOp}s produced by the enrichment layer for a single draft-schema
 * run. Exposes its backing list directly; callers may use {@link #add(SuggestionOp)} as a
 * convenience.
 *
 * <p>The {@code degraded} flag is set to {@code true} when an upstream enricher (typically the LLM
 * client) fell back to a no-op mid-run — e.g. on rate-limit, parse failure, or classifier refusal.
 * Consumers can use it to surface a warning in the UI without inspecting every op.
 */
public class SuggestionSet {

    private List<SuggestionOp> ops = new ArrayList<>();
    private boolean degraded;

    public SuggestionSet() {}

    @JsonProperty("ops")
    public List<SuggestionOp> ops() {
        return ops;
    }

    @JsonProperty("ops")
    public void setOps(List<SuggestionOp> ops) {
        this.ops = ops == null ? new ArrayList<>() : ops;
    }

    public SuggestionSet add(SuggestionOp op) {
        ops.add(op);
        return this;
    }

    @JsonProperty("degraded")
    public boolean degraded() {
        return degraded;
    }

    @JsonProperty("degraded")
    public void setDegraded(boolean degraded) {
        this.degraded = degraded;
    }
}
