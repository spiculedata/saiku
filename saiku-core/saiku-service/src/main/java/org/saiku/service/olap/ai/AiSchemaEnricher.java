/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import java.util.Map;

/**
 * Apply an {@link AiSchemaEnrichment} overlay to an {@link AiSchema}.
 * The enricher only sets {@code displayName} fields and copies the
 * suggestion list — canonical {@code name}/{@code uniqueName} fields are
 * never touched, so validation still resolves the agent's name set
 * exactly as before enrichment.
 */
public class AiSchemaEnricher {

    public void apply(AiSchema schema, AiSchemaEnrichment overlay) {
        if (schema == null || overlay == null) return;

        if (overlay.getRenames() != null) {
            for (Map.Entry<String, String> e : overlay.getRenames().entrySet()) {
                applyRename(schema, e.getKey(), e.getValue());
            }
        }

        if (overlay.getSuggestions() != null && !overlay.getSuggestions().isEmpty()) {
            schema.suggestions.addAll(overlay.getSuggestions());
        }
    }

    /* ------------------------------------------------------------------ */

    private static void applyRename(AiSchema schema, String path, String displayName) {
        if (path == null || displayName == null) return;
        String[] parts = path.split("\\.");
        if (parts.length == 0) return;

        if (parts.length == 2 && "measures".equals(parts[0])) {
            AiSchema.Measure m = schema.measures.get(AiSchema.key(parts[1]));
            if (m != null) m.displayName = displayName;
            return;
        }

        if (parts.length >= 2 && "dimensions".equals(parts[0])) {
            AiSchema.Dimension d = schema.dimensions.get(AiSchema.key(parts[1]));
            if (d == null) return;
            if (parts.length == 2) {
                d.displayName = displayName;
                return;
            }
            if (parts.length >= 4 && "hierarchies".equals(parts[2])) {
                AiSchema.Hierarchy h = d.hierarchies.get(AiSchema.key(parts[3]));
                if (h == null) return;
                if (parts.length == 4) {
                    h.displayName = displayName;
                    return;
                }
                if (parts.length == 6 && "levels".equals(parts[4])) {
                    AiSchema.Level l = h.levels.get(AiSchema.key(parts[5]));
                    if (l != null) l.displayName = displayName;
                }
            }
        }
    }
}
