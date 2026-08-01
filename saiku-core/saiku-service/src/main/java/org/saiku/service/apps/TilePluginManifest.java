/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.apps;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An admin-installed dashboard tile plugin: a small, self-contained HTML widget the App Builder can
 * embed as an {@code srcdoc} iframe tile.
 *
 * <p>Plugins live under {@code saiku-home/tile-plugins/<id>/} as two files — {@code plugin.json}
 * (this manifest) and {@code plugin.html} (the srcdoc source). The security model (App Builder
 * Phase 2) is deliberately narrow: Tier-2 plugin HTML is <strong>admin-installed</strong> — dropped
 * into the home directory by an operator — never supplied by arbitrary dashboard authors. This
 * registry is therefore a trusted source of plugin HTML; authoring users only ever pick a plugin by
 * {@code id}, they never inject markup.
 *
 * <p>The {@code id} is the authoritative key. It is validated to a safe slug ({@code [a-z0-9-]+}) so
 * it is safe to use both in a URL path segment and as a filesystem directory name — no traversal.
 *
 * <p>Fields are immutable; the record is safe to hand to concurrent readers.
 */
public record TilePluginManifest(String id, String label, JsonNode optionSchema, String sourcePath) {

    public TilePluginManifest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(sourcePath, "sourcePath");
        if (id.isBlank()) {
            throw new IllegalArgumentException("tile plugin id must be non-blank");
        }
        if (label.isBlank()) {
            throw new IllegalArgumentException("tile plugin label must be non-blank");
        }
    }

    /**
     * A compact, REST-safe projection: {@code id}, {@code label}, and (when present) the
     * {@code optionSchema}. Omits {@code sourcePath} — the on-disk layout is not the client's
     * business — and omits {@code optionSchema} entirely when the plugin declares none, so the
     * catalogue payload stays lean.
     */
    public Map<String, Object> asSummary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("label", label);
        if (optionSchema != null && !optionSchema.isNull()) {
            m.put("optionSchema", optionSchema);
        }
        return m;
    }
}
