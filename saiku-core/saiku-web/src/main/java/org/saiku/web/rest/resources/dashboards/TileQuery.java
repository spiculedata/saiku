/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.dashboards;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.saiku.service.olap.ai.AiQueryRequest;

/**
 * Discriminated union of "reference a saved query" vs "carry the query
 * inline". {@link #kind} drives which of {@link #path} or {@link #body}
 * is populated.
 *
 * <p>Kept as a flat POJO with a string discriminator rather than a Jackson
 * polymorphic-type hierarchy — the saved JSON is read by a TypeScript
 * frontend that uses the same shape verbatim, and keeping the wire format
 * trivial avoids Jackson type-info headers leaking into the user-facing
 * file.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TileQuery {

    /** {@code "reference" | "inline"}. */
    public String kind;

    /** Repository path to a {@code .saiku} query file. Populated when
     *  {@link #kind} is {@code "reference"}. */
    public String path;

    /** Inline query body. Populated when {@link #kind} is {@code "inline"}.
     *  Reuses the existing AI Query API request shape so the tile's
     *  effective-query merge logic can lean on the same converter. */
    public AiQueryRequest body;
}
