/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.export.semantic;

import org.saiku.service.ossie.OssieModelDto;

/**
 * Entry point for "Semantic Layer Sync" (saiku#1427): renders an {@link OssieModelDto} into a
 * downloadable BI-tool artefact. Stateless — safe to share a single instance, and cheap enough
 * ({@link TableauTdsExporter}/{@link SupersetDatasetYamlExporter} hold no state of their own
 * beyond the last call's diagnostics) that callers may also construct one per request.
 */
public class SemanticExportService {

    private final TableauTdsExporter tableauExporter = new TableauTdsExporter();
    private final SupersetDatasetYamlExporter supersetExporter = new SupersetDatasetYamlExporter();

    /**
     * @param tool case-insensitive; one of {@code tableau}, {@code superset}
     * @throws SemanticExportException on an unknown tool or a model that can't be rendered (e.g.
     *     no datasets)
     */
    public SemanticExportResult export(OssieModelDto model, String tool) {
        return export(model, SemanticExportTool.parse(tool));
    }

    public SemanticExportResult export(OssieModelDto model, SemanticExportTool tool) {
        switch (tool) {
            case TABLEAU:
                return tableauExporter.export(model);
            case SUPERSET:
                return supersetExporter.export(model);
            default:
                throw new SemanticExportException("Unhandled export tool: " + tool);
        }
    }
}
