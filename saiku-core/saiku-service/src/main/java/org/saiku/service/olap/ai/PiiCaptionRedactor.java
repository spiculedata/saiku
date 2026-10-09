/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import java.util.HashSet;
import java.util.Set;
import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.dto.resultset.MemberCell;

/**
 * Redacts member captions drawn from a {@code saiku.semantic.pii=true} level (saiku#902) out of an
 * executed {@link CellDataSet}, in place, before the result crosses to an LLM.
 *
 * <p>Closes a gap the schema-level redaction in {@link AiSchema#toAgentView()} doesn't cover: that
 * projection strips PII markers from the {@code /ai/schema} METADATA response (names, descriptions,
 * sample members), but a PII-tagged level is still a perfectly queryable row/column axis — {@code
 * /ai/query} returns its member captions verbatim (saiku#910 audit). Callers that build an LLM
 * prompt from a {@link CellDataSet} (e.g. the dashboard narrative endpoint) must run this pass
 * first. Measure VALUES are never touched, only member captions — the issue's own contract is
 * "values still in prompt but captions redacted".
 */
public final class PiiCaptionRedactor {

    /** Replacement text for a redacted caption. Matches the schema-level redaction's convention. */
    public static final String REDACTED = "[REDACTED]";

    private PiiCaptionRedactor() {}

    /**
     * Redacts {@code cds} in place using {@code schema}'s level PII flags. No-op (and safe) when
     * either argument is {@code null}, or when the schema has no PII-tagged levels.
     *
     * @return the number of member cells redacted
     */
    public static int redact(CellDataSet cds, AiSchema schema) {
        if (cds == null || schema == null) {
            return 0;
        }
        Set<String> piiLevels = piiLevelUniqueNames(schema);
        if (piiLevels.isEmpty()) {
            return 0;
        }
        int count = redactGrid(cds.getCellSetHeaders(), piiLevels);
        count += redactGrid(cds.getCellSetBody(), piiLevels);
        return count;
    }

    private static int redactGrid(AbstractBaseCell[][] grid, Set<String> piiLevels) {
        if (grid == null) {
            return 0;
        }
        int count = 0;
        for (AbstractBaseCell[] row : grid) {
            if (row == null) {
                continue;
            }
            for (AbstractBaseCell cell : row) {
                if (cell instanceof MemberCell mc && mc.getLevel() != null && piiLevels.contains(mc.getLevel())) {
                    mc.setFormattedValue(REDACTED);
                    mc.setRawValue(REDACTED);
                    count++;
                }
            }
        }
        return count;
    }

    /** Every level unique name across the schema's dimensions/hierarchies flagged {@code pii}. */
    private static Set<String> piiLevelUniqueNames(AiSchema schema) {
        Set<String> out = new HashSet<>();
        for (AiSchema.Dimension d : schema.dimensions.values()) {
            for (AiSchema.Hierarchy h : d.hierarchies.values()) {
                for (AiSchema.Level l : h.levels.values()) {
                    if (l.pii) {
                        out.add(l.uniqueName);
                    }
                }
            }
        }
        return out;
    }
}
