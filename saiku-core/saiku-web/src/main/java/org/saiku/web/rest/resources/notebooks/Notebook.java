/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.notebooks;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

/**
 * Top-level notebook document (issue #1108). Persisted as a single {@code
 * .saikunb} JSON file in the JCR repository, next to {@code .saiku} queries
 * and {@code .saikudash} dashboards.
 *
 * <p>{@link NotebookResource} treats this document as opaque (raw {@code
 * JsonNode} passthrough — see the class comment there and saiku#1179): this
 * typed model exists only so {@link org.saiku.web.rest.resources.share.NotebookShareViewResource}
 * can enumerate cells to re-run their queries under the share owner's
 * identity. {@code @JsonIgnoreProperties(ignoreUnknown = true)} so a UI-owned
 * field this model doesn't catalogue never breaks the share-view read path.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Notebook {

    /** Stable UUID. Survives renames; used for cross-references. */
    public String id;

    /** Human-readable name shown in the catalogue + editor. */
    public String name;

    /** Schema version. Bumped on incompatible changes; v1 = 1. */
    public int version = 1;

    /** Cells, top-to-bottom in execution/display order. */
    public List<NotebookCell> cells = new ArrayList<>();
}
