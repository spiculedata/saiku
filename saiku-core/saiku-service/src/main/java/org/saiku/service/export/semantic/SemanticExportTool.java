/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.export.semantic;

import java.util.Locale;

/** BI tools {@link SemanticExportService} can render an Ossie model into. */
public enum SemanticExportTool {
    TABLEAU,
    SUPERSET;

    /** Case-insensitive lookup. Throws {@link SemanticExportException} naming the valid values on a miss. */
    public static SemanticExportTool parse(String value) {
        if (value != null) {
            for (SemanticExportTool tool : values()) {
                if (tool.name().equalsIgnoreCase(value)) return tool;
            }
        }
        throw new SemanticExportException("Unknown export tool '" + value + "' — expected one of: tableau, superset");
    }

    public String lowerName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
