/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.export.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.saiku.service.ossie.OssieModelDto;

/**
 * {@link OssieModelDto} &rarr; Superset/Preset "dataset export" bundle (saiku#1427, "Semantic
 * Layer Sync") — the same zip shape Superset's {@code Settings > Database Connections > Export}
 * / {@code superset import-datasources} round-trips: a root {@code metadata.yaml}, one {@code
 * databases/<connection>.yaml}, and one {@code datasets/<connection>/<dataset>.yaml} per Ossie
 * dataset.
 *
 * <p><b>Deliberately out of scope for this first cut</b> (tracked on the parent issue):
 *
 * <ul>
 *   <li><b>Live {@code sqlalchemy_uri}.</b> Saiku's Ossie warehouse URL is an arbitrary JDBC URL;
 *       there's no general JDBC-URL-to-SQLAlchemy-URI mapping, so {@code databases/*.yaml} emits
 *       a placeholder the analyst completes locally (same posture as the Tableau exporter's
 *       blank connection — see {@link TableauTdsExporter}).
 *   <li><b>Cross-dataset metrics.</b> A Superset dataset is one physical/virtual table, so a
 *       metric expression that only references its own dataset's columns has those references
 *       unwrapped to bare column names (Superset metric SQL is relative to the table); an
 *       expression touching another dataset is emitted with its {@code dataset.field} references
 *       left bracket-free — Superset would need that dataset joined via a virtual SQL dataset or
 *       the metric split by hand.
 * </ul>
 */
public class SupersetDatasetYamlExporter {

    private static final Pattern QUALIFIED_REF =
            Pattern.compile("\"?([A-Za-z_][A-Za-z0-9_]*)\"?\\s*\\.\\s*\"?([A-Za-z_][A-Za-z0-9_]*)\"?");

    private final ObjectMapper yaml =
            new ObjectMapper(new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER));

    public SemanticExportResult export(OssieModelDto model) {
        if (model.getDatasets().isEmpty()) {
            throw new SemanticExportException(
                    "Ossie model '" + model.getName() + "' has no datasets — nothing to export to Superset.");
        }
        try {
            String connectionSlug = OssieExportModelSupport.slug(
                    model.getConnection() != null ? model.getConnection() : model.getName());
            UUID databaseUuid = UUID.nameUUIDFromBytes(("saiku-db:" + connectionSlug).getBytes(StandardCharsets.UTF_8));

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(buf)) {
                writeEntry(zip, "metadata.yaml", metadataYaml(model));
                writeEntry(
                        zip,
                        "databases/" + connectionSlug + ".yaml",
                        databaseYaml(model, connectionSlug, databaseUuid));
                for (OssieModelDto.Dataset ds : model.getDatasets()) {
                    String path =
                            "datasets/" + connectionSlug + "/" + OssieExportModelSupport.slug(ds.getName()) + ".yaml";
                    writeEntry(zip, path, datasetYaml(model, ds, databaseUuid));
                }
            }
            String filename = OssieExportModelSupport.slug(model.getName()) + "-superset-export.zip";
            return new SemanticExportResult(filename, "application/zip", buf.toByteArray());
        } catch (SemanticExportException e) {
            throw e;
        } catch (Exception e) {
            throw new SemanticExportException(
                    "Failed to render Superset dataset export for '" + model.getName() + "': " + e.getMessage(), e);
        }
    }

    private void writeEntry(ZipOutputStream zip, String path, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private String metadataYaml(OssieModelDto model) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", "1.0.0");
        m.put("type", "Dataset");
        m.put("timestamp", Instant.now().toString());
        return yaml.writeValueAsString(m);
    }

    private String databaseYaml(OssieModelDto model, String connectionSlug, UUID databaseUuid) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("database_name", model.getConnection() != null ? model.getConnection() : model.getName());
        // Placeholder — see class javadoc. Superset refuses to import a database YAML with a
        // syntactically invalid URI, so this uses SQLAlchemy's documented "sqlite" no-op scheme
        // rather than a string Superset would reject outright; the analyst must replace it before
        // importing against a real warehouse.
        m.put("sqlalchemy_uri", "sqlite:///CHANGE_ME_" + connectionSlug + ".db");
        m.put("cache_timeout", null);
        m.put("expose_in_sqllab", true);
        m.put("allow_run_async", false);
        m.put("uuid", databaseUuid.toString());
        m.put("version", "1.0.0");
        return yaml.writeValueAsString(m);
    }

    private String datasetYaml(OssieModelDto model, OssieModelDto.Dataset ds, UUID databaseUuid) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("table_name", ds.getName());
        m.put("main_dttm_col", firstTimeField(ds));
        m.put("description", ds.getDescription());
        m.put("default_endpoint", null);
        m.put("offset", 0);
        m.put("cache_timeout", null);
        m.put("schema", null);
        m.put("sql", null);
        m.put("params", null);
        m.put("template_params", null);
        m.put("filter_select_enabled", true);
        m.put("fetch_values_predicate", null);

        java.util.List<Map<String, Object>> metrics = new java.util.ArrayList<>();
        for (OssieModelDto.Metric metric : model.getMetrics()) {
            if (OssieExportModelSupport.resolveMetricDataset(model, metric) != ds) continue;
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("metric_name", metric.getName());
            mm.put("verbose_name", metric.getDisplayCaption() != null ? metric.getDisplayCaption() : metric.getName());
            mm.put("metric_type", metric.getAggregationKind());
            mm.put("expression", toSupersetExpression(metric.getExpression(), ds));
            mm.put("description", metric.getDescription());
            mm.put("d3format", null);
            mm.put("currency", null);
            mm.put("warning_text", null);
            metrics.add(mm);
        }
        m.put("metrics", metrics);

        java.util.List<Map<String, Object>> columns = new java.util.ArrayList<>();
        for (OssieModelDto.Field f : ds.getFields()) {
            Map<String, Object> cc = new LinkedHashMap<>();
            cc.put("column_name", f.getName());
            cc.put(
                    "verbose_name",
                    f.getDisplayCaption() != null
                            ? f.getDisplayCaption()
                            : (f.getLabel() != null ? f.getLabel() : null));
            cc.put("is_dttm", f.isTime());
            cc.put("is_active", !f.isDisplayHidden());
            cc.put("type", f.isTime() ? "TIMESTAMP" : "VARCHAR");
            cc.put("groupby", true);
            cc.put("filterable", true);
            cc.put("expression", null);
            cc.put("description", f.getDescription());
            cc.put("python_date_format", null);
            columns.add(cc);
        }
        m.put("columns", columns);

        m.put("version", "1.0.0");
        m.put("database_uuid", databaseUuid.toString());
        return yaml.writeValueAsString(m);
    }

    private String firstTimeField(OssieModelDto.Dataset ds) {
        for (OssieModelDto.Field f : ds.getFields()) {
            if (f.isTime()) return f.getName();
        }
        return null;
    }

    /**
     * Superset metric/column expressions are SQL relative to the dataset's own table. References
     * to the owning dataset are unwrapped to a bare column name; references to another dataset are
     * left as {@code dataset.field} (unresolvable without a virtual SQL dataset joining the two —
     * see class javadoc).
     */
    private String toSupersetExpression(String expression, OssieModelDto.Dataset owner) {
        if (expression == null || expression.isBlank()) {
            return "COUNT(*)";
        }
        Matcher m = QUALIFIED_REF.matcher(expression);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(expression, last, m.start());
            if (m.group(1).equalsIgnoreCase(owner.getName())) {
                out.append(m.group(2));
            } else {
                out.append(m.group(1)).append('.').append(m.group(2));
            }
            last = m.end();
        }
        out.append(expression.substring(last));
        return out.toString();
    }
}
