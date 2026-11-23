/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.history;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One archived write of an Ossie (M4) semantic-model YAML file (issue #1121, Phase 1). Stored as
 * a single JSON line in a per-target {@code saiku-schema-history-*.jsonl} file (see {@link
 * SchemaHistoryService}). Unlike {@code DashboardVersion} (which only records the replaced
 * state), this keeps both sides of the write so the REST layer can render a raw before/after
 * diff without a second lookup.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SchemaVersion {

    /** Stable id (UUID). */
    public String id;

    /** Epoch millis the write was recorded. */
    public long createdAt;

    /** Username that performed the write. */
    public String author;

    /** {@code CREATE} when no prior YAML existed, {@code UPDATE} otherwise. */
    public String action;

    /** The Ossie datasource name the YAML belongs to (one YAML file per datasource today). */
    public String target;

    /** YAML content before this write; {@code null} for the first write ({@code CREATE}). */
    public String oldYaml;

    /** YAML content after this write. */
    public String newYaml;

    public SchemaVersion() {}
}
