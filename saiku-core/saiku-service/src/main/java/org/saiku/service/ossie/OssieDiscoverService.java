/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie;

import bi.saiku.ossie.OssieYamlReader;
import bi.saiku.ossie.model.CustomExtension;
import bi.saiku.ossie.model.Dataset;
import bi.saiku.ossie.model.DialectExpression;
import bi.saiku.ossie.model.Field;
import bi.saiku.ossie.model.Metric;
import bi.saiku.ossie.model.OssieDocument;
import bi.saiku.ossie.model.Relationship;
import bi.saiku.ossie.model.SemanticModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.saiku.datasources.connection.ISaikuConnection;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.service.datasource.IDatasourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Read one Ossie {@code semantic_model} for a named {@code OSSIE} datasource and project it into
 * the {@link OssieModelDto} tree the frontend renders. This is the semantic-layer analogue of
 * {@code OlapMetaExplorer.getConnection} for the MDX side — same role in the discover pipeline,
 * different query language downstream.
 *
 * <p>The YAML file's path lives in the datasource properties under {@link
 * ISaikuConnection#OSSIE_YAML_KEY}; the model name (when the YAML has multiple {@code
 * semantic_model[]} entries) lives under {@code schema} or {@link
 * ISaikuConnection#OSSIE_MODEL_KEY}. Defaults to the first semantic model if unspecified.
 */
public class OssieDiscoverService {

    private static final Logger log = LoggerFactory.getLogger(OssieDiscoverService.class);

    private static final String SEMANTIC_PREFIX = "saiku.semantic.";
    private static final String VENDOR_SAIKU = "SAIKU";

    private final ObjectMapper json = new ObjectMapper();

    private IDatasourceManager datasourceManager;

    public OssieDiscoverService() {}

    @Autowired
    public void setDatasourceManager(IDatasourceManager mgr) {
        this.datasourceManager = mgr;
    }

    /**
     * Return the Ossie model tree for a connection. Throws {@link IllegalArgumentException} if
     * the connection isn't OSSIE-typed or the YAML file is missing / unreadable.
     */
    public OssieModelDto getModel(String connectionName) {
        SaikuDatasource ds = datasourceManager.getDatasource(connectionName);
        if (ds == null) {
            throw new IllegalArgumentException("No datasource named '" + connectionName + "'");
        }
        if (ds.getType() != SaikuDatasource.Type.OSSIE) {
            throw new IllegalArgumentException(
                    "Datasource '" + connectionName + "' is not an OSSIE datasource (type=" + ds.getType() + ")");
        }
        String yamlPath = ds.getProperties().getProperty(ISaikuConnection.OSSIE_YAML_KEY);
        if (yamlPath == null || yamlPath.isBlank()) {
            throw new IllegalArgumentException("Ossie datasource '" + connectionName + "' has no '"
                    + ISaikuConnection.OSSIE_YAML_KEY + "' property");
        }
        Path yaml = Path.of(yamlPath);
        if (!Files.isReadable(yaml)) {
            throw new IllegalArgumentException("Ossie YAML not readable at " + yamlPath);
        }
        OssieDocument doc;
        try {
            doc = new OssieYamlReader().read(yaml);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read Ossie YAML at " + yamlPath + ": " + e.getMessage(), e);
        }
        if (doc.getEffectiveSemanticModels().isEmpty()) {
            throw new IllegalArgumentException("Ossie YAML at " + yamlPath + " has zero semantic models");
        }
        // Pick the requested model or default to the first entry. schema and OSSIE_MODEL_KEY
        // both mean "which semantic_model[] to pick"; schema is what the .sds writes, the
        // constant is the wire-level property key.
        String modelName = ds.getProperties().getProperty(ISaikuConnection.OSSIE_MODEL_KEY);
        if (modelName == null || modelName.isBlank()) {
            modelName = ds.getProperties().getProperty("schema");
        }
        SemanticModel picked = null;
        if (modelName != null && !modelName.isBlank()) {
            for (SemanticModel m : doc.getEffectiveSemanticModels()) {
                if (modelName.equals(m.getName())) {
                    picked = m;
                    break;
                }
            }
            if (picked == null) {
                log.warn(
                        "Ossie datasource '{}' asked for model '{}' but YAML only has {} — falling back to first",
                        connectionName,
                        modelName,
                        doc.getEffectiveSemanticModels().stream()
                                .map(SemanticModel::getName)
                                .toList());
            }
        }
        if (picked == null) {
            picked = doc.getEffectiveSemanticModels().get(0);
        }
        return project(connectionName, picked);
    }

    private OssieModelDto project(String connectionName, SemanticModel src) {
        OssieModelDto out = new OssieModelDto();
        out.setConnection(connectionName);
        out.setName(src.getName());
        out.setDescription(src.getDescription());
        for (Dataset d : src.getDatasets()) {
            out.getDatasets().add(projectDataset(d));
        }
        for (Metric m : src.getMetrics()) {
            out.getMetrics().add(projectMetric(m));
        }
        for (Relationship r : src.getRelationships()) {
            out.getRelationships().add(projectRelationship(r));
        }
        return out;
    }

    private OssieModelDto.Dataset projectDataset(Dataset src) {
        OssieModelDto.Dataset d = new OssieModelDto.Dataset();
        d.setName(src.getName());
        d.setSource(src.getSource());
        d.setDescription(src.getDescription());
        d.setPrimaryKey(new ArrayList<>(src.getPrimaryKey()));
        for (Field f : src.getFields()) {
            d.getFields().add(projectField(f));
        }
        return d;
    }

    private OssieModelDto.Field projectField(Field src) {
        OssieModelDto.Field f = new OssieModelDto.Field();
        f.setName(src.getName());
        f.setExpression(pickAnsiExpression(
                src.getExpression() == null ? List.of() : src.getExpression().getDialects()));
        f.setLabel(src.getLabel());
        f.setDescription(src.getDescription());
        f.setTime(src.getDimension() != null
                && Boolean.TRUE.equals(src.getDimension().getIsTime()));
        f.setPii(readPii(src.getCustomExtensions()));
        return f;
    }

    private OssieModelDto.Metric projectMetric(Metric src) {
        OssieModelDto.Metric m = new OssieModelDto.Metric();
        m.setName(src.getName());
        m.setExpression(pickAnsiExpression(
                src.getExpression() == null ? List.of() : src.getExpression().getDialects()));
        m.setDescription(src.getDescription());
        m.setAggregationKind(readAggregationKind(src.getCustomExtensions()));
        return m;
    }

    private OssieModelDto.Relationship projectRelationship(Relationship src) {
        OssieModelDto.Relationship r = new OssieModelDto.Relationship();
        r.setName(src.getName());
        r.setFrom(src.getFrom());
        r.setTo(src.getTo());
        r.setFromColumns(new ArrayList<>(src.getFromColumns()));
        r.setToColumns(new ArrayList<>(src.getToColumns()));
        return r;
    }

    private static String pickAnsiExpression(List<DialectExpression> dialects) {
        for (DialectExpression d : dialects) {
            if ("ANSI_SQL".equalsIgnoreCase(d.getDialect())) return d.getExpression();
        }
        // No ANSI dialect — leave null so the frontend hides the raw expression.
        return null;
    }

    /**
     * Look up {@code saiku.semantic.pii: true} inside a SAIKU vendor extension. The exporter
     * writes {@code custom_extensions: [{vendor_name: SAIKU, data: '{"pii":true, ...}'}]}; we
     * parse the JSON blob and return the pii boolean.
     */
    private boolean readPii(List<CustomExtension> extensions) {
        for (CustomExtension ext : extensions) {
            if (!VENDOR_SAIKU.equals(ext.getVendorName())) continue;
            if (ext.getData() == null || ext.getData().isBlank()) continue;
            try {
                ObjectNode node = (ObjectNode) json.readTree(ext.getData());
                if (node.has("pii") && node.get("pii").asBoolean(false)) return true;
            } catch (IOException | ClassCastException ignored) {
                // Best-effort: a mangled extension payload shouldn't fail the whole discover.
            }
        }
        return false;
    }

    /** Read {@code aggregation_kind} out of the SAIKU vendor extension on a metric, or null. */
    private String readAggregationKind(List<CustomExtension> extensions) {
        for (CustomExtension ext : extensions) {
            if (!VENDOR_SAIKU.equals(ext.getVendorName())) continue;
            if (ext.getData() == null || ext.getData().isBlank()) continue;
            try {
                ObjectNode node = (ObjectNode) json.readTree(ext.getData());
                if (node.has("aggregation_kind"))
                    return node.get("aggregation_kind").asText(null);
            } catch (IOException | ClassCastException ignored) {
                // See readPii — best-effort.
            }
        }
        return null;
    }
}
