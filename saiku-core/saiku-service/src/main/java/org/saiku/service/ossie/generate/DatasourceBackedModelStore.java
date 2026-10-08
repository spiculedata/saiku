/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.generate;

import java.util.Optional;
import org.saiku.service.datasource.DatasourceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link GeneratedModelStore} backed by Saiku's repository, writing next to the schema files under
 * {@code /datasources/}.
 *
 * <p>File names follow the issue's contract: {@code <datasource>.generated.yaml} and {@code
 * <datasource>.generated.rationale.md}. The stem is sanitised to a single path segment — a model
 * name is caller-supplied, and a {@code ../} in it must not be able to write outside
 * {@code /datasources/}.
 */
public final class DatasourceBackedModelStore implements GeneratedModelStore {

    private static final Logger LOG = LoggerFactory.getLogger(DatasourceBackedModelStore.class);

    private static final String DATASOURCES_PREFIX = "/datasources/";
    private static final String YAML_SUFFIX = ".generated.yaml";
    private static final String RATIONALE_SUFFIX = ".generated.rationale.md";
    private static final String FILE_TYPE = "nt:saikufiles";

    private final DatasourceService datasourceService;

    public DatasourceBackedModelStore(DatasourceService datasourceService) {
        this.datasourceService = datasourceService;
    }

    @Override
    public Optional<String> readExisting(String dataSourceId, String modelName) {
        String path = DATASOURCES_PREFIX + stem(dataSourceId, modelName) + YAML_SUFFIX;
        try {
            String data = datasourceService.getInternalFileData(path);
            return (data == null || data.isEmpty()) ? Optional.empty() : Optional.of(data);
        } catch (RuntimeException e) {
            LOG.warn("Failed reading previously generated Ossie model at {}: {}", path, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public WrittenPaths pathsFor(String dataSourceId, String modelName) {
        String stem = stem(dataSourceId, modelName);
        return new WrittenPaths(DATASOURCES_PREFIX + stem + YAML_SUFFIX, DATASOURCES_PREFIX + stem + RATIONALE_SUFFIX);
    }

    @Override
    public void write(String dataSourceId, String modelName, String yaml, String rationale) {
        WrittenPaths paths = pathsFor(dataSourceId, modelName);
        String yamlStatus = datasourceService.saveInternalFile(paths.yamlPath(), yaml, FILE_TYPE);
        String rationaleStatus = datasourceService.saveInternalFile(paths.rationalePath(), rationale, FILE_TYPE);
        LOG.info(
                "Generated Ossie model for '{}' → {} ({}) + rationale → {} ({})",
                dataSourceId,
                paths.yamlPath(),
                yamlStatus,
                paths.rationalePath(),
                rationaleStatus);
    }

    /**
     * One path segment, no separators and no dot-runs. Falls back to the data-source id when the
     * model name is blank, and to a fixed placeholder when neither is usable — an unwritable run
     * is a clearer failure than a path-traversal write.
     *
     * <p>Note that {@code .} is a legal filename character, so the character filter alone leaves
     * {@code ../..} sequences intact in the output (as {@code .._..}). That still cannot escape
     * {@code /datasources/} because the separators are gone, but a name containing {@code ..} is
     * ambiguous to read and to any downstream path handling, so dot-runs are collapsed too.
     */
    private static String stem(String dataSourceId, String modelName) {
        String raw = (modelName == null || modelName.isBlank()) ? dataSourceId : modelName;
        if (raw == null || raw.isBlank()) {
            return "generated";
        }
        String cleaned = raw.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("\\.{2,}", "_");
        // A name that was nothing but dots collapsed to empty — keep a usable stem.
        return cleaned.isBlank() ? "generated" : cleaned;
    }
}
