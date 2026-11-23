/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.generate;

import java.util.Optional;

/**
 * Persistence boundary for the two artefacts a generation run produces.
 *
 * <p>Exists so {@link OssieModelGenerationService} stays decoupled from the repository layer and
 * so tests can assert on written content without a Saiku home. The production implementation is
 * {@link #datasourceBacked(DatasourceService)}.
 */
public interface GeneratedModelStore {

    /**
     * Read a previously-generated Ossie YAML for this data source / model name, if one exists.
     * A missing file is an empty {@link Optional}, not an error — that's the first-run path.
     * A genuine I/O fault surfaces as an empty result too, with a WARN at the implementation;
     * the run then proceeds as a first run rather than failing.
     */
    Optional<String> readExisting(String dataSourceId, String modelName);

    /**
     * The repository paths {@link #write} will use for this data source / model name. Exposed so
     * the caller can name them in the rationale document <em>before</em> writing — the rationale
     * is rendered in one pass, and a second write to patch a path into it would risk leaving a
     * half-updated pair on disk.
     */
    WrittenPaths pathsFor(String dataSourceId, String modelName);

    /**
     * Persist the emitted model and its rationale. Both must land or neither should — the
     * rationale is the audit half, and a model without one is the failure mode enterprise review
     * cares about most.
     */
    void write(String dataSourceId, String modelName, String yaml, String rationale);

    /** Repository paths of a generation run's two artefacts. */
    record WrittenPaths(String yamlPath, String rationalePath) {}
}
