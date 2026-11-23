/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.notebooks;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.saiku.service.olap.ai.AiCubeRef;

/**
 * One cell in a notebook (issue #1108). Polymorphic by {@link #type} — see
 * {@link Notebook} for why this partial typed model exists alongside the
 * opaque CRUD storage.
 *
 * <p>Phase 1 (this issue) covers {@code markdown} and {@code mdx} cells only;
 * a future {@code chart} cell type (phase 2 of #1108) renders the previous
 * cell's cellset and needs no new field here beyond {@link #type}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class NotebookCell {

    /** Stable per-cell id (UUID-ish). */
    public String id;

    /** {@code markdown | mdx}. */
    public String type;

    /** Optional display title. */
    public String name;

    /** Markdown source for {@code markdown} cells. */
    public String markdown;

    /** Raw MDX text for {@code mdx} cells, run against {@link #cube}. */
    public String mdx;

    /** Cube this cell's MDX runs against. Null for {@code markdown} cells.
     *  A name-only reference (same shape the AI Query API's {@code cube}
     *  field uses), NOT a full {@code SaikuCube} — {@link
     *  NotebookCubeResolver} resolves it against the live schema at run
     *  time. {@code SaikuCube} itself has no JSON setters (server-authored
     *  only), so a notebook cell — client-authored, persisted JSON —
     *  cannot safely round-trip one. */
    public AiCubeRef cube;
}
