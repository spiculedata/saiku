/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie;

import bi.saiku.ossie.OssieSynonymIndex;
import bi.saiku.ossie.OssieYamlReader;
import bi.saiku.ossie.model.CustomExtension;
import bi.saiku.ossie.model.Dataset;
import bi.saiku.ossie.model.DialectExpression;
import bi.saiku.ossie.model.Field;
import bi.saiku.ossie.model.Metric;
import bi.saiku.ossie.model.OssieDocument;
import bi.saiku.ossie.model.Relationship;
import bi.saiku.ossie.model.SemanticModel;
import com.fasterxml.jackson.databind.JsonNode;
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
     * Return the ontology block from the Ossie document associated with a connection. Empty when
     * the document has no {@code ontology:} section. Same connection-validation rules as
     * {@link #getModel(String)} — throws {@link IllegalArgumentException} on unknown / non-Ossie
     * datasources or missing YAML.
     */
    public List<bi.saiku.ossie.model.ontology.OntologyEntry> getOntology(String connectionName) {
        OssieDocument doc = readDocument(connectionName);
        return doc.getOntology();
    }

    /**
     * Load and parse the underlying {@link OssieDocument} for a connection. Kept package-private
     * because callers should prefer the projected {@link OssieModelDto} — direct document access
     * exists for the ontology surface (which has no OssieModelDto equivalent) and future spec
     * blocks that don't fit the semantic-model projection.
     */
    OssieDocument readDocument(String connectionName) {
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
        try {
            return new OssieYamlReader().read(yaml);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read Ossie YAML at " + yamlPath + ": " + e.getMessage(), e);
        }
    }

    /**
     * Return the Ossie model tree for a connection. Throws {@link IllegalArgumentException} if
     * the connection isn't OSSIE-typed or the YAML file is missing / unreadable.
     */
    public OssieModelDto getModel(String connectionName) {
        OssieDocument doc = readDocument(connectionName);
        if (doc.getEffectiveSemanticModels().isEmpty()) {
            throw new IllegalArgumentException("Ossie YAML for '" + connectionName + "' has zero semantic models");
        }
        SaikuDatasource ds = datasourceManager.getDatasource(connectionName);
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
        // Synonym indices come from bi.saiku.ossie:ossie-core so every consumer of the AI schema
        // (agents, validators, workbench "did you mean" hints) reads canonicalisation off the DTO
        // rather than rewalking the source. Populated once at DTO-build time — cheap enough that
        // an empty synonyms case doesn't need a fast path.
        out.setFieldAliases(OssieSynonymIndex.buildFieldIndex(src));
        out.setMetricAliases(OssieSynonymIndex.buildMetricIndex(src));
        out.setDatasetAliases(OssieSynonymIndex.buildDatasetIndex(src));
        return out;
    }

    private OssieModelDto.Dataset projectDataset(Dataset src) {
        OssieModelDto.Dataset d = new OssieModelDto.Dataset();
        d.setName(src.getName());
        d.setSource(src.getSource());
        d.setDescription(src.getDescription());
        d.setPrimaryKey(new ArrayList<>(src.getPrimaryKey()));
        d.setCustomExtensions(projectCustomExtensions(src.getCustomExtensions()));
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
        f.setCustomExtensions(projectCustomExtensions(src.getCustomExtensions()));
        applyWellKnownExtensionsToField(f, src.getCustomExtensions());
        return f;
    }

    private OssieModelDto.Metric projectMetric(Metric src) {
        OssieModelDto.Metric m = new OssieModelDto.Metric();
        m.setName(src.getName());
        m.setExpression(pickAnsiExpression(
                src.getExpression() == null ? List.of() : src.getExpression().getDialects()));
        m.setDescription(src.getDescription());
        m.setAggregationKind(readAggregationKind(src.getCustomExtensions()));
        m.setCustomExtensions(projectCustomExtensions(src.getCustomExtensions()));
        applyWellKnownExtensionsToMetric(m, src.getCustomExtensions());
        return m;
    }

    /**
     * Overlay the {@code saiku.display}, {@code saiku.roles}, and {@code saiku.pii} well-knowns
     * (saiku#1409) onto a projected field. Absent extensions leave the field unchanged.
     */
    private static void applyWellKnownExtensionsToField(OssieModelDto.Field f, List<CustomExtension> src) {
        SaikuWellKnownExtensions.Parsed w = SaikuWellKnownExtensions.read(src);
        if (w.isEmpty()) return;
        if (w.display() != null) {
            f.setDisplayCaption(w.display().caption());
            f.setDisplayFormat(w.display().format());
            f.setDisplayUnit(w.display().unit());
            f.setDisplayHidden(w.display().hidden());
        }
        if (w.roles() != null) {
            f.setAllowRoles(new ArrayList<>(w.roles().allow()));
            f.setDenyRoles(new ArrayList<>(w.roles().deny()));
        }
        if (w.pii() != null) {
            f.setPiiLevel(w.pii().name());
            // The legacy `pii` boolean stays true for any level — the graded form is additive.
            if (!f.isPii()) f.setPii(true);
        }
    }

    /** Same overlay for metrics. Metrics don't carry a pii boolean, so the pii level lands nowhere. */
    private static void applyWellKnownExtensionsToMetric(OssieModelDto.Metric m, List<CustomExtension> src) {
        SaikuWellKnownExtensions.Parsed w = SaikuWellKnownExtensions.read(src);
        if (w.isEmpty()) return;
        if (w.display() != null) {
            m.setDisplayCaption(w.display().caption());
            m.setDisplayFormat(w.display().format());
            m.setDisplayUnit(w.display().unit());
            m.setDisplayHidden(w.display().hidden());
        }
        if (w.roles() != null) {
            m.setAllowRoles(new ArrayList<>(w.roles().allow()));
            m.setDenyRoles(new ArrayList<>(w.roles().deny()));
        }
    }

    /**
     * Copy an OSI {@code custom_extensions[]} list into the DTO shape, parsing each entry's opaque
     * JSON {@code data} into a {@link JsonNode} so downstream consumers walk it structurally
     * without re-parsing.
     *
     * <p>Extensions whose payload declares {@code "visibility":"internal"} are dropped — that flag
     * is the standard escape hatch for tooling-private metadata that shouldn't reach an
     * agent-facing view (saiku#1409). Vendor-private extensions that omit the flag stay in.
     */
    private List<CustomExtensionDto> projectCustomExtensions(List<CustomExtension> src) {
        List<CustomExtensionDto> out = new ArrayList<>();
        if (src == null) return out;
        for (CustomExtension ext : src) {
            if (ext == null) continue;
            CustomExtensionDto dto = new CustomExtensionDto();
            dto.setVendorName(ext.getVendorName());
            JsonNode data = null;
            if (ext.getData() != null && !ext.getData().isBlank()) {
                try {
                    data = json.readTree(ext.getData());
                } catch (IOException e) {
                    log.debug(
                            "custom_extensions[{}].data is not valid JSON — dropping to null on the DTO: {}",
                            ext.getVendorName(),
                            e.getMessage());
                }
            }
            if (data != null) {
                JsonNode visibility = data.get("visibility");
                if (visibility != null && visibility.isTextual() && "internal".equals(visibility.asText())) {
                    continue;
                }
                dto.setData(data);
            }
            out.add(dto);
        }
        return out;
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
