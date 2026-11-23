/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.converter;

import java.util.ArrayList;
import java.util.List;

/**
 * The outcome of validating a converted model: the per-element findings, the element counts the
 * UI previews, and a per-dataset roll-up.
 *
 * <p>{@link #hasErrors()} is the gate the save step checks — a report with only warnings and
 * info still saves (that's the issue's "esoterica degrade to warnings" rule), one with an error
 * does not, because an error means the model can't be joined or aliased as written.
 */
public class OssieValidationReport {

    private final List<ConversionDiagnostic> diagnostics;
    private final int datasetCount;
    private final int fieldCount;
    private final int metricCount;
    private final int relationshipCount;
    private final List<DatasetSummary> datasets;

    public OssieValidationReport(
            List<ConversionDiagnostic> diagnostics,
            int datasetCount,
            int fieldCount,
            int metricCount,
            int relationshipCount,
            List<DatasetSummary> datasets) {
        List<ConversionDiagnostic> sorted = new ArrayList<>(diagnostics);
        sorted.sort(null);
        this.diagnostics = sorted;
        this.datasetCount = datasetCount;
        this.fieldCount = fieldCount;
        this.metricCount = metricCount;
        this.relationshipCount = relationshipCount;
        this.datasets = datasets;
    }

    public List<ConversionDiagnostic> getDiagnostics() {
        return diagnostics;
    }

    public int getDatasetCount() {
        return datasetCount;
    }

    public int getFieldCount() {
        return fieldCount;
    }

    public int getMetricCount() {
        return metricCount;
    }

    public int getRelationshipCount() {
        return relationshipCount;
    }

    public List<DatasetSummary> getDatasets() {
        return datasets;
    }

    public int getErrorCount() {
        return count(ConversionDiagnostic.Severity.ERROR);
    }

    public int getWarningCount() {
        return count(ConversionDiagnostic.Severity.WARNING);
    }

    public int getInfoCount() {
        return count(ConversionDiagnostic.Severity.INFO);
    }

    public boolean hasErrors() {
        return getErrorCount() > 0;
    }

    private int count(ConversionDiagnostic.Severity severity) {
        return (int)
                diagnostics.stream().filter(d -> d.getSeverity() == severity).count();
    }

    /** One dataset line of the preview table: name, source table, field count, key count. */
    public static class DatasetSummary {
        private final String name;
        private final String source;
        private final int fieldCount;
        private final int primaryKeyCount;

        public DatasetSummary(String name, String source, int fieldCount, int primaryKeyCount) {
            this.name = name;
            this.source = source;
            this.fieldCount = fieldCount;
            this.primaryKeyCount = primaryKeyCount;
        }

        public String getName() {
            return name;
        }

        public String getSource() {
            return source;
        }

        public int getFieldCount() {
            return fieldCount;
        }

        public int getPrimaryKeyCount() {
            return primaryKeyCount;
        }
    }
}
